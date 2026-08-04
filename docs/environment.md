# 环境要求

## 必需

- **JDK 17+**、**Maven 3.6+**、**Docker Desktop** — Milvus 向量数据库需要

## 可选

- **Tesseract OCR** — PNG/JPG 图像提取文字需要
- **Pandoc** — PDF/DOCX 高质量转 Markdown 需要

## 技术栈

- 嵌入模型：**BgeSmallZhV15**（ONNX 本地推理，512 维，中文优化）
- 向量数据库：**Milvus**（Docker standalone，端口 19530）
- 首次启动时 ONNX 模型自动下载到本地缓存（约 100MB）

## 配置优先级

环境变量优先级高于 `config.json`。`config.json` 放在工作目录（与 JAR 同目录）。

## 常见问题

- 默认端口 8080，冲突时通过 `server.port` 或 `RAG_SERVER_PORT` 修改
- Milvus 需通过 `docker compose up -d` 提前启动
- MilvusEmbeddingStore 默认 consistencyLevel=EVENTUALLY，本应用显式设为 STRONG 保证写入立即可查
