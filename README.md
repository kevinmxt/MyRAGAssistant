# MyAIDemo2 - 本地知识库智能问答系统

基于 LangChain4j + DeepSeek + Javalin 的本地知识库智能问答系统，支持多模态文档索引和检索增强生成（RAG）。

## 项目简介

本系统可以将本地文档目录中的多格式文件（TXT、PDF、DOCX、PNG 等）自动分块、向量化并存入本地向量数据库。用户通过 Web 界面输入自然语言问题，系统会检索最相关的文档片段，结合大语言模型生成精准回答，并附上参考来源。

## 技术栈

| 组件 | 技术 |
|------|------|
| Web 框架 | Javalin 6.x（内嵌 Jetty） |
| AI 框架 | LangChain4j 1.12.1 |
| LLM | DeepSeek v4-flash（OpenAI 兼容 API） |
| 嵌入模型 | BgeSmallZhV15（本地 ONNX 推理，中文优化，无需 GPU） |
| 文档解析 | Apache Tika 3.x（支持 PDF/DOCX/PNG 等多模态） |
| 文档切分 | flexmark-java + 自适应混合策略（结构/语义/智能体） |
| Markdown 转换 | Pandoc（可选）+ Tika fallback |
| 向量存储 | Milvus（Docker standalone） |
| 前端 | 纯 HTML/CSS/JS（SPA） |
| 打包 | Maven Shade Plugin（Fat JAR） |

## 功能特性

- **多模态文档支持** — TXT、PDF、DOCX、DOC、PNG、JPG、Markdown、HTML、CSV、JSON、XLSX、PPTX
- **目录浏览选择** — Web 界面内置目录浏览器，可视化选择知识库目录
- **自适应文档切分** — 根据文档结构、大小、类型自动选择最优切分策略（结构/语义/智能体），提升检索片段质量
- **中文嵌入优化** — 本地 ONNX 中文嵌入模型（BgeSmallZhV15，512 维），检索精度更高
- **上下文增强** — 嵌入前为每个 chunk 附加文档名和章节路径，让向量感知上下文
- **查询增强** — LLM 驱动的查询改写 + HyDE（假设文档生成）+ RRF 融合，智能适配不同问题类型
- **自动向量化** — 本地 ONNX 模型生成嵌入向量，支持批量处理
- **向量数据持久化** — 向量数据存储于 Milvus，通过 Docker volume 持久化，重启后自动恢复
- **RAG 智能问答** — 基于检索增强生成的智能问答，答案附带参考来源
- **可配置参数** — 通过 config.json 和环境变量灵活配置所有参数
- **支持中文对话** — 系统提示词为中文，对话体验友好
- **对话记忆** — 基于滑动窗口的多轮对话上下文
- **依赖注入架构** — 嵌入模型和聊天模型集中创建、构造函数注入，全局共享单实例

## 环境要求

- **JDK 17+**
- **Maven 3.6+**
- （可选）**Tesseract OCR** — 如需从 PNG/JPG 等图像中提取文字，需要系统安装 Tesseract
- （可选）**Pandoc** — 如需高质量 PDF/DOCX 转 Markdown，建议安装 Pandoc

## 快速开始

### 0. 启动 Milvus

```bash
docker compose up -d
```

### 1. 配置

在项目根目录创建 `config.json`（可参考 `config.example.json`）：

```json
{
  "llm": {
    "apiKey": "你的DeepSeek-API-Key",
    "baseUrl": "https://api.deepseek.com",
    "modelName": "deepseek-v4-flash"
  },
  "document": {
    "dir": "./documents",
    "chunkSize": 300,
    "chunkOverlap": 0,
    "supportedExtensions": [".txt", ".pdf", ".docx", ".doc", ".png", ".jpg", ".jpeg", ".md", ".html", ".csv", ".json", ".xlsx", ".pptx"],
    "chunking": {
      "mode": "auto",
      "semanticThreshold": 0.6,
      "enableAgentRefiner": false,
      "maxChunkSize": 2000
    }
  },
  "queryEnhancement": {
    "enabled": true,
    "defaultMode": "auto",
    "rrfK": 60,
    "hydeMaxTokens": 200
  }
}
```

也可通过环境变量配置（环境变量优先级高于 config.json）：

```bash
export RAG_LLM_API_KEY="你的API-Key"
export RAG_LLM_MODEL_NAME="deepseek-v4-flash"
export RAG_DOCUMENT_DIR="./my-docs"
export RAG_SERVER_PORT=8080
```

