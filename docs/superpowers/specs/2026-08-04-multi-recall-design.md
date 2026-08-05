# 多路召回 — 设计文档

## 目标

将现有单一稠密向量检索升级为三路并行召回：稠密向量 + 稀疏向量（BM25）+ 知识图谱（LightRAG），统一 RRF 融合后返回结果。

## 需求摘要

| 决策 | 选择 |
|------|------|
| 召回路径 | 稠密向量 + 稀疏向量(BM25) + 知识图谱(LightRAG) |
| KG 方案 | LightRAG，JVM 内嵌 Python（JPype）调用 |
| 稀疏检索 | Milvus 原生 BM25（`BM25EmbeddingFunction`） |
| KG 索引触发 | 用户手动触发，支持按目录/单文档 |
| 融合策略 | 统一 RRF 三路融合 |
| 前端 | 文档管理页新增"构建图谱"按钮 |

## 架构变更

```
Before:
  RAGService
    ├── EmbeddingStoreManager.search()        (稠密向量)
    ├── QueryEnhancementRouter.route()        (查询增强)
    └── AnswerWithSources

After:
  RAGService
    ├── MultiRecallRouter.recall(query, modes)   (多路召回编排)
    │     ├── DenseRecallStrategy                  (委托 EmbeddingStoreManager)
    │     ├── SparseRecallStrategy                 (Milvus 原生 BM25)
    │     ├── GraphRecallStrategy                  (JPype → LightRAG)
    │     └── RRF 融合
    ├── QueryEnhancementRouter.route()             (查询增强，不变)
    ├── KnowledgeGraphService                      (KG 构建/状态管理)
    └── AnswerWithSources
```

## 新增模块

### `me.maxt.rag.web.service.vector.recall` 包

| 文件 | 职责 |
|------|------|
| `RecallStrategy.java` | 接口 `List<EmbeddingMatch<TextSegment>> recall(String query, int topK)` |
| `DenseRecallStrategy.java` | 委托 `EmbeddingStoreManager.search()`，现有稠密检索逻辑 |
| `SparseRecallStrategy.java` | `MilvusServiceClient.search()` + `AnnSearchParam` 稀疏模式，BM25 分词 |
| `GraphRecallStrategy.java` | JPype 启动 Python 解释器，调用 `lightrag.query(query, mode)` |
| `MultiRecallRouter.java` | 编排：并行调用活跃策略 → RRF 融合 → topK |
| `RecallConfig.java` | 配置接口：enabled / modes / topK / rrfK |

### SparseRecallStrategy 关键细节

- Milvus collection `rag_knowledge_base` 新增稀疏向量字段 `sparse_vector`，绑定 BM25 分词函数到 `text` 字段
- 使用 `io.milvus:milus-sdk-java` 直接调用原生 API（langchain4j 的 `EmbeddingStore.search()` 只支持稠密）
- 存量数据无稀疏向量索引时自动降级跳过

### GraphRecallStrategy / KnowledgeGraphService 关键细节

- **LightRAG 配置**：存储后端本地文件 `data/kg/`，嵌入模型复用 BGE 路径，LLM 复用 DeepSeek API 配置
- **检索模式**：默认 `hybrid`（可配置 `local`/`global`/`naive`）
- **索引时机**：通过 `KnowledgeGraphService` 手动触发，支持 `buildForDirectory(path)` 和 `buildForDocument(docId)`
- **降级**：Python 环境未初始化或图谱未构建时自动跳过

## 受影响的现有文件

| 文件 | 变更类型 | 说明 |
|------|----------|------|
| `pom.xml` | 修改 | 新增 `io.milvus:milus-sdk-java`、`org.jpype:jpype-core` |
| `RAGService.java` | 修改 | `answerWithSources` 增加 recallModes 参数，集成 MultiRecallRouter |
| `ChatController.java` | 修改 | 请求体新增 `recall` 字段，解析后传入 RAGService |
| `QueryEnhancementRouter.java` | 重构 | RRF 融合逻辑抽为静态工具方法，供 MultiRecallRouter 复用 |
| `WebApplication.java` | 修改 | 组装 MultiRecallRouter、KnowledgeGraphService，注册新路由 |
| `AppConfig.java` | 修改 | 新增 MultiRecallConfig、LightRAGConfig 配置解析 |
| `config.example.json` | 修改 | 新增 `multiRecall` 配置节 |

