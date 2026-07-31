# Milvus 向量数据库集成 — 实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 用 MilvusEmbeddingStore 替换 InMemoryEmbeddingStore + JSON 文件持久化

**Architecture:** EmbeddingStoreManager 构造函数改为接收 `EmbeddingStore<TextSegment>` 接口，生产注入 `MilvusEmbeddingStore`，测试注入 `InMemoryEmbeddingStore`。删除 StoredEntry / JSON 持久化代码。

**Tech Stack:** LangChain4j 1.12.1, langchain4j-milvus, Docker Milvus standalone, Testcontainers, BgeSmallZhV15 (512 维)

## Global Constraints

- JDK 17+, Maven 3.6+
- 嵌入模型 BgeSmallZhV15（512 维）
- Milvus Docker standalone（localhost:19530）
- 完全替换 InMemoryEmbeddingStore，不保留旧代码
- 单元测试不依赖 Docker，集成测试用 Testcontainers

---

### Task 1: 添加依赖和 Docker Compose

**Files:**
- Modify: `pom.xml`
- Create: `docker-compose.yml`

**Interfaces:**
- Produces: `langchain4j-milvus` 依赖可用；`docker compose up -d` 启动 Milvus

- [ ] **Step 1: 在 pom.xml 添加 langchain4j-milvus 和 testcontainers 依赖**

在 `pom.xml` 的 `<dependencies>` 中，`langchain4j-embedding-store-filter-parser-sql` 之后添加：

```xml
    <dependency>
      <groupId>dev.langchain4j</groupId>
      <artifactId>langchain4j-milvus</artifactId>
      <version>1.12.1</version>
    </dependency>

    <dependency>
      <groupId>org.testcontainers</groupId>
      <artifactId>milvus</artifactId>
      <version>1.20.0</version>
      <scope>test</scope>
    </dependency>
    <dependency>
      <groupId>org.testcontainers</groupId>
      <artifactId>junit-jupiter</artifactId>
      <version>1.20.0</version>
      <scope>test</scope>
    </dependency>
```

- [ ] **Step 2: 创建 docker-compose.yml**

```yaml
version: '3.8'

services:
  etcd:
    image: quay.io/coreos/etcd:v3.5.5
    environment:
      - ETCD_AUTO_COMPACTION_MODE=revision
      - ETCD_AUTO_COMPACTION_RETENTION=1000
      - ETCD_QUOTA_BACKEND_BYTES=4294967296
      - ETCD_SNAPSHOT_COUNT=50000
    volumes:
      - etcd_data:/etcd
    command: etcd -advertise-client-urls=http://127.0.0.1:2379 -listen-client-urls http://0.0.0.0:2379 --data-dir /etcd
    healthcheck:
      test: ["CMD", "etcdctl", "endpoint", "health"]
      interval: 30s
      timeout: 20s
      retries: 3

  minio:
    image: minio/minio:RELEASE.2023-03-20T20-16-18Z
    environment:
      MINIO_ACCESS_KEY: minioadmin
      MINIO_SECRET_KEY: minioadmin
    volumes:
      - minio_data:/minio_data
    command: minio server /minio_data --console-address ":9001"
    healthcheck:
      test: ["CMD", "curl", "-f", "http://localhost:9000/minio/health/live"]
      interval: 30s
      timeout: 20s
      retries: 3

  milvus:
    image: milvusdb/milvus:v2.4.0
    command: ["milvus", "run", "standalone"]
    security_opt:
      - seccomp:unconfined
    environment:
      ETCD_ENDPOINTS: etcd:2379
      MINIO_ADDRESS: minio:9000
      MINIO_ACCESS_KEY_ID: minioadmin
      MINIO_SECRET_ACCESS_KEY: minioadmin
    volumes:
      - milvus_data:/var/lib/milvus
    healthcheck:
      test: ["CMD", "curl", "-f", "http://localhost:9091/healthz"]
      interval: 30s
      start_period: 90s
      timeout: 20s
      retries: 3
    ports:
      - "19530:19530"
      - "9091:9091"
    depends_on:
      etcd:
        condition: service_healthy
      minio:
        condition: service_healthy

volumes:
  etcd_data:
  minio_data:
  milvus_data:
```

- [ ] **Step 3: 验证编译**

Run: `mvn compile`
Expected: BUILD SUCCESS，langchain4j-milvus 依赖解析成功

- [ ] **Step 4: 提交**

