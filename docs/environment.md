# 环境要求

## 必需

- **JDK 17+**、**Maven 3.6+**、**Docker Desktop** — Milvus 向量数据库需要

## 可选

- **Tesseract OCR** — PNG/JPG 图像提取文字需要
- **Pandoc** — PDF/DOCX 高质量转 Markdown 需要
- **Python 3.10+** — 知识图谱（LightRAG）依赖，应用启动时自动初始化，失败自动降级不影响其他功能；需安装 `lightrag` 和 `requests` 包
- **ONNX Runtime** — 重排序功能需要，随 Maven 依赖自动引入，无需手动安装

## 技术栈

- 嵌入模型：**BgeSmallZhV15**（ONNX 本地推理，512 维，中文优化）
- 向量数据库：**Milvus**（Docker standalone，端口 19530）
- 知识图谱：**LightRAG**（Python 子进程桥接，后台非阻塞初始化）
- 精排模型：**bge-reranker-v2-m3**（ONNX Cross-Encoder，首次启动后台自动下载）
- 首次启动时 ONNX 嵌入模型自动下载到本地缓存（约 100MB）

## 环境检测与管理

应用启动时**后台非阻塞**检测所有外部依赖，前端"环境管理"Tab 可查看实时状态。

### 检测项

| 检测项 | 分类 | 必需/可选 | 可自动安装 |
|--------|------|----------|-----------|
| Python 3.10+ + lightrag + requests | RUNTIME | 必需（KG 功能） | pip 包可 |
| Milvus (Docker) | SERVICE | 必需 | 否 |
| Pandoc | BINARY | 可选 | 否 |
| Tesseract OCR | BINARY | 可选 | 否 |
| 精排模型 (bge-reranker-v2-m3) | MODEL | 必需（精排功能） | 已有后台下载 |
| LightRAG 嵌入模型 | MODEL | 必需（KG 功能） | 否 |

### 前端界面

- 顶部 Tab 切换"智能问答"和"环境管理"
- 环境管理页显示所有依赖卡片，实时状态（✓ 正常 / ✗ 缺失 / ⚠ 可选缺失）
- 可自动安装的依赖提供"安装"按钮，点击后 SSE 实时推送 pip install 日志
- 不可自动安装的依赖提供"安装指引"，按平台显示命令（winget / brew / apt）
- 状态通过 SSE 热更新，无需刷新或重启

### API 端点

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/api/env/status` | 环境状态快照 (JSON) |
| GET | `/api/env/stream` | SSE 实时流 |
| POST | `/api/env/install` | 触发安装 (`{"name":"python"}`) |
| POST | `/api/env/check` | 触发全量重检 |

### 配置

在 `config.json` 中配置：

```json
"environment": {
  "enabled": true,
  "autoInstall": false,
  "checkTimeoutSeconds": 15,
  "probeTimeoutSeconds": 5
}
```

环境变量: `RAG_ENV_CHECK_ENABLED`, `RAG_ENV_AUTO_INSTALL`, `RAG_ENV_CHECK_TIMEOUT`, `RAG_ENV_PROBE_TIMEOUT`

### 扩展新依赖

新增外部依赖时，需要：
1. 在 `me.maxt.rag.web.service.environment` 包中创建 `DependencyChecker` 实现类
2. 在 `WebApplication.buildEnvironmentChecker()` 工厂方法中注册
3. 前端 `env.js` 的 `showInstallGuide()` 中补充安装指引

## 配置优先级

环境变量优先级高于 `config.json`。`config.json` 放在工作目录（与 JAR 同目录）。
多路召回（`multiRecall.enabled`）控制 graph 召回路路由，LightRAG 子进程始终初始化。详见 `config.example.json`。

## 常见问题

- 默认端口 8080，冲突时通过 `server.port` 或 `RAG_SERVER_PORT` 修改
- Milvus 需通过 `docker compose up -d` 提前启动
- MilvusEmbeddingStore 默认 consistencyLevel=EVENTUALLY，本应用显式设为 STRONG 保证写入立即可查
- 环境状态热更新，Milvus 断连后重启无需重启应用，在前端点"重试连接"即可
