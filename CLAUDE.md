# MyAIDemo2 — 本地知识库智能问答系统

## 命令

| 命令 | 用途 |
|------|------|
| `mvn compile` | 编译 |
| `mvn test` | 运行 78 个单元测试 |
| `mvn test jacoco:report` | 覆盖率报告 → `target/site/jacoco/index.html` |
| `mvn clean package` | 构建 Fat JAR |
| `docker compose up -d` | 启动 Milvus（首次运行前必须） |
| `java -jar target/MyAIDemo2-1.0-SNAPSHOT.jar` | 启动应用 (http://localhost:8080) |

## 文档索引

| 文档 | 内容 |
|------|------|
| `README.md` | 用户手册、功能特性、配置说明、API 文档 |
| `docs/architecture.md` | 架构设计原则、依赖注入、切分管线、查询增强 |
| `docs/modules.md` | 模块职责一览 |
| `docs/testing.md` | 测试约定与命令 |
| `docs/environment.md` | 环境要求、技术栈、常见问题 |
| `docs/agents/issue-tracker.md` | GitHub Issues 操作约定 |
| `docs/agents/triage-labels.md` | 五个 triage 标签映射 |
| `docs/agents/domain.md` | 领域文档布局（CONTEXT.md + ADR） |
| `config.example.json` | 配置文件模板 |

## 关键入口

- `me.maxt.rag.web.App` — 应用入口（5 行胶水代码）
- `me.maxt.rag.web.WebApplication` — 启动组装工厂（依赖创建 + 路由注册）
- `me.maxt.rag.web.config.AppConfig` — 配置实现，同时实现 6 个接口
- `me.maxt.rag.web.service.chunking.ChunkingPipeline` — 自适应文档切分管线入口
- `me.maxt.rag.web.service.vector.QueryEnhancementRouter` — 查询增强路由器（改写/HyDE/RRF）
- `me.maxt.rag.web.service.vector.ContextualEnricher` — 上下文增强器（chunk 前缀）

## 对话

用中文回复、commit message 写中文。
