# 架构

## 依赖注入

`EmbeddingModel`（BgeSmallZhV15，512 维，中文优化）和 `ChatModel`（OpenAiChatModel）在 `App` 入口集中创建，通过构造函数注入 `RAGService` 和 `DocumentService`。不允许 service 内部 `new` 外部依赖。

**共享资源**：ONNX 嵌入模型全局只加载一次，避免多实例浪费内存（每个约 100MB）。

## EmbeddingStoreManager

构造函数注入 `EmbeddingStore<TextSegment>` 接口，生产环境注入 `MilvusEmbeddingStore`，测试注入 `InMemoryEmbeddingStore`。内部维护轻量级 `docIndex`（ConcurrentHashMap）追踪文档元数据（文件名、类型、目录、片段数）。持久化由 Milvus Docker volume 负责。

## 配置接口

`AppConfig` 拆分为 10 个聚焦接口：`LlmConfig`、`RetrievalConfig`、`DocumentConfig`、`ServerConfig`、`QueryEnhancementConfig`、`MilvusConfig`、`RecallConfig`、`RerankConfig`、`EvaluationConfig`、`EnvCheckConfig`。每个 consumer 只依赖它需要的接口。

## 启动组装

`WebApplication` 封装所有依赖创建和路由注册逻辑，`App.main()` 保持 5 行胶水代码。wiring 和运行时之间有清晰的 seam，启动逻辑可脱离 `main()` 独立测试。多路召回组件在 `multiRecall.enabled` 为 true 时才组装，否则为 null，行为与原有逻辑完全一致。

## 控制器薄层

`ChatController`、`DocumentController` 和 `KnowledgeGraphController` 只做 JSON↔对象的转换，业务逻辑全部下沉到 `RAGService`、`DocumentService` 和 `KnowledgeGraphService`。

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
| `SemanticSplitter` | 基于 embedding 余弦相似度断点切分（含后合并阶段防止过度碎片化） |
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
| `QueryEnhancementRouter` | 根据模式（auto/rewrite/hyde/both/none）路由到对应策略 |
| `RrfFusion` | 独立的 RRF（Reciprocal Rank Fusion）融合工具类，支持 2 路和 N 路融合 |

`RAGService` 集成 `QueryEnhancementRouter`，多查询变体结果通过文本去重 + 最高分保留融合。

## 多路召回（Multi Recall）

`service/vector/recall/` 包实现了可插拔的多策略召回架构：

```
MultiRecallRouter → [DenseRecallStrategy, SparseRecallStrategy, GraphRecallStrategy] → RrfFusion
```

| 组件 | 职责 |
|------|------|
| `RecallStrategy` | 召回策略接口，定义 `name()` + `recall(query, topK)` |
| `DenseRecallStrategy` | 稠密向量检索，委托 `EmbeddingStoreManager` 的 ONNX embedding 搜索 |
| `SparseRecallStrategy` | Milvus 原生 BM25 稀疏检索，启动时探测 `sparse_vector` 字段，缺失时自动降级 |
| `GraphRecallStrategy` | 通过 `LightRagBridge` 调用 LightRAG 知识图谱检索，图谱未构建时自动降级 |
| `MultiRecallRouter` | 并行调用各策略（CompletableFuture + 30s 超时），RRF 融合后返回 topK 结果 |
| `LightRagBridge` | Python LightRAG 常驻子进程桥接（JSON 协议），负责 insert/query |

**降级容错**：单路策略失败或超时不影响其他路；所有策略均失败时返回空列表。

**启用方式**：配置 `multiRecall.enabled: true`，通过 `multiRecall.modes` 指定启用模式（dense/sparse/graph）。

## 重排序（Re-rank）


## 评估管线（Evaluation）

`service/evaluation/` 包实现 RAG 效果自动化评估体系，支持五种文档格式独立测试基线、检索质量 + 答案质量双维度评估：

```
EvaluationPipeline
  ├── DatasetLoader        → 加载 testcases.json
  ├── KnowledgeBaseSeeder   → 切分→向量化→InMemory 入库
  ├── RAGService           → 实时生成答案 + 检索来源
  ├── RetrievalEvaluator    → Recall@K, Precision@K, MRR, NDCG（纯数学）
  ├── AnswerQualityEvaluator → LLM-as-Judge (Faithfulness, AnswerRelevancy)
  └── BaselineManager       → 基线加载/保存/对比/退化判定
```