### 2. 构建

```bash
mvn clean package
```

构建产物位于 `target/MyAIDemo2-1.0-SNAPSHOT.jar`（Fat JAR，包含所有依赖）。

### 3. 运行

```bash
java -jar target/MyAIDemo2-1.0-SNAPSHOT.jar
```

启动后访问 `http://localhost:8080`。

首次启动时，如果 `document.dir` 配置的目录存在且有支持格式的文件，系统会自动摄入。

### 4. 使用

1. 在左侧面板的"摄入新文档"区域，点击**浏览**按钮选择文档目录，或直接输入路径，点击**摄入**
2. 摄入完成后，下方"已索引文档"列表中会显示所有文档及其片段数
3. 在右侧聊天区域输入问题，按 Enter 发送
4. 系统会根据知识库内容生成回答，并附上参考来源

## 配置说明

### config.json 字段

| 路径 | 类型 | 默认值 | 说明 |
|------|------|--------|------|
| `llm.apiKey` | string | `"demo"` | DeepSeek API Key |
| `llm.baseUrl` | string | `"https://api.deepseek.com"` | API 基础地址 |
| `llm.modelName` | string | `"deepseek-v4-flash"` | 模型名称 |
| `llm.systemPrompt` | string | 中文助手提示词 | 系统提示词 |
| `llm.temperature` | number | `0.7` | 模型温度 (0~1) |
| `llm.maxTokens` | number | `4096` | 最大输出 Token |
| `llm.timeoutSeconds` | number | `120` | API 超时秒数 |
| `retrieval.maxResults` | number | `3` | 检索最大结果数 |
| `retrieval.minScore` | number | `0.5` | 检索最低相似度 |
| `document.dir` | string | `"./documents"` | 默认文档目录 |
| `document.chunkSize` | number | `300` | 分块大小（字符） |
| `document.chunkOverlap` | number | `0` | 分块重叠大小 |
| `document.supportedExtensions` | array/string | 13种格式 | 支持的文件扩展名 |
| `document.chunking.mode` | string | `"auto"` | 切分模式：`auto`/`structure`/`semantic`/`recursive` |
| `document.chunking.semanticThreshold` | number | `0.6` | 语义断点相似度阈值 (0~1) |
| `document.chunking.enableAgentRefiner` | boolean | `false` | 是否启用 LLM 精炼切分 |
| `document.chunking.maxChunkSize` | number | `2000` | 切分上限（字符），超限回退 |
| `queryEnhancement.enabled` | boolean | `true` | 是否启用查询增强 |
| `queryEnhancement.defaultMode` | string | `"auto"` | 默认增强模式：`auto`/`rewrite`/`hyde`/`both`/`none` |
| `queryEnhancement.rrfK` | number | `60` | RRF 融合参数 k |
| `queryEnhancement.hydeMaxTokens` | number | `200` | HyDE 生成文本最大 token 数 |
| `chat.memorySize` | number | `10` | 对话记忆窗口（消息数） |
| `server.port` | number | `8080` | HTTP 服务端口 |
| `milvus.host` | string | `"localhost"` | Milvus 服务地址 |
| `milvus.port` | number | `19530` | Milvus gRPC 端口 |
| `milvus.collectionName` | string | `"rag_knowledge_base"` | Collection 名称 |
| `milvus.dimension` | number | `512` | 向量维度 |
| `milvus.consistencyLevel` | string | `"STRONG"` | Milvus 一致性级别（STRONG 保证写入后立即可查） |

### 环境变量