```bash
git add pom.xml docker-compose.yml
git commit -m "feat: 添加 langchain4j-milvus 依赖和 Docker Compose 配置"
```

---

### Task 2: 创建 MilvusConfig 配置接口

**Files:**
- Create: `src/main/java/me/maxt/rag/web/config/MilvusConfig.java`

**Interfaces:**
- Produces: `MilvusConfig` — `getMilvusHost()`, `getMilvusPort()`, `getMilvusCollectionName()`, `getMilvusDimension()`

- [ ] **Step 1: 创建 MilvusConfig 接口**

```java
package me.maxt.rag.web.config;

/**
 * Milvus 向量数据库配置接口。
 */
public interface MilvusConfig {

    /** @return Milvus 服务主机地址 */
    String getMilvusHost();

    /** @return Milvus gRPC 端口 */
    int getMilvusPort();

    /** @return Milvus collection 名称 */
    String getMilvusCollectionName();

    /** @return 向量维度（BgeSmallZhV15 = 512） */
    int getMilvusDimension();
}
```

- [ ] **Step 2: 编译验证**

Run: `mvn compile`
Expected: BUILD SUCCESS

- [ ] **Step 3: 提交**

```bash
git add src/main/java/me/maxt/rag/web/config/MilvusConfig.java
git commit -m "feat: 新增 MilvusConfig 配置接口"
```

---

### Task 3: AppConfig 实现 MilvusConfig

**Files:**
- Modify: `src/main/java/me/maxt/rag/web/config/AppConfig.java`

**Interfaces:**
- Consumes: `MilvusConfig`
- Produces: `AppConfig` 新增 milvus 配置字段和 getter

- [ ] **Step 1: AppConfig 声明实现 MilvusConfig**

修改类声明（第 28 行）：
```java
public class AppConfig implements LlmConfig, RetrievalConfig, DocumentConfig, ServerConfig, QueryEnhancementConfig, MilvusConfig {
```

- [ ] **Step 2: 添加 milvus 配置字段**

在 `defaultEnhancementMode` 字段之后添加：

```java
    // ========== Milvus 参数 ==========

    /** Milvus 服务主机地址，可通过环境变量 RAG_MILVUS_HOST 覆盖 */
    private String milvusHost;

    /** Milvus gRPC 端口，可通过环境变量 RAG_MILVUS_PORT 覆盖 */
    private int milvusPort;

    /** Milvus collection 名称，可通过环境变量 RAG_MILVUS_COLLECTION 覆盖 */
    private String milvusCollectionName;

    /** 向量维度，可通过环境变量 RAG_MILVUS_DIMENSION 覆盖 */
    private int milvusDimension;
```

- [ ] **Step 3: 在构造函数中设置默认值**

在 `this.hydeMaxTokens = 200;` 之后添加：

```java
        this.milvusHost = "localhost";
        this.milvusPort = 19530;
        this.milvusCollectionName = "rag_knowledge_base";
        this.milvusDimension = 512;
```

- [ ] **Step 4: 在 applyFileConfig 中解析 milvus 配置**

在 `applyFileConfig` 方法的 `if (store != null)` 块之后添加：

```java
        Map<String, Object> milvus = (Map<String, Object>) fileConfig.get("milvus");
        if (milvus != null) {
            config.milvusHost = getString(milvus, "host", config.milvusHost);
            config.milvusPort = getInt(milvus, "port", config.milvusPort);
            config.milvusCollectionName = getString(milvus, "collectionName", config.milvusCollectionName);
            config.milvusDimension = getInt(milvus, "dimension", config.milvusDimension);
        }
```

- [ ] **Step 5: 在 applyEnvOverrides 中添加环境变量支持**

在 `config.hydeMaxTokens = envInt(...)` 之后添加：

```java
        config.milvusHost = env("RAG_MILVUS_HOST", config.milvusHost);
        config.milvusPort = envInt("RAG_MILVUS_PORT", config.milvusPort);
        config.milvusCollectionName = env("RAG_MILVUS_COLLECTION", config.milvusCollectionName);
        config.milvusDimension = envInt("RAG_MILVUS_DIMENSION", config.milvusDimension);
```

- [ ] **Step 6: 添加 getter 方法**

在 `getHydeMaxTokens()` 之后添加：

