# 模块一览

| 模块 | 包路径 | 职责 |
|------|--------|------|
| 配置 | `config/` | AppConfig 实现 6 个配置接口（Llm / Retrieval / Document / Server / QueryEnhancement / Milvus） |
| 文档服务 | `service/DocumentService` | 文档摄入、目录浏览、文件列表 |
| 向量存储 | `service/EmbeddingStoreManager` | Milvus 向量存储，注入 EmbeddingStore 接口 |
| RAG 服务 | `service/RAGService` | 检索增强生成编排，支持查询增强路由 |
| 智能切分 | `service/chunking/` | 文档分块管线：结构分析 → 策略分类 → 语义切分 → 小模型精炼 |
| 查询增强 | `service/vector/` | QueryRewriter / HyDEGenerator / QueryEnhancementRouter / ContextualEnricher |
| 控制器 | `controller/` | ChatController / DocumentController（薄胶水层） |