| 组件 | 职责 |
|------|------|
| `EvaluationPipeline` | 评估管线编排入口，串联全部组件执行单格式评估 |
| `DatasetLoader` | 从 classpath 加载 `evaluation/<format>/testcases.json`，校验字段完整性 |
| `KnowledgeBaseSeeder` | 将 `docs/` 下文档走完整切分→向量化→入库，使用 InMemoryEmbeddingStore 隔离生产数据 |
| `EvaluationMetric` | 检索指标接口（可扩展），内置 Recall@K / Precision@K / MRR / NDCG@K |
| `RetrievalEvaluator` | 编排所有检索指标计算 |
| `AnswerQualityMetric` | 答案质量指标接口（可扩展），内置 Faithfulness / AnswerRelevancy |
| `AnswerQualityEvaluator` | LLM-as-Judge 编排，复用应用 ChatModel，temperature=0 |
| `BaselineManager` | 基线生命周期：首次生成写入 `src/test/resources`，后续对比报告退化 |
| `EvaluationReport` | 报告 DTO，覆盖 baseline / 时间戳 / summary 三种格式 |
| `TestCase` | 测试用例 DTO，映射 testcases.json 单条记录 |

**隔离策略**：评估使用 InMemoryEmbeddingStore，不与生产 Milvus 数据混合。通过 Maven `evaluation` profile 隔离运行（`mvn test -P evaluation`），默认 `mvn test` 不执行评估。

**基线管理**：首次运行生成 baseline.json 写入 `src/test/resources/evaluation/<format>/`（提交 git）；后续运行对比基线，退化 >5% 告警。手动更新基线用 `-DupdateBaseline`。

## 重排序（Re-rank）

`service/vector/rerank/` 包实现 Cross-Encoder 精排层，在多路召回/RRF 融合之后对候选片段进行语义相关性重打分：

```
RRF 融合结果 → CrossEncoderReranker (精排) → LLM
```

| 组件 | 职责 |
|------|------|
| `Reranker` | 重排序接口，定义 `name()` + `isAvailable()` + `rerank(query, candidates, topK)` |
| `CrossEncoderReranker` | ONNX Cross-Encoder 精排实现（bge-reranker-v2-m3），对每个 (query, passage) 对独立打分后按分数降序取 topK |

**降级容错**：启动时自动检测精排模型文件（`models/bge-reranker-v2-m3/model.onnx`），缺失时日志警告 + 跳过；自动下载开启时，后台异步下载模型，下载完成后自动启用；加载失败（模型损坏等）同样降级，不阻塞应用启动。`RAGService` 中 reranker 为 null 或不可用时不执行精排。

**启用方式**：始终生效，无需配置开关。模型缺失时自动降级或后台下载。

## 知识图谱（Knowledge Graph）

| 组件 | 职责 |
|------|------|
| `KnowledgeGraphService` | KG 构建和管理：从 Milvus 回查文档文本，通过 `LightRagBridge` 交给 Python LightRAG 建索引 |
| `KnowledgeGraphController` | KG API 端点：按目录/单文档触发构建，查询构建状态 |

图谱索引由用户通过 API 手动触发，支持按目录或单文档构建。构建状态通过 `AtomicBoolean` + `AtomicReference` 管理，线程安全。

## 环境检测（Environment Check）

`service/environment/` 包实现启动时非阻塞检测所有外部依赖，前端通过 SSE 实时接收状态：

```
EnvironmentChecker (编排器)
  ├── PythonChecker          → python --version
  ├── PipPackageChecker       → pip show lightrag requests
  ├── MilvusChecker           → TCP connect + gRPC version
  ├── PandocChecker           → pandoc --version
  ├── TesseractChecker        → tesseract --version
  └── ModelFileChecker        → 检查 model.onnx + 嵌入模型路径
```

| 组件 | 职责 |
|------|------|
| `DependencyChecker` | 检测器接口：`check()`、`canAutoInstall()`、`autoInstall(consumer)` |
| `EnvironmentChecker` | 编排器：并行检测（ExecutorService + invokeAll 超时）、串行安装队列、SSE 广播 |
| `ProcessRunner` | 进程探测工具：`run()` 超时执行 + `runStreaming()` 流式输出日志 |
| `EnvironmentController` | REST 端点：`/api/env/status`、`/api/env/stream`(SSE)、`/api/env/install`、`/api/env/check` |

**降级容错**：检测失败不阻塞启动；可选依赖缺失标记为 SKIPPED（非 MISSING）；安装仅支持 pip 包，串行执行；SSE 断连自动重连。

**启用方式**：始终生效，配置 `environment.enabled: false` 可关闭。

## 测试

详见 [`docs/testing.md`](testing.md)。

## 环境与 Gotchas

详见 [`docs/environment.md`](environment.md)。
