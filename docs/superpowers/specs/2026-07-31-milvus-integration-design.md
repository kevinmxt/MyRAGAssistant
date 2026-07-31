# 向量数据库集成（Milvus）— 设计文档

## 目标

将当前 `InMemoryEmbeddingStore` + JSON 文件持久化替换为 Milvus 向量数据库，利用 LangChain4j 的 `langchain4j-milvus` 模块集成。

## 需求摘要

| 决策 | 选择 |
|------|------|
| 向量数据库 | Milvus |
| 部署方式 | Docker 本地（standalone） |
| 存储策略 | 完全替换 `InMemoryEmbeddingStore` |
| 元数据过滤 | 基础过滤（fileName、fileType） |
| 集成方式 | LangChain4j `langchain4j-milvus` 模块 |
| 嵌入模型 | BgeSmallZhV15（512 维） |

## 架构变更

```
Before:
  EmbeddingStoreManager
    ├── InMemoryEmbeddingStore
    ├── ConcurrentHashMap entries
    └── JSON 文件持久化 (persist/loadFromFile)

After:
  EmbeddingStoreManager
    └── MilvusEmbeddingStore (LangChain4j)
           └── Docker Milvus (etcd + minio + milvus)
```

## 受影响文件

| 文件 | 变更类型 | 说明 |
|------|----------|------|
| `pom.xml` | 修改 | 新增 `langchain4j-milvus`；删除 `langchain4j-embeddings-bge-small-en-v15-q` |
| `docker-compose.yml` | 新增 | Milvus standalone + etcd + minio |
| `AppConfig.java` | 修改 | 新增 milvus 配置节解析 |
| `EmbeddingStoreManager.java` | 重构 | 替换内部实现，删除 JSON 持久化代码 |
| `WebApplication.java` | 修改 | 更新构造函数参数 |
| `EmbeddingStoreManagerTest.java` | 重构 | 注入 `EmbeddingStore<TextSegment>` 接口 |
| `EmbeddingStoreManagerMilvusIT.java` | 新增 | Testcontainers 集成测试 |

## 不动的文件

`RAGService`、`DocumentService`、`ChunkingPipeline`、Controller 层——它们通过 `EmbeddingStoreManager` 公共接口交互，内部变化透明。

## 删除的代码

- `StoredEntry` 内部类
- `entries` ConcurrentHashMap
- `persist()` / `loadFromFile()` 方法
- `storePath` 字段及相关文件 I/O
- `store.filePath` 配置字段

## 配置设计

`config.json` 新增 `milvus` 节：

```json
{
  "milvus": {
    "host": "localhost",
    "port": 19530,
    "collectionName": "rag_knowledge_base",
    "dimension": 512
  }
}
```

| 字段 | 默认值 | 环境变量 |
|------|--------|----------|
| `host` | `localhost` | `RAG_MILVUS_HOST` |
| `port` | `19530` | `RAG_MILVUS_PORT` |
| `collectionName` | `rag_knowledge_base` | `RAG_MILVUS_COLLECTION` |
| `dimension` | `512` | `RAG_MILVUS_DIMENSION` |

新增 `MilvusConfig` 配置接口，`AppConfig` 实现。

## EmbeddingStoreManager 重构

构造函数改为接收 `EmbeddingStore<TextSegment>` 接口，实现存储解耦：

```java
public class EmbeddingStoreManager {
    private final EmbeddingStore<TextSegment> embeddingStore;

    public EmbeddingStoreManager(EmbeddingStore<TextSegment> store) {
        this.embeddingStore = store;
    }

    public String add(Embedding embedding, TextSegment textSegment) { ... }
    public EmbeddingSearchResult<TextSegment> search(EmbeddingSearchRequest request) { ... }
    public ContentRetriever createContentRetriever(...) { ... }
}
```

生产环境：`WebApplication` 创建 `MilvusEmbeddingStore` 并注入。
测试环境：注入 `InMemoryEmbeddingStore`。

## 元数据过滤

`search()` 方法增加可选过滤参数：

```java
public EmbeddingSearchResult<TextSegment> search(
    EmbeddingSearchRequest request,
    String fileName,   // null = 不过滤
    String fileType    // null = 不过滤
)
```

内部通过 Milvus scalar filtering（expr 表达式）实现。

## Docker Compose

```yaml
services:
  etcd:
    image: quay.io/coreos/etcd:v3.5.5
  minio:
    image: minio/minio:latest
  milvus:
    image: milvusdb/milvus:v2.4.0
    ports: ["19530:19530", "9091:9091"]
```

启动：`docker compose up -d`。数据通过 Docker volume 持久化。

## 测试策略

| 层级 | 策略 |
|------|------|
| 单元测试 | `EmbeddingStoreManager` 构造函数注入 `EmbeddingStore<TextSegment>`，测试时注入 `InMemoryEmbeddingStore` |
| 集成测试 | `EmbeddingStoreManagerMilvusIT` 用 Testcontainers 启动 Milvus，验证真实读写 |
| 其他 Service | 继续 mock `EmbeddingStoreManager`，不受影响 |

## pom.xml 变更

```xml
<!-- 新增 -->
<dependency>
  <groupId>dev.langchain4j</groupId>
  <artifactId>langchain4j-milvus</artifactId>
  <version>1.12.1</version>
</dependency>

<!-- 集成测试 -->
<dependency>
  <groupId>org.testcontainers</groupId>
  <artifactId>milvus</artifactId>
  <version>1.20.0</version>
  <scope>test</scope>
</dependency>
```