```java
    /** @return Milvus 服务主机地址 */
    public String getMilvusHost() { return milvusHost; }
    /** @return Milvus gRPC 端口 */
    public int getMilvusPort() { return milvusPort; }
    /** @return Milvus collection 名称 */
    public String getMilvusCollectionName() { return milvusCollectionName; }
    /** @return 向量维度 */
    public int getMilvusDimension() { return milvusDimension; }
```

- [ ] **Step 7: 更新 AppConfigTest 验证新配置**

Run: `mvn test -pl . -Dtest=AppConfigTest`
Expected: 所有测试通过

如测试断言了 `getStoreFilePath()` 需要注意——milvus 模式下 store 配置不再使用，但先保留向后兼容。

- [ ] **Step 8: 提交**

```bash
git add src/main/java/me/maxt/rag/web/config/AppConfig.java
git commit -m "feat: AppConfig 实现 MilvusConfig，支持 milvus 配置节和环境变量"
```

---

### Task 4: 重构 EmbeddingStoreManager

**Files:**
- Modify: `src/main/java/me/maxt/rag/web/service/EmbeddingStoreManager.java`

**Interfaces:**
- Consumes: `EmbeddingStore<TextSegment>`（由调用方注入）
- Produces: `EmbeddingStoreManager(EmbeddingStore<TextSegment>)`, `search(request, fileName, fileType)`, 删除 `StoredEntry`/`persist()`/`loadFromFile()`

- [ ] **Step 1: 重写 EmbeddingStoreManager**

完整替换文件内容：

```java
package me.maxt.rag.web.service;

import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.store.embedding.EmbeddingSearchRequest;
import dev.langchain4j.store.embedding.EmbeddingSearchResult;
import dev.langchain4j.store.embedding.EmbeddingStore;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 向量存储管理器，封装 Milvus（或其他 EmbeddingStore 实现）。
 *
 * <p>构造函数接收 {@link EmbeddingStore<TextSegment>} 接口，
 * 生产环境注入 MilvusEmbeddingStore，测试注入 InMemoryEmbeddingStore。</p>
 */
public class EmbeddingStoreManager {

    private final EmbeddingStore<TextSegment> embeddingStore;

    public EmbeddingStoreManager(EmbeddingStore<TextSegment> store) {
        this.embeddingStore = store;
    }

    public String add(Embedding embedding, TextSegment textSegment) {
        String id = UUID.randomUUID().toString();
        embeddingStore.add(id, embedding, textSegment);
        return id;
    }

    public List<String> addAll(List<Embedding> embeddings, List<TextSegment> textSegments) {
        List<String> ids = new ArrayList<>();
        for (int i = 0; i < embeddings.size(); i++) {
            ids.add(UUID.randomUUID().toString());
        }
        embeddingStore.addAll(ids, embeddings, textSegments);
        return ids;
    }

    public EmbeddingSearchResult<TextSegment> search(EmbeddingSearchRequest request) {
        return embeddingStore.search(request);
    }

    public dev.langchain4j.rag.content.retriever.ContentRetriever createContentRetriever(
            dev.langchain4j.model.embedding.EmbeddingModel embeddingModel,
            int maxResults,
            double minScore) {
        return dev.langchain4j.rag.content.retriever.EmbeddingStoreContentRetriever.builder()
                .embeddingStore(embeddingStore)
                .embeddingModel(embeddingModel)
                .maxResults(maxResults)
                .minScore(minScore)
                .build();
    }
}
```

删除的内容：
- `ObjectMapper MAPPER`, `Logger log` 常量
- `storePath` 字段, `entries` ConcurrentHashMap
- `persist()`, `loadFromFile()` 方法
- `StoredEntry` 内部类
- `listDocuments()`, `getEntryCount()`, `removeAll()`, `clear()` 方法
- `synchronized` 关键字（持久化由 Milvus 负责）
- 所有 `java.io`, `java.nio.file`, `com.fasterxml.jackson`, `ConcurrentHashMap` import

- [ ] **Step 2: 编译验证**

Run: `mvn compile`
Expected: 编译失败——因为 WebApplication 和 DocumentService 可能引用了 `listDocuments()` / `getEntryCount()` / `StoredEntry`。这些会在后续 task 中修复。

- [ ] **Step 3: 提交**

```bash
git add src/main/java/me/maxt/rag/web/service/EmbeddingStoreManager.java
git commit -m "重构: EmbeddingStoreManager 改为注入 EmbeddingStore<TextSegment> 接口"
```

---

### Task 5: 更新 WebApplication 装配逻辑

