# API 使用指南

## 基础信息

- 服务地址: `http://localhost:8080`
- 请求格式: JSON / multipart/form-data
- 响应格式: JSON

## API 列表

### 1. 上传文档

```
POST /api/documents/upload
Content-Type: multipart/form-data

参数:
  - file: 文档文件（必填）
  - format: 文档格式（可选，自动检测）

响应:
{
  "success": true,
  "documentId": "doc_abc123",
  "fileName": "技术文档.md",
  "chunkCount": 15
}
```

### 2. 知识库问答

```
POST /api/chat
Content-Type: application/json

请求体:
{
  "query": "MyAIDemo2 支持哪些文档格式？",
  "topK": 5
}

响应:
{
  "answer": "MyAIDemo2 支持 Markdown、TXT、PDF、DOCX、JSON 等多种文档格式。",
  "sources": [
    {
      "fileName": "project-overview.md",
      "content": "支持 Markdown、TXT、PDF、DOCX、JSON 等多种格式...",
      "score": 0.95
    }
  ]
}
```

### 3. 查看知识库状态

```
GET /api/knowledge-base/status

响应:
{
  "documentCount": 42,
  "chunkCount": 356,
  "embeddingModel": "BGE-small-zh-v1.5",
  "vectorStore": "Milvus"
}
```

### 4. 删除文档

```
DELETE /api/documents/{documentId}

响应:
{
  "success": true,
  "deletedChunks": 12
}
```

## 配置参数

通过 `config.json` 或环境变量可配置以下参数：

| 参数 | 环境变量 | 默认值 |
|------|----------|--------|
| LLM API 地址 | `LLM_BASE_URL` | `http://localhost:11434/v1` |
| LLM 模型名 | `LLM_MODEL_NAME` | `qwen2.5:7b` |
| 嵌入模型路径 | `EMBEDDING_MODEL_PATH` | `models/bge-small-zh-v1.5` |
| Milvus 地址 | `MILVUS_HOST` | `localhost` |
| Milvus 端口 | `MILVUS_PORT` | `19530` |
| 检索 TopK | `RAG_TOP_K` | `5` |
| 召回超时(秒) | `RECALL_TIMEOUT` | `30` |