| 环境变量 | 对应配置 |
|----------|----------|
| `RAG_LLM_API_KEY` | `llm.apiKey` |
| `RAG_LLM_BASE_URL` | `llm.baseUrl` |
| `RAG_LLM_MODEL_NAME` | `llm.modelName` |
| `RAG_LLM_SYSTEM_PROMPT` | `llm.systemPrompt` |
| `RAG_LLM_TEMPERATURE` | `llm.temperature` |
| `RAG_LLM_MAX_TOKENS` | `llm.maxTokens` |
| `RAG_LLM_TIMEOUT` | `llm.timeoutSeconds` |
| `RAG_RETRIEVAL_MAX_RESULTS` | `retrieval.maxResults` |
| `RAG_RETRIEVAL_MIN_SCORE` | `retrieval.minScore` |
| `RAG_CHUNK_SIZE` | `document.chunkSize` |
| `RAG_CHUNK_OVERLAP` | `document.chunkOverlap` |
| `RAG_CHAT_MEMORY_SIZE` | `chat.memorySize` |
| `RAG_SERVER_PORT` | `server.port` |
| `RAG_DOCUMENT_DIR` | `document.dir` |
| `RAG_MILVUS_HOST` | `milvus.host` |
| `RAG_MILVUS_PORT` | `milvus.port` |
| `RAG_MILVUS_COLLECTION` | `milvus.collectionName` |
| `RAG_MILVUS_DIMENSION` | `milvus.dimension` |
| `RAG_SUPPORTED_EXTENSIONS` | `document.supportedExtensions`（逗号分隔） |
| `RAG_CHUNKING_MODE` | `document.chunking.mode` |
| `RAG_CHUNKING_SEMANTIC_THRESHOLD` | `document.chunking.semanticThreshold` |
| `RAG_CHUNKING_AGENT_REFINER` | `document.chunking.enableAgentRefiner` |
| `RAG_CHUNKING_MAX_SIZE` | `document.chunking.maxChunkSize` |
| `RAG_QUERY_ENHANCEMENT_ENABLED` | `queryEnhancement.enabled` |
| `RAG_QUERY_ENHANCEMENT_MODE` | `queryEnhancement.defaultMode` |
| `RAG_QUERY_ENHANCEMENT_RRF_K` | `queryEnhancement.rrfK` |
| `RAG_QUERY_ENHANCEMENT_HYDE_MAX_TOKENS` | `queryEnhancement.hydeMaxTokens` |

## API 文档

### `GET /api/health`

健康检查。

**响应：**
```json
{"status": "ok"}
```

### `POST /api/chat`

发送对话消息。

**请求：**
```json
{
  "query": "这份文档的主要内容是什么？",
  "enhancement": "auto"
}
```
`enhancement` 可选，默认 `null`（由 `queryEnhancement.defaultMode` 决定），可选值：`auto`/`rewrite`/`hyde`/`both`/`none`。

**响应：**
```json
{
  "answer": "这份文档主要介绍了...",
  "sources": [
    {
      "fileName": "report.pdf",
      "text": "文档中的相关片段文本...",
      "score": 0.95
    }
  ]
}
```

### `POST /api/ingest`

摄入指定目录的文档。

**请求：**
```json
{"directory": "/path/to/documents"}
```

**响应：**
```json
{
  "success": true,
  "filesProcessed": 3,
  "segmentsCreated": 45,
  "message": "Successfully processed 3 files, created 45 segments."
}
```

### `GET /api/documents`

获取已索引的文档列表。

**响应：**
```json
[
  {
    "fileName": "report.pdf",
    "segmentCount": 15,
    "directory": "/path/to/documents",
    "fileType": "PDF"
  }
]
```

### `POST /api/browse`

浏览文件系统目录。

**请求：**
```json
{"path": "C:/Users"}
```
path 为空时返回根目录（Windows 返回驱动器列表）。

**响应：**
```json
{
  "currentPath": "C:/Users",
  "parentPath": "C:/",
  "directories": ["C:/Users/Admin", "C:/Users/Public", "C:/Users/Default"]
}
```

## 项目结构

