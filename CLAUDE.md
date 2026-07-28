## Agent skills

### Issue tracker

Issues 在 GitHub Issues 中管理，使用 `gh` CLI 操作。详见 `docs/agents/issue-tracker.md`。

### Triage labels

使用默认五个 triage 标签：`needs-triage`、`needs-info`、`ready-for-agent`、`ready-for-human`、`wontfix`。详见 `docs/agents/triage-labels.md`。

### Domain docs

单上下文布局：`CONTEXT.md` + `docs/adr/` 在仓库根目录。详见 `docs/agents/domain.md`。

## 架构

- **依赖注入**：`EmbeddingModel`（BgeSmallEnV15）和 `ChatModel`（OpenAiChatModel）在 `App` 入口集中创建，通过构造函数注入 `RAGService` 和 `DocumentService`。不允许 service 内部 `new` 外部依赖。
- **共享资源**：ONNX 嵌入模型全局只加载一次，避免多实例浪费内存。
- **EmbeddingStoreManager**：内部 `InMemoryEmbeddingStore` 完全隐藏，外部只通过 `search()`、`createContentRetriever()` 等接口交互。`StoredEntry` 字段 private。
- **配置接口**：`AppConfig` 拆分为 `LlmConfig`、`RetrievalConfig`、`DocumentConfig`、`ServerConfig` 四个聚焦接口。每个 consumer 只依赖它需要的接口。
