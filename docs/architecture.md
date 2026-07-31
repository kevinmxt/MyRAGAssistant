# 架构

## 依赖注入

`EmbeddingModel`（BgeSmallZhV15，512 维，中文优化）和 `ChatModel`（OpenAiChatModel）在 `App` 入口集中创建，通过构造函数注入 `RAGService` 和 `DocumentService`。不允许 service 内部 `new` 外部依赖。

**共享资源**：ONNX 嵌入模型全局只加载一次，避免多实例浪费内存（每个约 100MB）。

## EmbeddingStoreManager

内部 `InMemoryEmbeddingStore` 完全隐藏，外部只通过 `search()`、`createContentRetriever()` 等接口交互。`StoredEntry` 字段 private。

## 配置接口

`AppConfig` 拆分为 6 个聚焦接口：`LlmConfig`、`RetrievalConfig`、`DocumentConfig`、`ServerConfig`、`ChunkingConfig`、`QueryEnhancementConfig`。每个 consumer 只依赖它需要的接口。

## 启动组装

`WebApplication` 封装所有依赖创建和路由注册逻辑，`App.main()` 从 90 行缩减为 5 行胶水代码。wiring 和运行时之间有清晰的 seam，启动逻辑可脱离 `main()` 独立测试。

## 控制器薄层

`ChatController` 和 `DocumentController` 只做 JSON↔对象的转换，业务逻辑全部下沉到 `RAGService` 和 `DocumentService`。

## 智能文档切分（Chunking Pipeline）

`service/chunking/` 包实现多策略文档分块管线：

```
StructureAnalyzer → SplitClassifier → StructureSplitter/SemanticSplitter → AgentRefiner
```

| 组件 | 职责 |
|------|------|
| `StructureAnalyzer` | 基于 flexmark 提取 Markdown 标题层级结构 |
| `SplitClassifier` | 根据文档特征（结构丰富度、段落密度）选择切分策略 |
| `StructureSplitter` | 按标题层级切分文档 |
| `SemanticSplitter` | 基于 embedding 余弦相似度断点切分 |
| `AgentRefiner` | 小模型驱动的切分精炼器，合并过短片段 |
| `MarkdownConverter` | 多格式转 Markdown（Pandoc + Tika 降级） |
| `ChunkEvaluator` | 切分质量评估 |
| `ChunkingPipeline` | 编排层，串联上述组件 |

策略接口 `SplitStrategy` 和各实现类通过 `ChunkingConfig` 配置参数。

## 查询增强（Query Enhancement）

`service/vector/` 包提供查询增强能力：

| 组件 | 职责 |
|------|------|
| `QueryEnhancer` | 增强策略接口，定义 `enhance(query)` → `List<String>` |
| `QueryRewriter` | LLM 改写简短口语化问题 |
| `HyDEGenerator` | 生成假设性答案作为增强查询变体 |
| `QueryEnhancementRouter` | 根据模式（auto/rewrite/hyde/both/none）路由到对应策略，包含 RRF 融合 |

`RAGService` 集成 `QueryEnhancementRouter`，多查询变体结果通过文本去重 + 最高分保留融合。

## 测试

- 61 个单元测试，JUnit 5 + Mockito + AssertJ + JaCoCo
- Service 层覆盖率 >88%
- 只 mock 系统边界（EmbeddingModel、ChatModel），使用真实 `EmbeddingStoreManager` 实例
- Controllers 不单独测试（薄胶水层）

## Gotchas

- 首次启动时 BgeSmallZhV15 ONNX 模型自动下载到本地缓存（约 100MB）
- 默认端口 8080，冲突时通过 `server.port` 或 `RAG_SERVER_PORT` 修改
- config.json 放在工作目录（与 JAR 同目录）
- 环境变量优先级高于 config.json
