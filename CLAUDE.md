# MyAIDemo2 — 本地知识库智能问答系统

## 命令

| 命令 | 用途 |
|------|------|
| `mvn compile` | 编译 |
| `mvn test` | 运行 76 个单元测试 |
| `mvn test jacoco:report` | 覆盖率报告 → `target/site/jacoco/index.html` |
| `mvn clean package` | 构建 Fat JAR |
| `docker compose up -d` | 启动 Milvus（首次运行前必须） |
| `java -jar target/MyAIDemo2-1.0-SNAPSHOT.jar` | 启动应用 (http://localhost:8080) |

## 目录索引

| 文档 | 内容 |
|------|------|
| `README.md` | 用户手册、功能特性、配置说明、API 文档 |
| `docs/architecture.md` | 架构设计原则、模块职责、依赖关系 |
| `docs/agents/issue-tracker.md` | GitHub Issues 操作约定 |
| `docs/agents/triage-labels.md` | 五个 triage 标签映射 |
| `docs/agents/domain.md` | 领域文档布局（CONTEXT.md + ADR） |
| `config.example.json` | 配置文件模板 |

## 模块一览

| 模块 | 包路径 | 职责 |
|------|--------|------|
| 配置 | `config/` | AppConfig 实现 6 个配置接口（Llm / Retrieval / Document / Server / QueryEnhancement / Milvus） |
| 文档服务 | `service/DocumentService` | 文档摄入、目录浏览、文件列表 |
| 向量存储 | `service/EmbeddingStoreManager` | Milvus 向量存储，注入 EmbeddingStore 接口 |
| RAG 服务 | `service/RAGService` | 检索增强生成编排，支持查询增强路由 |
| 智能切分 | `service/chunking/` | 文档分块管线：结构分析 → 策略分类 → 语义切分 → 小模型精炼 |
| 查询增强 | `service/vector/` | QueryRewriter / HyDEGenerator / QueryEnhancementRouter / ContextualEnricher |
| 控制器 | `controller/` | ChatController / DocumentController（薄胶水层） |

## 测试约定

- JUnit 5 + Mockito + AssertJ，76 个单元测试，Service 层覆盖率 >79%
- **只 mock 系统边界**（EmbeddingModel、ChatModel），不 mock 自己的模块
- 测试文件在 `src/test/java/me/maxt/rag/web/` 下，与源码结构一一对应

## 关键入口

- `me.maxt.rag.web.App` — 应用入口（5 行胶水代码）
- `me.maxt.rag.web.WebApplication` — 启动组装工厂（依赖创建 + 路由注册）
- `me.maxt.rag.web.config.AppConfig` — 配置实现，同时实现 6 个接口
- `me.maxt.rag.web.service.chunking.ChunkingPipeline` — 自适应文档切分管线入口
- `me.maxt.rag.web.service.vector.QueryEnhancementRouter` — 查询增强路由器（改写/HyDE/RRF）
- `me.maxt.rag.web.service.vector.ContextualEnricher` — 上下文增强器（chunk 前缀）

## 环境

- **JDK 17+**、**Maven 3.6+**、**Docker Desktop** — Milvus 向量数据库需要
- （可选）**Tesseract OCR** — PNG/JPG 图像提取文字需要
- （可选）**Pandoc** — PDF/DOCX 高质量转 Markdown 需要
- 环境变量优先级高于 `config.json`
- 嵌入模型：**BgeSmallZhV15**（ONNX 本地推理，512 维，中文优化）
- 向量数据库：**Milvus**（Docker standalone，端口 19530）

## 对话

用中文回复、commit message 写中文。
