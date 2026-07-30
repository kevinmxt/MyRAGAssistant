# 架构

## 依赖注入

`EmbeddingModel`（BgeSmallEnV15）和 `ChatModel`（OpenAiChatModel）在 `App` 入口集中创建，通过构造函数注入 `RAGService` 和 `DocumentService`。不允许 service 内部 `new` 外部依赖。

**共享资源**：ONNX 嵌入模型全局只加载一次，避免多实例浪费内存（每个约 100MB）。

## EmbeddingStoreManager

内部 `InMemoryEmbeddingStore` 完全隐藏，外部只通过 `search()`、`createContentRetriever()` 等接口交互。`StoredEntry` 字段 private。

## 配置接口

`AppConfig` 拆分为 `LlmConfig`、`RetrievalConfig`、`DocumentConfig`、`ServerConfig` 四个聚焦接口。每个 consumer 只依赖它需要的接口。例如 `RAGService` 只依赖 `RetrievalConfig`（3 个 getter）。

## 启动组装

`WebApplication` 封装所有依赖创建和路由注册逻辑，`App.main()` 从 90 行缩减为 5 行胶水代码。wiring 和运行时之间有清晰的 seam，启动逻辑可脱离 `main()` 独立测试。

## 控制器薄层

`DocumentController` 只做 JSON↔对象的转换，业务逻辑（文档分组聚合、目录浏览遍历、文件系统操作）全部下沉到 `DocumentService`。

## 测试

- 25 个单元测试，JUnit 5 + Mockito + AssertJ + JaCoCo
- Service 层覆盖率 >88%：EmbeddingStoreManager 93.8%，DocumentService 88.3%，RAGService 93.9%
- 只 mock 系统边界（EmbeddingModel、ChatModel），使用真实 `EmbeddingStoreManager` 实例
- Controllers 不单独测试（薄胶水层）

## Gotchas

- 首次启动时 BgeSmallEnV15 ONNX 模型自动下载到本地缓存（约 100MB）
- 默认端口 8080，冲突时通过 `server.port` 或 `RAG_SERVER_PORT` 修改
- config.json 放在工作目录（与 JAR 同目录）
- 环境变量优先级高于 config.json