## 新增 API 端点

| 方法 | 路径 | 用途 |
|------|------|------|
| `POST` | `/api/kg/build?path={相对目录}` | 对目录下所有文档构建知识图谱 |
| `POST` | `/api/kg/build/{docId}` | 对单个文档构建知识图谱 |
| `GET` | `/api/kg/status` | 查询图谱构建状态、已索引文档列表 |

## 现有 API 变更

`POST /api/chat` 请求体新增可选字段：

```json
{
  "query": "...",
  "enhancement": "auto",
  "recall": ["dense", "sparse", "graph"]
}
```

- 默认 `["dense"]`，向后兼容
- ChatController 解析 `recall` 字段传给 RAGService

## 前端改动

文档管理页面（`src/main/resources/static/`）新增：
- 每行文档 **"构建图谱"** 按钮 → `POST /api/kg/build/{docId}`
- 目录顶部 **"批量构建图谱"** 按钮 → `POST /api/kg/build?path=xxx`
- 构建状态指示（未构建/构建中/已完成/失败）

## 配置设计

`config.json` 新增 `multiRecall` 节：

```json
{
  "multiRecall": {
    "enabled": false,
    "modes": ["dense", "sparse", "graph"],
    "rrfK": 60,
    "topK": 5,
    "lightrag": {
      "pythonPath": "python",
      "workingDir": "data/kg",
      "embeddingModelPath": "models/bge-small-zh-v1.5",
      "queryMode": "hybrid"
    }
  }
}
```

| 字段 | 默认值 | 环境变量 |
|------|--------|----------|
| `enabled` | `false` | `RAG_MULTI_RECALL_ENABLED` |
| `modes` | `["dense"]` | `RAG_MULTI_RECALL_MODES` |
| `rrfK` | `60` | `RAG_MULTI_RECALL_RRF_K` |
| `topK` | `5` | `RAG_MULTI_RECALL_TOP_K` |
| `lightrag.pythonPath` | `python` | `RAG_LIGHTRAG_PYTHON` |
| `lightrag.workingDir` | `data/kg` | `RAG_LIGHTRAG_WORKDIR` |
| `lightrag.embeddingModelPath` | `models/bge-small-zh-v1.5` | `RAG_LIGHTRAG_EMBEDDING` |
| `lightrag.queryMode` | `hybrid` | `RAG_LIGHTRAG_QUERY_MODE` |

## 测试

### 单元测试

| 测试类 | 内容 |
|--------|------|
| `MultiRecallRouterTest` | 策略编排、RRF 融合、策略降级（某路失败不影响其他） |
| `SparseRecallStrategyTest` | BM25 检索逻辑（Mock MilvusServiceClient） |
| `GraphRecallStrategyTest` | LightRAG 调用逻辑（Mock Python 接口） |
| `RecallConfigTest` | 配置解析、默认值、向后兼容 |

### 集成测试

| 测试类 | 内容 |
|--------|------|
| `MultiRecallRouterIT` | 端到端三路召回 + RRF 融合效果验证 |
| `KnowledgeGraphServiceIT` | KG 构建/状态查询全流程 |
| `SparseRecallIT` | Milvus BM25 真实检索（需 Milvus 运行） |

## 不动的文件

`DocumentService`、`ChunkingPipeline`、`ContextualEnricher`、`EmbeddingStoreManager`（仅 DenseRecallStrategy 继续委托它，其内部不变）——摄入侧不受影响。

## 风险与缓解

| 风险 | 缓解 |
|------|------|
| JPype 与 JDK 版本兼容性 | 用 `jpype-core >=1.5.0`，支持 JDK 17+；启动时做 Python 环境探测 |
| BM25 需要修改 Milvus collection schema | 启动时检查字段是否存在，不存在则 `ALTER COLLECTION` 新增；存量数据需重新写入以生成稀疏向量 |
| LightRAG 索引耗时长（LLM 调用多） | 异步执行，通过 `/api/kg/status` 查询进度 |
| 三路检索增加延迟（3 次网络调用） | 并行调用 + Future，总延迟 = max(各路延迟) |
