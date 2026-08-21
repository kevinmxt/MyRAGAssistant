# MyAIDemo2 — 本地知识库智能问答系统

## 命令

| 命令 | 用途 |
|------|------|
| `mvn compile` | 编译 |
| `mvn test -Dtest=MilvusSessionIT` | 运行向量库会话集成测试（需 Docker） |
| `mvn test` | 运行 138 个单元测试 |
| `mvn test -P evaluation` | 运行 RAG 效果评估（对比基线） |
| `mvn test jacoco:report` | 覆盖率报告 → `target/site/jacoco/index.html` |
| `mvn clean package` | 构建 Fat JAR |
| `docker compose up -d` | 启动 Milvus（首次运行前必须） |
| `java -jar target/MyAIDemo2-1.0-SNAPSHOT.jar` | 启动应用 (http://localhost:8080) |

## 文档索引

| 文档 | 内容 |
|------|------|
| `README.md` | 用户手册、功能特性、配置说明、API 文档 |
| `docs/architecture.md` | 架构设计原则、依赖注入、切分管线、查询增强、评估管线 |
| `docs/modules.md` | 模块职责一览 |
| `docs/testing.md` | 测试约定与命令 |
| `docs/environment.md` | 环境要求、技术栈、常见问题 |
| `docs/superpowers/specs/2026-08-06-rag-evaluation-design.md` | RAG 效果评估设计文档 |
| `docs/superpowers/plans/2026-08-06-rag-evaluation.md` | RAG 效果评估实现计划 |
| `docs/agents/issue-tracker.md` | GitHub Issues 操作约定 |
| `docs/agents/triage-labels.md` | 五个 triage 标签映射 |
| `docs/agents/domain.md` | 领域文档布局（CONTEXT.md + ADR） |
| `config.example.json` | 配置文件模板 |

## 关键入口

- `me.maxt.rag.web.App` — 应用入口（5 行胶水代码）
- `me.maxt.rag.web.WebApplication` — 启动组装工厂（依赖创建 + 路由注册）
- `me.maxt.rag.web.config.AppConfig` — 配置实现，同时实现 10 个接口
- `me.maxt.rag.web.service.vector.MilvusSession` — 向量库会话（连接生命周期、降级/重连、DEGRADED↔CONNECTED 原子切换，见 CONTEXT.md）
- `me.maxt.rag.web.service.environment.EnvironmentChecker` — 环境检测编排器（并行检测、SSE 广播、安装管理）
- `me.maxt.rag.web.controller.EnvironmentController` — 环境管理 REST 端点
- `me.maxt.rag.web.service.chunking.ChunkingPipeline` — 自适应文档切分管线入口
- `me.maxt.rag.web.service.vector.QueryEnhancementRouter` — 查询增强路由器（改写/HyDE/RRF）
- `me.maxt.rag.web.service.vector.ContextualEnricher` — 上下文增强器（chunk 前缀）
- `me.maxt.rag.web.service.vector.RrfFusion` — RRF 融合工具类（2路/N路）
- `me.maxt.rag.web.service.vector.recall.MultiRecallRouter` — 多路召回路由器（Dense+Sparse+Graph 并行）
- `me.maxt.rag.web.service.vector.recall.LightRagBridge` — Python LightRAG 子进程桥接
- `me.maxt.rag.web.service.KnowledgeGraphService` — LightRAG 知识图谱构建和管理
- `me.maxt.rag.web.service.vector.rerank.CrossEncoderReranker` — ONNX Cross-Encoder 精排器（bge-reranker-v2-m3）
- `me.maxt.rag.web.config.RerankConfig` — 重排序配置接口（模型路径、自动下载、精排TopK）
- `me.maxt.rag.web.service.evaluation.EvaluationPipeline` — 评估管线编排入口（检索/答案质量/基线管理）
- `me.maxt.rag.web.service.evaluation.RetrievalEvaluator` — 检索评估器（Recall/Precision/MRR/NDCG）
- `me.maxt.rag.web.service.evaluation.BaselineManager` — 基线管理器（加载/保存/对比/退化判定）
- `me.maxt.rag.web.config.EvaluationConfig` — 评估配置接口（TopK、格式列表、退化阈值）

## 对话

用中文回复、commit message 写中文。