**Files:**
- Modify: `src/main/java/me/maxt/rag/web/WebApplication.java`

**Interfaces:**
- Consumes: `MilvusEmbeddingStore`, `MilvusConfig`
- Produces: WebApplication 创建 MilvusEmbeddingStore 并注入 EmbeddingStoreManager

- [ ] **Step 1: 添加 import**

在 import 区域添加：

```java
import dev.langchain4j.store.embedding.milvus.MilvusEmbeddingStore;
```

- [ ] **Step 2: 替换 EmbeddingStoreManager 创建逻辑**

删除第 70-96 行（维度不兼容检测、`new EmbeddingStoreManager(config.getStoreFilePath())`）。

替换为：

```java
        // Milvus 向量存储
        MilvusEmbeddingStore milvusStore = MilvusEmbeddingStore.builder()
                .host(config.getMilvusHost())
                .port(config.getMilvusPort())
                .collectionName(config.getMilvusCollectionName())
                .dimension(config.getMilvusDimension())
                .build();

        this.storeManager = new EmbeddingStoreManager(milvusStore);
```

- [ ] **Step 3: 移除 autoIngestIfNeeded 对 getEntryCount 的依赖**

`autoIngestIfNeeded()` 第 164 行引用了 `storeManager.getEntryCount()`。改为总是尝试摄入（Milvus collection 可能为空时自动摄入逻辑不变，但我们暂时简化）：

```java
    public void autoIngestIfNeeded() {
        File defaultDocDir = new File(config.getDocumentDir());
        if (defaultDocDir.exists() && defaultDocDir.isDirectory()) {
            documentService.ingestDirectory(config.getDocumentDir());
        }
    }
```

- [ ] **Step 4: 删除不再使用的 import**

删除以下不再需要的 import：
- `java.io.IOException`
- `java.nio.file.Files`
- `java.nio.file.Path`
- `java.nio.file.Paths`

- [ ] **Step 5: 编译验证**

Run: `mvn compile`
Expected: BUILD SUCCESS。如果 DocumentService 或 ChatController 引用了 `storeManager.getEntryCount()` / `listDocuments()` 等已删除方法，需要一并修复。

- [ ] **Step 6: 运行全部测试确认编译通过**

Run: `mvn test`
Expected: 编译通过（可能有测试失败，后续 task 修复）

- [ ] **Step 7: 提交**

```bash
git add src/main/java/me/maxt/rag/web/WebApplication.java
git commit -m "feat: WebApplication 创建 MilvusEmbeddingStore 并注入 EmbeddingStoreManager"
```

---

### Task 6: 更新 EmbeddingStoreManagerTest

**Files:**
- Modify: `src/test/java/me/maxt/rag/web/service/EmbeddingStoreManagerTest.java`

**Interfaces:**
- Consumes: `EmbeddingStoreManager(EmbeddingStore<TextSegment>)`, `InMemoryEmbeddingStore`

- [ ] **Step 1: 重写测试——注入 InMemoryEmbeddingStore**

完整替换文件：

```java
package me.maxt.rag.web.service;

import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.store.embedding.EmbeddingSearchRequest;
import dev.langchain4j.store.embedding.EmbeddingSearchResult;
import dev.langchain4j.store.embedding.inmemory.InMemoryEmbeddingStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class EmbeddingStoreManagerTest {

    private InMemoryEmbeddingStore<TextSegment> memoryStore;
    private EmbeddingStoreManager mgr;

    @BeforeEach
    void setUp() {
        memoryStore = new InMemoryEmbeddingStore<>();
        mgr = new EmbeddingStoreManager(memoryStore);
    }

    @Test
    void shouldAddAndSearch() {
        float[] vector = {0.1f, 0.2f, 0.3f};
        TextSegment segment = TextSegment.from("hello world");
        String id = mgr.add(Embedding.from(vector), segment);

        assertThat(id).isNotEmpty();

        EmbeddingSearchRequest request = EmbeddingSearchRequest.builder()
                .queryEmbedding(Embedding.from(new float[]{0.1f, 0.2f, 0.3f}))
                .maxResults(5)
                .minScore(0.5)
                .build();
        EmbeddingSearchResult<TextSegment> result = mgr.search(request);
        assertThat(result.matches()).hasSize(1);
        assertThat(result.matches().get(0).embedded().text()).isEqualTo("hello world");
    }

    @Test
    void shouldAddAllBatch() {
        List<Embedding> embeddings = List.of(
                Embedding.from(new float[]{1.0f, 0.0f}),
                Embedding.from(new float[]{0.0f, 1.0f})
        );
        List<TextSegment> segments = List.of(
                TextSegment.from("first"),
                TextSegment.from("second")
        );

        List<String> ids = mgr.addAll(embeddings, segments);
        assertThat(ids).hasSize(2);
    }

    @Test
    void shouldCreateContentRetriever() {
        dev.langchain4j.model.embedding.EmbeddingModel model =
                org.mockito.Mockito.mock(dev.langchain4j.model.embedding.EmbeddingModel.class);
        dev.langchain4j.rag.content.retriever.ContentRetriever retriever =
                mgr.createContentRetriever(model, 3, 0.5);
        assertThat(retriever).isNotNull();
    }

    @Test
    void shouldFilterByMinScore() {
        mgr.add(Embedding.from(new float[]{1.0f, 0.0f}), TextSegment.from("target"));

        EmbeddingSearchRequest request = EmbeddingSearchRequest.builder()
                .queryEmbedding(Embedding.from(new float[]{0.0f, 1.0f}))
                .maxResults(5)
                .minScore(0.9)
                .build();
        EmbeddingSearchResult<TextSegment> result = mgr.search(request);
        assertThat(result.matches()).isEmpty();
    }
}
```

