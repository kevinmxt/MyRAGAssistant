# 模块一览

| 模块 | 包路径 | 职责 |
|------|--------|------|
| 配置 | `config/` | AppConfig 实现 10 个配置接口（Llm / Retrieval / Document / Server / QueryEnhancement / Milvus / Recall / Rerank / Evaluation / EnvCheck） |
| 文档服务 | `service/DocumentService` | 文档摄入、目录浏览、文件列表 |
| 向量存储 | `service/EmbeddingStoreManager` | Milvus 向量存储，注入 EmbeddingStore 接口 |
| RAG 服务 | `service/RAGService` | 检索增强生成编排，支持查询增强路由和多路召回 |
| 知识图谱服务 | `service/KnowledgeGraphService` | LightRAG KG 构建和管理，从 Milvus 回查文档文本 |
| 环境检测 | `service/environment/` | 6 个 DependencyChecker + EnvironmentChecker 编排器，SSE 推送状态 |
| 智能切分 | `service/chunking/` | 文档分块管线：结构分析 → 策略分类 → 语义切分 → 小模型精炼 |
| 查询增强 | `service/vector/` | QueryRewriter / HyDEGenerator / QueryEnhancementRouter / ContextualEnricher / RrfFusion |
| 多路召回 | `service/vector/recall/` | RecallStrategy 接口 + Dense/Sparse/Graph 三路实现 + MultiRecallRouter 编排 |
| 重排序 | `service/vector/rerank/` | Reranker 接口 + CrossEncoderReranker（ONNX bge-reranker-v2-m3 精排） |
| 评估 | `service/evaluation/` | 评估管线：DatasetLoader / RetrievalEvaluator / AnswerQualityEvaluator / BaselineManager / EvaluationPipeline |
| LightRAG 桥接 | `service/vector/recall/LightRagBridge` | Python 常驻子进程桥接，JSON 协议通信 |
| 控制器 | `controller/` | ChatController / DocumentController / EnvironmentController / KnowledgeGraphController（薄胶水层） |