```
MyAIDemo2/
├── config.example.json              # 配置文件模板
├── pom.xml                          # Maven 构建配置
├── README.md
└── src/main/
    ├── java/
    │   ├── shared/
    │   │   ├── Assistant.java       # AI 服务接口
    │   │   └── Utils.java           # 工具类
    │   └── me/maxt/rag/
    │       ├── Easy_RAG_Example2.java
    │       ├── Naive_RAG_Example.java
    │       └── web/
    │           ├── App.java            # 应用入口（薄胶水层）
│           ├── WebApplication.java  # 启动组装工厂
    │           ├── config/
    │           │   ├── AppConfig.java          # 配置实现
│           │   ├── LlmConfig.java           # LLM 配置接口
│           │   ├── RetrievalConfig.java     # 检索配置接口
│           │   ├── DocumentConfig.java         # 文档配置接口
│           │   ├── QueryEnhancementConfig.java # 查询增强配置接口
│           │   └── ServerConfig.java           # 服务器配置接口
    │           ├── controller/
    │           │   ├── ChatController.java     # 对话 API
    │           │   └── DocumentController.java # 文档管理 API
    │           └── service/
    │               ├── DocumentService.java       # 文档摄入/浏览/列表服务
    │               ├── EmbeddingStoreManager.java # 向量存储管理
    │               ├── RAGService.java            # RAG 核心服务
    │               ├── vector/
    │               │   ├── ContextualEnricher.java       # 上下文增强器
    │               │   ├── QueryEnhancer.java            # 查询增强接口
    │               │   ├── QueryRewriter.java            # LLM 查询改写
    │               │   ├── HyDEGenerator.java            # 假设文档生成
    │               │   └── QueryEnhancementRouter.java   # 增强路由器 + RRF 融合
    │               └── chunking/
    │                   ├── ChunkingPipeline.java   # 切分管线编排
    │                   ├── DocStructure.java等     # 数据结构
    │                   ├── analyzer/
    │                   │   └── StructureAnalyzer.java  # flexmark 结构分析
    │                   ├── classifier/
    │                   │   └── SplitClassifier.java    # 策略选择器
    │                   ├── converter/
    │                   │   └── MarkdownConverter.java  # Pandoc+Tika 转换
    │                   ├── splitter/
    │                   │   ├── SplitStrategy.java      # 策略接口
    │                   │   ├── StructureSplitter.java  # 结构切分
    │                   │   ├── SemanticSplitter.java   # 语义切分
    │                   │   └── AgentRefiner.java       # LLM 精炼
    │                   └── evaluator/
    │                       └── ChunkEvaluator.java     # 质量评估
    └── test/
        └── java/me/maxt/rag/web/
            ├── config/
            │   └── AppConfigTest.java                 # 配置测试
            ├── service/
            │   ├── EmbeddingStoreManagerTest.java      # 存储管理测试
            │   ├── DocumentServiceTest.java            # 文档服务测试
            │   ├── RAGServiceTest.java                 # RAG 服务测试
            │   ├── vector/
            │   │   ├── ContextualEnricherTest.java       # 上下文增强器测试
            │   │   ├── QueryRewriterTest.java           # 查询改写测试
            │   │   ├── HyDEGeneratorTest.java           # 假设文档生成测试
            │   │   └── QueryEnhancementRouterTest.java  # 增强路由器测试
            │   └── chunking/
            │       ├── ChunkingPipelineTest.java       # 管线测试
            │       ├── analyzer/
            │       │   └── StructureAnalyzerTest.java  # 结构分析测试
            │       ├── classifier/
            │       │   └── SplitClassifierTest.java    # 分类器测试
            │       ├── converter/
            │       │   └── MarkdownConverterTest.java  # 转换器测试
            │       └── splitter/
            │           ├── StructureSplitterTest.java  # 结构切分测试
            │           ├── SemanticSplitterTest.java   # 语义切分测试
            │           └── AgentRefinerTest.java       # 精炼器测试
    └── resources/
        └── webapp/
            ├── index.html           # 前端页面
            ├── style.css            # 样式表
            └── app.js               # 前端逻辑
```

## 注意事项

1. **OCR 支持**：PNG/JPG 图像文件的文字提取依赖 Tesseract OCR。如果没有安装 Tesseract，图像文件会被静默跳过（不会报错）。Windows 上可通过 [UB-Mannheim/tesseract](https://github.com/UB-Mannheim/tesseract/wiki) 安装。
2. **嵌入模型**：首次启动时，BgeSmallZhV15（中文优化）ONNX 模型会自动下载到本地缓存（约 100MB）。向量维度 512，与旧版 EN 模型（384 维）不兼容，切换后旧向量数据会自动清空。
3. **Milvus 持久化**：Milvus 向量数据通过 Docker volume 持久化，无需手动管理。
4. **API Key**：默认 API Key 为 `"demo"`，生产环境请通过环境变量 `RAG_LLM_API_KEY` 配置真实 Key。
5. **端口冲突**：默认端口 8080，可通过 `config.json` 或 `RAG_SERVER_PORT` 环境变量修改。
6. **Pandoc 支持**：安装 Pandoc 后，PDF/DOCX 文档会自动转为 Markdown 再切分，保留标题、代码块等结构信息。未安装时自动降级到 Tika 纯文本提取。Windows 上可通过 `winget install Pandoc.Pandoc` 或 [pandoc.org](https://pandoc.org/installing.html) 安装。