- [ ] **Step 2: 运行测试**

Run: `mvn test -pl . -Dtest=EmbeddingStoreManagerTest`
Expected: 4 tests PASS

- [ ] **Step 3: 提交**

```bash
git add src/test/java/me/maxt/rag/web/service/EmbeddingStoreManagerTest.java
git commit -m "test: EmbeddingStoreManagerTest 改为注入 InMemoryEmbeddingStore"
```

---

### Task 7: 修复其他测试和编译错误

**Files:**
- Modify: `src/test/java/me/maxt/rag/web/service/RAGServiceTest.java`
- Modify: `src/test/java/me/maxt/rag/web/service/DocumentServiceTest.java`
- Modify: `src/main/java/me/maxt/rag/web/controller/DocumentController.java`（如有引用）
- Modify: `src/main/java/me/maxt/rag/web/service/DocumentService.java`（如有引用）

**Interfaces:**
- 修复对 `EmbeddingStoreManager.removeAll()` / `getEntryCount()` / `listDocuments()` / `clear()` / `StoredEntry` 的引用

- [ ] **Step 1: 运行全量测试找出编译/运行失败**

Run: `mvn test`
Expected: 部分测试因引用已删除方法而失败

- [ ] **Step 2: 逐个修复编译错误**

需要检查的文件：
- `RAGServiceTest.java` — 可能引用 `EmbeddingStoreManager(String)` 构造函数，需更新为 `new EmbeddingStoreManager(new InMemoryEmbeddingStore<>())`
- `DocumentServiceTest.java` — 同上
- `DocumentController.java` — 可能引用 `storeManager.listDocuments()` 或 `getEntryCount()`
- `DocumentService.java` — 可能引用 `storeManager.getEntryCount()`

DocumentController 中如果引用了 `storeManager.listDocuments()` 用于 `/api/documents`，需改为直接从 Milvus 查询。先改用空逻辑或跳过，后续可增强。

- [ ] **Step 3: 运行全量测试**

Run: `mvn test`
Expected: 78 tests PASS

- [ ] **Step 4: 提交**

```bash
git add -A
git commit -m "fix: 修复 EmbeddingStoreManager 重构后的编译错误和测试失败"
```

---

### Task 8: 集成测试 — EmbeddingStoreManagerMilvusIT

**Files:**
- Create: `src/test/java/me/maxt/rag/web/service/EmbeddingStoreManagerMilvusIT.java`

**Interfaces:**
- Consumes: `MilvusEmbeddingStore`, Testcontainers `MilvusContainer`

- [ ] **Step 1: 创建集成测试**

```java
package me.maxt.rag.web.service;

import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.store.embedding.EmbeddingSearchRequest;
import dev.langchain4j.store.embedding.EmbeddingSearchResult;
import dev.langchain4j.store.embedding.milvus.MilvusEmbeddingStore;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.milvus.MilvusContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
class EmbeddingStoreManagerMilvusIT {

    @Container
    static MilvusContainer milvus = new MilvusContainer("milvusdb/milvus:v2.4.0");

    static EmbeddingStoreManager mgr;
    static final int DIMENSION = 512;

    @BeforeAll
    static void setUp() {
        MilvusEmbeddingStore store = MilvusEmbeddingStore.builder()
                .host(milvus.getHost())
                .port(milvus.getMappedPort(19530))
                .collectionName("test_collection")
                .dimension(DIMENSION)
                .build();
        mgr = new EmbeddingStoreManager(store);
    }

    @AfterAll
    static void tearDown() {
        // Milvus container auto-stops via @Container
    }

    @Test
    void shouldAddAndSearchInMilvus() {
        float[] vector = new float[DIMENSION];
        vector[0] = 0.1f;
        vector[1] = 0.2f;

        TextSegment segment = TextSegment.from("integration test content");
        segment.metadata().put("file_name", "test.pdf");

        String id = mgr.add(Embedding.from(vector), segment);
        assertThat(id).isNotEmpty();

        float[] queryVector = new float[DIMENSION];
        queryVector[0] = 0.1f;
        queryVector[1] = 0.2f;

        EmbeddingSearchRequest request = EmbeddingSearchRequest.builder()
                .queryEmbedding(Embedding.from(queryVector))
                .maxResults(5)
                .minScore(0.5)
                .build();
        EmbeddingSearchResult<TextSegment> result = mgr.search(request);
        assertThat(result.matches()).hasSize(1);
        assertThat(result.matches().get(0).embedded().text())
                .isEqualTo("integration test content");
    }
}
```

- [ ] **Step 2: 运行集成测试**

Run: `mvn test -pl . -Dtest=EmbeddingStoreManagerMilvusIT`
Note: 需要 Docker daemon 运行中
Expected: 1 test PASS

- [ ] **Step 3: 提交**

```bash
git add src/test/java/me/maxt/rag/web/service/EmbeddingStoreManagerMilvusIT.java
git commit -m "test: 新增 Milvus 集成测试"
```

---

### Task 9: 更新配置文件和文档

**Files:**
- Modify: `config.example.json`
- Modify: `README.md`

- [ ] **Step 1: 更新 config.example.json**

删除 `store` 节，新增 `milvus` 节：

```json
  "store": {
    "filePath": "./data/embedding-store.json"
  },
```

替换为：

```json
  "milvus": {
    "host": "localhost",
    "port": 19530,
    "collectionName": "rag_knowledge_base",
    "dimension": 512
  },
```

- [ ] **Step 2: 更新 README.md**

技术栈表中：
- `| 向量存储 | Milvus（Docker standalone） |` 替换 `| 向量存储 | 内存 + JSON 文件持久化 |`

配置说明表中：
- 删除 `store.filePath` 行
- 新增 milvus 配置表：

```
| `milvus.host` | string | `"localhost"` | Milvus 服务地址 |
| `milvus.port` | number | `19530` | Milvus gRPC 端口 |
| `milvus.collectionName` | string | `"rag_knowledge_base"` | Collection 名称 |
| `milvus.dimension` | number | `512` | 向量维度 |
```

环境变量表添加：
```
| `RAG_MILVUS_HOST` | `milvus.host` |
| `RAG_MILVUS_PORT` | `milvus.port` |
| `RAG_MILVUS_COLLECTION` | `milvus.collectionName` |
| `RAG_MILVUS_DIMENSION` | `milvus.dimension` |
```

删除 `RAG_STORE_PATH` 环境变量行。

快速开始中，新增前置步骤：
```
### 0. 启动 Milvus

docker compose up -d
```

注意事项中：
- 第 3 条改为 "Milvus 向量数据通过 Docker volume 持久化，无需手动管理"

- [ ] **Step 3: 提交**

```bash
git add config.example.json README.md
git commit -m "文档: 更新配置文件和 README 反映 Milvus 集成"
```

---

### Task 10: 全量测试和最终验证

- [ ] **Step 1: 运行全量单元测试**

Run: `mvn test`
Expected: 78+ tests PASS（不含 IT）

- [ ] **Step 2: 运行覆盖率报告**

Run: `mvn test jacoco:report`
Expected: Service 层覆盖率 >88%

- [ ] **Step 3: 打包验证**

Run: `mvn clean package -DskipTests`
Expected: BUILD SUCCESS

- [ ] **Step 4: 提交（如有未提交变更）**

```bash
git status
git add -A
git commit -m "chore: 全量测试通过，覆盖率验证"
```
