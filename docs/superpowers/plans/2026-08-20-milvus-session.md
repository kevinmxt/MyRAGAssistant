# MilvusSession 向量库会话深化 — 实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 把散布在 WebApplication / MilvusChecker / EmbeddingStoreManager 的 Milvus 连接生命周期（探测、降级、重连、索引重建）收敛为一个深模块 `MilvusSession`，根治"重连后消费者持有过期客户端"的活 bug。

**Architecture:** `MilvusSession`（`service.vector` 包）拥有向量库会话的全部状态：当前活跃 store、原生 `MilvusClientV2`、DEGRADED↔CONNECTED 原子切换。构造零 I/O、出生即 DEGRADED（内存降级）；`connect()` 是唯一建连路径（探针快速失败 → 连接 → 换 store + 重建索引一步完成）。消费者每次拉取 `nativeClient()`，不再缓存。`MilvusConnector` 是内部测试接缝（生产适配器 + 测试假件）。

**Tech Stack:** Java 21、JUnit 5、Mockito 5.12、AssertJ、Testcontainers（仅 IT）、langchain4j MilvusEmbeddingStore、Milvus ClientV2。

**Spec:** 本计划自带设计决议（2026-08-20 架构评审候选 1 的 grilling 产出）。术语定义见 `CONTEXT.md`（向量库会话），推迟项见 `docs/adr/0001-sparse-recall-no-late-registration.md`。

## Global Constraints

- 不引入状态监听器（零生产使用者，ADR-0001 记录了将来加回时的形状 `Consumer<State>`）
- sparse 召回迟到注册不实现（ADR-0001 推迟）：注册表仍只在组装时静态注册
- `EmbeddingStoreManager` 最终只留一个 `Supplier<EmbeddingStore>` 构造器（中间任务暂留旧构造器保编译，Task 5 删除）
- 重连端点成功后自行触发 `environmentChecker.checkAll()`（端点编排，与现状行为一致）
- `connect()` 失败一律进入 DEGRADED（换回内存 store、清空索引、记录 lastError）
- 术语：模块/接口/接缝/适配器/局部性/杠杆，见 `CONTEXT.md` 与 `C:\Users\KM\.claude\skills\codebase-design`
- commit message 用中文，格式如 `重构: xxx`
- 每个 Task 结束时 `mvn test -q` 全绿（evaluation profile 与 IT 除外：`mvn test -Dtest=MilvusSessionIT` 显式跑）

## 文件结构总览

| 文件 | 动作 | 职责 |
|------|------|------|
| `src/main/java/me/maxt/rag/web/service/vector/MilvusConnector.java` | 新建 | 内部接缝：探针/版本/建连三方法 + `Connection` record |
| `src/main/java/me/maxt/rag/web/service/vector/RealMilvusConnector.java` | 新建 | 生产适配器（代码迁自 WebApplication 与 MilvusChecker） |
| `src/main/java/me/maxt/rag/web/service/vector/MilvusSession.java` | 新建 | 深模块：会话状态机 + 原子切换 + 索引重建解析 |
| `src/main/java/me/maxt/rag/web/service/EmbeddingStoreManager.java` | 修改 | 构造器换 Supplier、删 swapStore/rebuildIndexFromMilvus、加 replaceAllIndex |
| `src/main/java/me/maxt/rag/web/service/environment/MilvusChecker.java` | 修改 | 退化为 session.probe() 的适配器 |
| `src/main/java/me/maxt/rag/web/service/KnowledgeGraphService.java` | 修改 | `MilvusClientV2` 字段 → `Supplier<MilvusClientV2>`，每次拉取 |
| `src/main/java/me/maxt/rag/web/service/vector/recall/SparseRecallStrategy.java` | 修改 | 同上 |
| `src/main/java/me/maxt/rag/web/WebApplication.java` | 修改 | 删 buildMilvusStore/buildMilvusClient/reconnectMilvus/milvusClientV2，接 session |
| `src/test/java/me/maxt/rag/web/service/vector/RealMilvusConnectorTest.java` | 新建 | reachable 的 ServerSocket 单测 |
| `src/test/java/me/maxt/rag/web/service/vector/MilvusSessionTest.java` | 新建 | 状态机单测（FakeConnector） |
| `src/test/java/me/maxt/rag/web/service/environment/MilvusCheckerTest.java` | 新建 | ProbeResult→CheckResult 映射 |
| `src/test/java/me/maxt/rag/web/service/vector/MilvusSessionIT.java` | 新建 | Testcontainers 真连接集成测试 |
| 其余 5 个测试文件 | 修改 | 构造器调用点换 lambda / 删过时测试 |

---

### Task 1: MilvusConnector 接口 + RealMilvusConnector 生产适配器

**Files:**
- Create: `src/main/java/me/maxt/rag/web/service/vector/MilvusConnector.java`
- Create: `src/main/java/me/maxt/rag/web/service/vector/RealMilvusConnector.java`
- Test: `src/test/java/me/maxt/rag/web/service/vector/RealMilvusConnectorTest.java`

**Interfaces:**
- Consumes: 无（首个任务）
- Produces: `MilvusConnector`（`boolean reachable(String host, int port, int timeoutMs)` / `String serverVersion(String host, int port) throws Exception` / `Connection connect(String host, int port, String collectionName, int dimension) throws Exception`，嵌套 `record Connection(EmbeddingStore<TextSegment> store, MilvusClientV2 client)`）；`RealMilvusConnector implements MilvusConnector`。Task 3/4/6 依赖。

- [ ] **Step 1: 写失败测试**

```java
package me.maxt.rag.web.service.vector;

import org.junit.jupiter.api.Test;

import java.net.ServerSocket;

import static org.assertj.core.api.Assertions.assertThat;

class RealMilvusConnectorTest {

    @Test
    void shouldProbeReachableWhenPortOpen() throws Exception {
        try (ServerSocket server = new ServerSocket(0)) {
            RealMilvusConnector connector = new RealMilvusConnector();
            assertThat(connector.reachable("localhost", server.getLocalPort(), 2000)).isTrue();
        }
    }

    @Test
    void shouldProbeUnreachableWhenPortClosed() throws Exception {
        int closedPort;
        try (ServerSocket server = new ServerSocket(0)) {
            closedPort = server.getLocalPort();
        }
        RealMilvusConnector connector = new RealMilvusConnector();
        assertThat(connector.reachable("localhost", closedPort, 2000)).isFalse();
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

Run: `mvn test -q -Dtest=RealMilvusConnectorTest`
Expected: 编译失败，`RealMilvusConnector` 不存在

- [ ] **Step 3: 写实现**

`MilvusConnector.java`：

```java
package me.maxt.rag.web.service.vector;

import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.store.embedding.EmbeddingStore;
import io.milvus.v2.client.MilvusClientV2;

/**
 * Milvus 连接操作的内部接缝：生产适配器连真实 Milvus，
 * 测试注入假件控制可达性与建连成败。
 * 接口由 MilvusSession 持有，不对其他消费者开放。
 */
public interface MilvusConnector {

    /** TCP 探针：快速判断可达性，不建立会话、不持有状态 */
    boolean reachable(String host, int port, int timeoutMs);

    /** 查询服务端版本（best-effort，失败抛异常，不影响可达性判断） */
    String serverVersion(String host, int port) throws Exception;

    /** 建立连接：返回 store + 原生客户端；失败抛异常 */
    Connection connect(String host, int port, String collectionName, int dimension) throws Exception;

    record Connection(EmbeddingStore<TextSegment> store, MilvusClientV2 client) {}
}
```

`RealMilvusConnector.java`（探针与建连代码迁自 WebApplication:299-344 与 MilvusChecker:24-55）：

```java
package me.maxt.rag.web.service.vector;

import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.milvus.MilvusEmbeddingStore;
import io.milvus.common.clientenum.ConsistencyLevelEnum;
import io.milvus.v2.client.ConnectConfig;
import io.milvus.v2.client.MilvusClientV2;

import java.net.InetSocketAddress;
import java.net.Socket;

/**
 * 生产适配器：真实 Milvus 的探针与建连。
 */
public class RealMilvusConnector implements MilvusConnector {

    @Override
    public boolean reachable(String host, int port, int timeoutMs) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), timeoutMs);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    @Override
    public String serverVersion(String host, int port) throws Exception {
        MilvusClientV2 client = new MilvusClientV2(ConnectConfig.builder()
                .uri("http://" + host + ":" + port)
                .build());
        try {
            Object version = client.getServerVersion();
            return version != null ? version.toString() : null;
        } finally {
            client.close();
        }
    }

    @Override
    public Connection connect(String host, int port, String collectionName, int dimension) throws Exception {
        EmbeddingStore<TextSegment> store = MilvusEmbeddingStore.builder()
                .host(host)
                .port(port)
                .collectionName(collectionName)
                .dimension(dimension)
                .consistencyLevel(ConsistencyLevelEnum.STRONG)
                .build();
        MilvusClientV2 client = new MilvusClientV2(ConnectConfig.builder()
                .uri("http://" + host + ":" + port)
                .build());
        return new Connection(store, client);
    }
}
```

- [ ] **Step 4: 运行测试确认通过**

Run: `mvn test -q -Dtest=RealMilvusConnectorTest`
Expected: 2 个测试 PASS

- [ ] **Step 5: 全量回归 + 提交**

Run: `mvn test -q`
Expected: 全绿（新增 2 个测试）

```bash
git add src/main/java/me/maxt/rag/web/service/vector/MilvusConnector.java src/main/java/me/maxt/rag/web/service/vector/RealMilvusConnector.java src/test/java/me/maxt/rag/web/service/vector/RealMilvusConnectorTest.java
git commit -m "新增: MilvusConnector 接缝与 RealMilvusConnector 生产适配器"
```

---

### Task 2: EmbeddingStoreManager 增加 Supplier 构造器与 replaceAllIndex

本任务只**新增** API；旧构造器、`swapStore`、`rebuildIndexFromMilvus` 暂留（WebApplication 还在用），Task 5 删除。

**Files:**
- Modify: `src/main/java/me/maxt/rag/web/service/EmbeddingStoreManager.java`
- Test: `src/test/java/me/maxt/rag/web/service/EmbeddingStoreManagerTest.java`

**Interfaces:**
- Consumes: 无
- Produces: `EmbeddingStoreManager(Supplier<EmbeddingStore<TextSegment>> storeSupplier)`（新构造器）、`void replaceAllIndex(Map<String, DocEntry> newIndex)`。Task 3 依赖。

- [ ] **Step 1: 写失败测试**

在 `EmbeddingStoreManagerTest.java` 追加（import 增加 `java.util.function.Supplier`）：

```java
    @Test
    void shouldConstructWithSupplier() {
        InMemoryEmbeddingStore<TextSegment> backing = new InMemoryEmbeddingStore<>();
        EmbeddingStoreManager supplierMgr = new EmbeddingStoreManager(() -> backing);

        supplierMgr.add(Embedding.from(new float[]{0.5f}), TextSegment.from("via supplier"));

        EmbeddingSearchRequest request = EmbeddingSearchRequest.builder()
                .queryEmbedding(Embedding.from(new float[]{0.5f}))
                .maxResults(5)
                .minScore(0.0)
                .build();
        assertThat(supplierMgr.search(request).matches()).hasSize(1);
    }

    @Test
    void shouldReplaceIndexAtomically() {
        TextSegment seg = TextSegment.from("content");
        seg.metadata().put("file_name", "test.pdf");
        mgr.add(Embedding.from(new float[]{0.5f}), seg);
        assertThat(mgr.getDocumentIndex()).containsKey("test.pdf");

        Map<String, EmbeddingStoreManager.DocEntry> fresh = Map.of(
                "doc.pdf", new EmbeddingStoreManager.DocEntry("/data", "PDF", 2));
        mgr.replaceAllIndex(fresh);

        assertThat(mgr.getDocumentIndex()).hasSize(1);
        assertThat(mgr.getDocumentIndex().get("doc.pdf").segmentCount).isEqualTo(2);
        assertThat(mgr.getDocumentIndex()).doesNotContainKey("test.pdf");
    }
```

- [ ] **Step 2: 运行测试确认失败**

Run: `mvn test -q -Dtest=EmbeddingStoreManagerTest`
Expected: 编译失败，Supplier 构造器与 `replaceAllIndex` 不存在

- [ ] **Step 3: 写实现**

`EmbeddingStoreManager.java` 修改（构造器区域，约 33-49 行）：

```java
    /** store 供应商：每次操作解析当前 store（volatile——旧 swapStore 路径仍会重赋值，Task 5 后仅构造时赋值） */
    private volatile Supplier<EmbeddingStore<TextSegment>> storeSupplier;

    /** 通过 store 供应商构造：每次操作解析当前 store（会话切换由供应商方控制）。 */
    public EmbeddingStoreManager(Supplier<EmbeddingStore<TextSegment>> storeSupplier) {
        this.storeSupplier = storeSupplier;
    }

    /** 静态 store 便捷构造器（暂留 shim，Task 5 删除）。 */
    public EmbeddingStoreManager(EmbeddingStore<TextSegment> store) {
        this(() -> store);
    }

    /** 整体替换文档索引——会话原子切换的第二半（与换 store 同步发生）。 */
    public void replaceAllIndex(Map<String, DocEntry> newIndex) {
        docIndex.clear();
        docIndex.putAll(newIndex);
    }
```

删除原 `private volatile EmbeddingStore<TextSegment> embeddingStore;` 字段——单一表示：所有读写都走 `storeSupplier`。为此旧 `swapStore` 改写为对 supplier 的重赋值（shim 语义不变，Task 5 整体删除）：

```java
    /** 运行时切换底层存储（暂留 shim，Task 5 删除——新路径由 MilvusSession 原子切换承担）。 */
    public synchronized void swapStore(EmbeddingStore<TextSegment> newStore) {
        this.storeSupplier = () -> newStore;
        this.docIndex.clear();
    }
```

所有读操作（`add`/`addAll`/`search`/`createContentRetriever`）中的 `embeddingStore` 引用替换为 `storeSupplier.get()`：

```java
    public String add(Embedding embedding, TextSegment textSegment) {
        String id = storeSupplier.get().add(embedding, textSegment);
        indexDoc(textSegment);
        return id;
    }
    // addAll / search / createContentRetriever 同理：embeddingStore → storeSupplier.get()
```

`rebuildIndexFromMilvus` 原样保留（Task 5 删除）。中间态自洽：旧构造器委托新构造器、旧 swapStore 与新 supplier 表示一致，重连路径在本任务与 Task 4 之间不失效。

- [ ] **Step 4: 运行测试确认通过**

Run: `mvn test -q -Dtest=EmbeddingStoreManagerTest`
Expected: 全部 PASS（含新增 2 个；旧 `shouldSwapStoreAndClearIndex`/`shouldRebuildIndexFromMilvusOnStartup` 此刻仍通过）

- [ ] **Step 5: 全量回归 + 提交**

Run: `mvn test -q`
Expected: 全绿

```bash
git add src/main/java/me/maxt/rag/web/service/EmbeddingStoreManager.java src/test/java/me/maxt/rag/web/service/EmbeddingStoreManagerTest.java
git commit -m "重构: EmbeddingStoreManager 新增 Supplier 构造器与 replaceAllIndex"
```

---

### Task 3: MilvusSession 状态机

**Files:**
- Create: `src/main/java/me/maxt/rag/web/service/vector/MilvusSession.java`
- Test: `src/test/java/me/maxt/rag/web/service/vector/MilvusSessionTest.java`

**Interfaces:**
- Consumes: Task 1 的 `MilvusConnector`；Task 2 的 `EmbeddingStoreManager(Supplier)` + `replaceAllIndex(Map)`；`me.maxt.rag.web.config.MilvusConfig`（已有，`getMilvusHost/getMilvusPort/getMilvusCollectionName/getMilvusDimension`）
- Produces: `MilvusSession(MilvusConfig, MilvusConnector)`、`String connect()`（null=成功）、`MilvusClientV2 nativeClient()`、`Status status()`、`ProbeResult probe()`、`EmbeddingStoreManager storeManager()`，嵌套 `enum State { CONNECTED, DEGRADED }`、`record Status(State state, String activeStore, String lastError)`、`record ProbeResult(boolean reachable, String version, String message)`。Task 4/6 依赖。

- [ ] **Step 1: 写失败测试**

```java
package me.maxt.rag.web.service.vector;

import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.store.embedding.EmbeddingSearchRequest;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.inmemory.InMemoryEmbeddingStore;
import io.milvus.v2.client.MilvusClientV2;
import io.milvus.v2.service.vector.request.QueryReq;
import io.milvus.v2.service.vector.response.QueryResp;
import me.maxt.rag.web.config.MilvusConfig;
import me.maxt.rag.web.service.EmbeddingStoreManager;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class MilvusSessionTest {

    /** 可编程假连接器：控制可达性/建连成败 */
    static class FakeConnector implements MilvusConnector {
        boolean reachable = true;
        boolean failConnect = false;
        int connectCalls = 0;
        final MilvusClientV2 client = mock(MilvusClientV2.class);
        final EmbeddingStore<TextSegment> store = new InMemoryEmbeddingStore<>();

        @Override
        public boolean reachable(String host, int port, int timeoutMs) { return reachable; }

        @Override
        public String serverVersion(String host, int port) { return "2.4.0"; }

        @Override
        public Connection connect(String host, int port, String collectionName, int dimension) throws Exception {
            connectCalls++;
            if (failConnect) throw new IOException("connection refused");
            return new Connection(store, client);
        }
    }

    private static MilvusConfig config() {
        MilvusConfig config = mock(MilvusConfig.class);
        when(config.getMilvusHost()).thenReturn("localhost");
        when(config.getMilvusPort()).thenReturn(19530);
        when(config.getMilvusCollectionName()).thenReturn("test_collection");
        when(config.getMilvusDimension()).thenReturn(512);
        return config;
    }

    /** mock 客户端返回两份 doc.pdf chunk + 一份 notes.txt（迁自旧 rebuildIndex 测试） */
    private static void stubIndexQuery(MilvusClientV2 client) {
        Map<String, Object> meta1 = Map.of("file_name", "doc.pdf", "file_type", "PDF",
                "absolute_directory_path", "/data");
        Map<String, Object> meta2 = Map.of("file_name", "notes.txt", "file_type", "TXT",
                "absolute_directory_path", "/data");
        QueryResp queryResp = QueryResp.builder()
                .queryResults(List.of(
                        QueryResp.QueryResult.builder().entity(Map.of("metadata", (Object) meta1)).build(),
                        QueryResp.QueryResult.builder().entity(Map.of("metadata", (Object) meta1)).build(),
                        QueryResp.QueryResult.builder().entity(Map.of("metadata", (Object) meta2)).build()))
                .build();
        when(client.query(any(QueryReq.class))).thenReturn(queryResp);
    }

    @Test
    void bornDegradedWithZeroIo() {
        FakeConnector connector = new FakeConnector();
        MilvusSession session = new MilvusSession(config(), connector);

        assertThat(session.status().state()).isEqualTo(MilvusSession.State.DEGRADED);
        assertThat(session.status().activeStore()).isEqualTo("in-memory");
        assertThat(session.nativeClient()).isNull();
        assertThat(connector.connectCalls).isZero();

        // 降级时写入/查询走内存 store
        session.storeManager().add(Embedding.from(new float[]{0.5f}), TextSegment.from("degraded write"));
        EmbeddingSearchRequest req = EmbeddingSearchRequest.builder()
                .queryEmbedding(Embedding.from(new float[]{0.5f}))
                .maxResults(5).minScore(0.0).build();
        assertThat(session.storeManager().search(req).matches()).hasSize(1);
    }

    @Test
    void connectFailsFastWhenUnreachable() {
        FakeConnector connector = new FakeConnector();
        connector.reachable = false;
        MilvusSession session = new MilvusSession(config(), connector);

        String error = session.connect();

        assertThat(error).contains("不可达");
        assertThat(session.status().state()).isEqualTo(MilvusSession.State.DEGRADED);
        assertThat(connector.connectCalls).isZero();  // 探针先于建连
    }

    @Test
    void connectSucceedsSwapsStoreAndRebuildsIndex() {
        FakeConnector connector = new FakeConnector();
        stubIndexQuery(connector.client);
        MilvusSession session = new MilvusSession(config(), connector);

        assertThat(session.connect()).isNull();
        assertThat(session.status().state()).isEqualTo(MilvusSession.State.CONNECTED);
        assertThat(session.status().activeStore()).isEqualTo("milvus");
        assertThat(session.nativeClient()).isSameAs(connector.client);

        // 索引从 Milvus metadata 重建
        Map<String, EmbeddingStoreManager.DocEntry> index = session.storeManager().getDocumentIndex();
        assertThat(index).hasSize(2);
        assertThat(index.get("doc.pdf").segmentCount).isEqualTo(2);
        assertThat(index.get("notes.txt").segmentCount).isEqualTo(1);

        // 后续写入落到 milvus store（假件的 InMemory）
        session.storeManager().add(Embedding.from(new float[]{0.5f}), TextSegment.from("after swap"));
        EmbeddingSearchRequest req = EmbeddingSearchRequest.builder()
                .queryEmbedding(Embedding.from(new float[]{0.5f}))
                .maxResults(5).minScore(0.0).build();
        assertThat(session.storeManager().search(req).matches())
                .extracting(m -> m.embedded().text())
                .contains("after swap");
    }

    @Test
    void connectDegradesWhenConnectThrows() {
        FakeConnector connector = new FakeConnector();
        connector.failConnect = true;
        MilvusSession session = new MilvusSession(config(), connector);

        String error = session.connect();

        assertThat(error).contains("切换失败");
        assertThat(session.status().state()).isEqualTo(MilvusSession.State.DEGRADED);
        assertThat(session.nativeClient()).isNull();
        assertThat(session.status().lastError()).isEqualTo(error);
    }

    @Test
    void recoveryAfterDegradeRefreshesNativeClient() {
        // 原 bug 场景：启动时 Milvus 挂（降级）→ 之后恢复 → 重连
        FakeConnector connector = new FakeConnector();
        connector.reachable = false;
        MilvusSession session = new MilvusSession(config(), connector);
        assertThat(session.connect()).isNotNull();

        connector.reachable = true;
        stubIndexQuery(connector.client);

        assertThat(session.connect()).isNull();
        assertThat(session.nativeClient()).isSameAs(connector.client);  // 引用永不过期
        assertThat(session.status().state()).isEqualTo(MilvusSession.State.CONNECTED);
    }

    @Test
    void degradeAfterConnectedClearsIndexAndClosesClient() {
        FakeConnector connector = new FakeConnector();
        stubIndexQuery(connector.client);
        MilvusSession session = new MilvusSession(config(), connector);
        session.connect();

        connector.reachable = false;
        String error = session.connect();  // Milvus 又挂了

        assertThat(error).contains("不可达");
        assertThat(session.status().state()).isEqualTo(MilvusSession.State.DEGRADED);
        assertThat(session.storeManager().getDocumentIndex()).isEmpty();
    }

    @Test
    void probeIsPureRead() {
        FakeConnector connector = new FakeConnector();
        connector.reachable = false;
        MilvusSession session = new MilvusSession(config(), connector);

        MilvusSession.ProbeResult unreachable = session.probe();
        assertThat(unreachable.reachable()).isFalse();
        assertThat(unreachable.message()).contains("不可达");

        connector.reachable = true;
        MilvusSession.ProbeResult reachable = session.probe();
        assertThat(reachable.reachable()).isTrue();
        assertThat(reachable.version()).isEqualTo("2.4.0");

        // probe 不改状态、不建连
        assertThat(session.status().state()).isEqualTo(MilvusSession.State.DEGRADED);
        assertThat(connector.connectCalls).isZero();
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

Run: `mvn test -q -Dtest=MilvusSessionTest`
Expected: 编译失败，`MilvusSession` 不存在

- [ ] **Step 3: 写实现**

`MilvusSession.java`：

```java
package me.maxt.rag.web.service.vector;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.inmemory.InMemoryEmbeddingStore;
import io.milvus.v2.client.MilvusClientV2;
import io.milvus.v2.service.vector.request.QueryReq;
import io.milvus.v2.service.vector.response.QueryResp;
import me.maxt.rag.web.config.MilvusConfig;
import me.maxt.rag.web.service.EmbeddingStoreManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 向量库会话（术语见 CONTEXT.md）：拥有向量存储连接生命周期——当前活跃 store、
 * 原生 Milvus 客户端、以及 DEGRADED（内存降级）↔ CONNECTED 之间的原子切换
 * （换 store + 重建文档索引一步完成）。
 *
 * <p>构造零 I/O，出生即 DEGRADED；{@link #connect()} 是唯一建连路径
 * （探针快速失败 → 连接 → 原子切换），启动初始化与重连共用。消费者每次使用时
 * 通过 {@link #nativeClient()} 拉取当前引用，不缓存——重连后永不过期。</p>
 */
public class MilvusSession {

    private static final Logger log = LoggerFactory.getLogger(MilvusSession.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final int PROBE_TIMEOUT_MS = 2000;

    public enum State { CONNECTED, DEGRADED }

    public record Status(State state, String activeStore, String lastError) {}

    public record ProbeResult(boolean reachable, String version, String message) {}

    private final MilvusConfig config;
    private final MilvusConnector connector;
    private final EmbeddingStore<TextSegment> memoryStore = new InMemoryEmbeddingStore<>();
    private final EmbeddingStoreManager storeManager;
    private volatile MilvusConnector.Connection current;  // null = DEGRADED
    private volatile String lastError = "";

    public MilvusSession(MilvusConfig config, MilvusConnector connector) {
        this.config = config;
        this.connector = connector;
        this.storeManager = new EmbeddingStoreManager(() ->
                current != null ? current.store() : memoryStore);
    }

    /** 唯一建连路径：探针快速失败 → 连接 → 原子切换（换 store + 重建索引）。
     * @return null 表示成功；否则返回错误描述（此时会话为 DEGRADED） */
    public synchronized String connect() {
        String host = config.getMilvusHost();
        int port = config.getMilvusPort();
        if (!connector.reachable(host, port, PROBE_TIMEOUT_MS)) {
            return degrade("Milvus 不可达 (" + host + ":" + port + ") → 请先 docker compose up -d");
        }
        try {
            MilvusConnector.Connection c = connector.connect(
                    host, port, config.getMilvusCollectionName(), config.getMilvusDimension());
            this.current = c;
            this.lastError = "";
            storeManager.replaceAllIndex(loadIndex(c.client()));
            log.info("Milvus 已连接 ({}:{}), 向量存储已切换", host, port);
            return null;
        } catch (Exception e) {
            log.warn("Milvus 存储切换失败: {}", e.getMessage());
            return degrade("Milvus 可达但存储切换失败: " + e.getMessage());
        }
    }

    /** 拉模型：当前原生客户端；null = 降级中 */
    public MilvusClientV2 nativeClient() {
        MilvusConnector.Connection c = current;
        return c != null ? c.client() : null;
    }

    public Status status() {
        MilvusConnector.Connection c = current;
        return new Status(c != null ? State.CONNECTED : State.DEGRADED,
                c != null ? "milvus" : "in-memory", lastError);
    }

    /** 纯读探测（不建会话、不改状态），供环境检测的 Milvus 检测项委托 */
    public ProbeResult probe() {
        String host = config.getMilvusHost();
        int port = config.getMilvusPort();
        if (!connector.reachable(host, port, PROBE_TIMEOUT_MS)) {
            return new ProbeResult(false, null,
                    "Milvus 不可达 (" + host + ":" + port + ") → docker compose up -d 启动 Milvus");
        }
        String version = null;
        try {
            version = connector.serverVersion(host, port);
        } catch (Exception e) {
            // 版本查询失败不影响可达性判断
        }
        return new ProbeResult(true, version, "Milvus 可连接 (" + host + ":" + port + ")");
    }

    public EmbeddingStoreManager storeManager() { return storeManager; }

    /** 进入降级：关闭旧客户端、换回内存 store、清空索引。返回错误描述。 */
    private String degrade(String message) {
        MilvusConnector.Connection c = current;
        current = null;
        if (c != null) {
            try { c.client().close(); } catch (Exception ignored) {}
        }
        storeManager.replaceAllIndex(Map.of());
        this.lastError = message;
        log.warn("向量库会话降级到内存存储: {}", message);
        return message;
    }

    /**
     * 从 Milvus 查询全量 metadata 重建文档索引（迁自原 EmbeddingStoreManager.rebuildIndexFromMilvus）。
     * 查询失败返回空 Map——降级不抛出，与原行为一致。
     */
    @SuppressWarnings("unchecked")
    private Map<String, EmbeddingStoreManager.DocEntry> loadIndex(MilvusClientV2 client) {
        Map<String, EmbeddingStoreManager.DocEntry> index = new LinkedHashMap<>();
        try {
            QueryReq req = QueryReq.builder()
                    .collectionName(config.getMilvusCollectionName())
                    .filter("id != \"\"")
                    .outputFields(List.of("metadata"))
                    .limit(10000)
                    .build();
            QueryResp resp = client.query(req);
            int count = 0;
            for (QueryResp.QueryResult qr : resp.getQueryResults()) {
                Map<String, Object> entity = qr.getEntity();
                Object metaObj = entity.get("metadata");
                if (metaObj == null) continue;
                Map<String, Object> meta;
                if (metaObj instanceof Map) {
                    meta = (Map<String, Object>) metaObj;
                } else {
                    meta = MAPPER.readValue(metaObj.toString(), Map.class);
                }
                String fileName = (String) meta.get("file_name");
                if (fileName == null) continue;
                String fileType = (String) meta.getOrDefault("file_type", "");
                String dir = (String) meta.getOrDefault("absolute_directory_path", "");
                index.merge(fileName, new EmbeddingStoreManager.DocEntry(dir, fileType, 1),
                        (old, n) -> { old.segmentCount += 1; return old; });
                count++;
            }
            log.info("从 Milvus 重建文档索引完成: {} 个文档, {} 个 chunk", index.size(), count);
        } catch (Exception e) {
            log.warn("从 Milvus 重建文档索引失败: {}", e.getMessage());
        }
        return index;
    }
}
```

- [ ] **Step 4: 运行测试确认通过**

Run: `mvn test -q -Dtest=MilvusSessionTest`
Expected: 7 个测试 PASS

- [ ] **Step 5: 全量回归 + 提交**

Run: `mvn test -q`
Expected: 全绿

```bash
git add src/main/java/me/maxt/rag/web/service/vector/MilvusSession.java src/test/java/me/maxt/rag/web/service/vector/MilvusSessionTest.java
git commit -m "新增: MilvusSession 向量库会话——原子切换状态机，构造零 I/O"
```

---

### Task 4: 消费者迁移（MilvusChecker / WebApplication / KG / Sparse）

**Files:**
- Modify: `src/main/java/me/maxt/rag/web/service/environment/MilvusChecker.java`（全文重写）
- Modify: `src/main/java/me/maxt/rag/web/WebApplication.java:83-137, 161-207, 232-239, 299-344`
- Modify: `src/main/java/me/maxt/rag/web/service/KnowledgeGraphService.java:35,42-48,182-186,216-252`
- Modify: `src/main/java/me/maxt/rag/web/service/vector/recall/SparseRecallStrategy.java:32-40,64-77`
- Test: `src/test/java/me/maxt/rag/web/service/environment/MilvusCheckerTest.java`（新建）
- Test: `src/test/java/me/maxt/rag/web/service/vector/recall/SparseRecallStrategyTest.java`（3 处构造改 lambda）

**Interfaces:**
- Consumes: Task 3 的 `MilvusSession`（`probe()`、`connect()`、`nativeClient()`、`storeManager()`）
- Produces: `MilvusChecker(MilvusSession session)`；`SparseRecallStrategy(Supplier<MilvusClientV2> clientSupplier, String collectionName)`；`KnowledgeGraphService(RecallConfig, EmbeddingStoreManager, Supplier<MilvusClientV2>, LightRagBridge)`

- [ ] **Step 1: 写 MilvusChecker 失败测试**

```java
package me.maxt.rag.web.service.environment;

import me.maxt.rag.web.service.vector.MilvusSession;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class MilvusCheckerTest {

    @Test
    void shouldReportOkWhenReachable() {
        MilvusSession session = mock(MilvusSession.class);
        when(session.probe()).thenReturn(new MilvusSession.ProbeResult(true, "2.4.0", "Milvus 可连接"));
        MilvusChecker checker = new MilvusChecker(session);

        CheckResult result = checker.check();

        assertThat(result.status()).isEqualTo(CheckResult.Status.OK);
        assertThat(result.version()).isEqualTo("2.4.0");
    }

    @Test
    void shouldReportMissingWhenUnreachable() {
        MilvusSession session = mock(MilvusSession.class);
        when(session.probe()).thenReturn(new MilvusSession.ProbeResult(false, null, "Milvus 不可达"));
        MilvusChecker checker = new MilvusChecker(session);

        CheckResult result = checker.check();

        assertThat(result.status()).isEqualTo(CheckResult.Status.MISSING);
        assertThat(result.message()).isEqualTo("Milvus 不可达");
    }
}
```

（`CheckResult` 在 `service.environment` 包内，测试同包无需 import。）

- [ ] **Step 2: 运行确认失败**

Run: `mvn test -q -Dtest=MilvusCheckerTest`
Expected: 编译失败，`MilvusChecker(MilvusSession)` 构造器不存在

- [ ] **Step 3: 重写 MilvusChecker**

```java
package me.maxt.rag.web.service.environment;

import me.maxt.rag.web.service.vector.MilvusSession;

/**
 * Milvus 连接性检测：向量库会话 probe() 的适配器（探针语义唯一所有者为 MilvusSession）。
 */
public class MilvusChecker implements DependencyChecker {

    private final MilvusSession session;

    public MilvusChecker(MilvusSession session) {
        this.session = session;
    }

    @Override
    public String name() { return "milvus"; }

    @Override
    public CheckResult check() {
        MilvusSession.ProbeResult p = session.probe();
        if (p.reachable()) {
            return new CheckResult(name(), CheckResult.Category.SERVICE,
                    CheckResult.Status.OK, p.version(), p.message());
        }
        return new CheckResult(name(), CheckResult.Category.SERVICE,
                CheckResult.Status.MISSING, null, p.message());
    }
}
```

- [ ] **Step 4: SparseRecallStrategy 换 Supplier**

`SparseRecallStrategy.java` 构造器与字段（32-40 行）改为：

```java
    private final java.util.function.Supplier<MilvusClientV2> clientSupplier;
    private final String collectionName;
    private final boolean sparseAvailable;

    public SparseRecallStrategy(java.util.function.Supplier<MilvusClientV2> clientSupplier,
                                String collectionName) {
        this.clientSupplier = clientSupplier;
        this.collectionName = collectionName;
        this.sparseAvailable = detectSparseField(clientSupplier.get(), collectionName);
    }

    /** 启动时探测 collection 是否含 sparse_vector 字段（client 为 null 视为不可用） */
    private static boolean detectSparseField(MilvusClientV2 client, String collectionName) {
        if (client == null) {
            return false;
        }
        // …… 原方法体不变
    }
```

`recall()` 方法（64-77 行）开头改为：

```java
        MilvusClientV2 client = clientSupplier.get();
        if (!sparseAvailable || client == null) {
            return List.of();
        }
        // ……
        SearchResp resp = client.search(req);   // 原 milvusClient.search 改为 client.search
```

（import 加 `java.util.function.Supplier`，字段用 `Supplier` 短名。）

- [ ] **Step 5: 更新 SparseRecallStrategyTest 构造点**

3 处 `new SparseRecallStrategy(milvusClient, COLLECTION)` → `new SparseRecallStrategy(() -> milvusClient, COLLECTION)`（`shouldReturnName`、`shouldReturnEmptyOnSearchFailure`、`shouldReturnEmptyWhenSparseFieldMissing` 及文件内其余出现处）。

- [ ] **Step 6: KnowledgeGraphService 换 Supplier**

字段与构造器（35、42-48 行）：

```java
    private final java.util.function.Supplier<MilvusClientV2> milvusClientSupplier;

    public KnowledgeGraphService(RecallConfig config, EmbeddingStoreManager storeManager,
                                 java.util.function.Supplier<MilvusClientV2> milvusClientSupplier,
                                 LightRagBridge bridge) {
        this.config = config;
        this.storeManager = storeManager;
        this.milvusClientSupplier = milvusClientSupplier;
        this.bridge = bridge;
    }
```

`runLightRagInsert`（182 行 null 检查）改为每次拉取：

```java
        MilvusClientV2 milvusClient = milvusClientSupplier != null ? milvusClientSupplier.get() : null;
        if (milvusClient == null) {
            log.warn("MilvusClientV2 not available, cannot load document text");
            lastError.set("MilvusClient 未初始化");
            return false;
        }
```

`loadDocumentText(String fileName)` 签名改为 `loadDocumentText(MilvusClientV2 client, String fileName)`，方法内 `milvusClient.query(req)` → `client.query(req)`；`runLightRagInsert` 调用处改为 `loadDocumentText(milvusClient, fileName)`。

（`KnowledgeGraphServiceTest` 传 `null` 第三参——`null` 对 `Supplier` 同样合法，无需改动。）

- [ ] **Step 7: WebApplication 切换到 session**

`WebApplication.java` 修改点：

(a) 字段区（83 行 `milvusClientV2` 删除，新增）：

```java
    private final MilvusSession milvusSession;
```

(b) 构造器开头（85-137 行区域重排——session 先于 checkers，初始连接在共享依赖之后）：

```java
    public WebApplication(AppConfig config) {
        this.config = config;

        // 向量库会话（构造零 I/O，出生即 DEGRADED；MilvusChecker 委托其探针）
        this.milvusSession = new MilvusSession(config, new RealMilvusConnector());

        // 环境检测（非阻塞后台启动，SSE 推送结果）
        List<DependencyChecker> checkers = List.of(
                new PythonChecker(config, config.getLightRagPythonPath()),
                new PipPackageChecker(config, config.getLightRagPythonPath()),
                new MilvusChecker(milvusSession),
                new PandocChecker(config),
                new TesseractChecker(config),
                new ModelFileChecker(config, config.getRerankModelPath(), config.getLightRagEmbeddingModelPath()));
        this.environmentChecker = new EnvironmentChecker(config, checkers);
        this.environmentController = new EnvironmentController(environmentChecker);
        environmentChecker.run();

        // 共享依赖（原样保留）
        this.embeddingModel = new BgeSmallZhV15QuantizedEmbeddingModel();
        this.chatModel = OpenAiChatModel.builder() /* ……原样…… */ .build();

        // 初始连接：探针快速失败，降级不阻塞启动
        String milvusError = milvusSession.connect();
        if (milvusError != null) {
            log.warn("Milvus 不可用，降级到内存存储（数据不持久化）: {}", milvusError);
        }
        this.storeManager = milvusSession.storeManager();
```

以下原有代码全部删除：112-137 行的 Milvus try/catch 块、`buildMilvusStore()`（299-307）、`buildMilvusClient()`（309-313）、`reconnectMilvus()`（315-344）。

(c) KG 组装（171 行）：

```java
        KnowledgeGraphService kgService = new KnowledgeGraphService(
                config, storeManager, milvusSession::nativeClient, lightRagBridge);
```

(d) sparse 注册（177-180 行）：

```java
            if (milvusSession.nativeClient() != null) {
                registry.put("sparse", new SparseRecallStrategy(
                        milvusSession::nativeClient, config.getMilvusCollectionName()));
            }
```

(e) 重连端点（232-239 行）——端点编排，成功后触发环境重检：

```java
        app.post("/api/env/reconnect-milvus", ctx -> {
            String error = milvusSession.connect();
            if (error == null) {
                ctx.json(Map.of("success", true, "message", "已切换到 Milvus"));
                Thread t = new Thread(environmentChecker::checkAll, "env-check-after-reconnect");
                t.setDaemon(true);
                t.start();
            } else {
                ctx.status(503).json(Map.of("success", false, "error", error));
            }
        });
```

(f) import 清理：删 `io.milv.v2.client.ConnectConfig`、`io.milvus.v2.client.MilvusClientV2`、`io.milvus.common.clientenum.ConsistencyLevelEnum`、`dev.langchain4j.store.embedding.milvus.MilvusEmbeddingStore`；增 `me.maxt.rag.web.service.vector.MilvusSession`、`me.maxt.rag.web.service.vector.RealMilvusConnector`。

- [ ] **Step 8: 编译 + 全量测试**

Run: `mvn test -q`
Expected: 全绿（MilvusCheckerTest 新增 2 个通过；SparseRecallStrategyTest 通过）

- [ ] **Step 9: 提交**

```bash
git add src/main/java/me/maxt/rag/web/service/environment/MilvusChecker.java src/main/java/me/maxt/rag/web/WebApplication.java src/main/java/me/maxt/rag/web/service/KnowledgeGraphService.java src/main/java/me/maxt/rag/web/service/vector/recall/SparseRecallStrategy.java src/test/java/me/maxt/rag/web/service/environment/MilvusCheckerTest.java src/test/java/me/maxt/rag/web/service/vector/recall/SparseRecallStrategyTest.java
git commit -m "重构: 消费者迁移到向量库会话——重连后客户端引用永不过期"
```

---

### Task 5: 删除 EmbeddingStoreManager 旧路径

前置：Task 4 后生产代码已无旧路径调用者。

**Files:**
- Modify: `src/main/java/me/maxt/rag/web/service/EmbeddingStoreManager.java`
- Test: `src/test/java/me/maxt/rag/web/service/EmbeddingStoreManagerTest.java`
- Test: `src/test/java/me/maxt/rag/web/service/RAGServiceTest.java:45`
- Test: `src/test/java/me/maxt/rag/web/service/DocumentServiceTest.java:31`
- Test: `src/test/java/me/maxt/rag/web/evaluation/EvaluationTest.java:81`
- Test: `src/test/java/me/maxt/rag/web/service/EmbeddingStoreManagerMilvusIT.java:45`

**Interfaces:**
- Consumes: 无
- Produces: `EmbeddingStoreManager` 只剩 `Supplier` 构造器；`swapStore`/`rebuildIndexFromMilvus`/旧构造器从接口消失

- [ ] **Step 1: 删除实现中的旧路径**

`EmbeddingStoreManager.java`：
- 删旧构造器 `EmbeddingStoreManager(EmbeddingStore<TextSegment> store)`
- 删 `swapStore(...)` 方法与 `embeddingStore` 旧字段
- 删 `rebuildIndexFromMilvus(...)` 方法及 `MilvusClientV2`/`QueryReq`/`QueryResp`/`ObjectMapper` 相关 import（`MAPPER` 字段一并删）

- [ ] **Step 2: 删除/更新测试**

`EmbeddingStoreManagerTest.java`：
- `setUp`：`mgr = new EmbeddingStoreManager(() -> memoryStore);`
- 删 `shouldSwapStoreAndClearIndex`（语义由 `MilvusSessionTest.connectSucceedsSwapsStoreAndRebuildsIndex` / `degradeAfterConnectedClearsIndexAndClosesClient` 覆盖）
- 删 `shouldRebuildIndexFromMilvusOnStartup`（解析逻辑已迁入 `MilvusSession.loadIndex`，由 session 测试覆盖）
- 删 `MilvusClientV2`/`QueryReq`/`QueryResp` 相关 import

其余 4 个文件构造点一行换形：
- `RAGServiceTest.java:45`：`storeManager = new EmbeddingStoreManager(() -> new InMemoryEmbeddingStore<>());`
- `DocumentServiceTest.java:31`：同上
- `EvaluationTest.java:81`：`new EmbeddingStoreManager(() -> new InMemoryEmbeddingStore<>())`
- `EmbeddingStoreManagerMilvusIT.java:45`：`mgr = new EmbeddingStoreManager(() -> store);`

- [ ] **Step 3: 编译 + 全量测试**

Run: `mvn test -q`
Expected: 全绿（EmbeddingStoreManagerTest 剩 8 个：原 6 个改造 + Task 2 新增 2 个）

- [ ] **Step 4: 提交**

```bash
git add -u
git commit -m "重构: 删除 EmbeddingStoreManager 旧存储切换路径，接口恢复存储无关"
```

---

### Task 6: MilvusSessionIT 集成测试 + 回归 + 文档

**Files:**
- Create: `src/test/java/me/maxt/rag/web/service/vector/MilvusSessionIT.java`
- Modify: `CLAUDE.md`（关键入口表）
- Modify: `docs/modules.md`

**Interfaces:**
- Consumes: Task 1 `RealMilvusConnector`、Task 3 `MilvusSession`
- Produces: 无（验证性任务）

- [ ] **Step 1: 写集成测试**

```java
package me.maxt.rag.web.service.vector;

import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.store.embedding.EmbeddingSearchRequest;
import dev.langchain4j.store.embedding.EmbeddingSearchResult;
import me.maxt.rag.web.config.MilvusConfig;
import me.maxt.rag.web.service.EmbeddingStoreManager;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.milvus.MilvusContainer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 向量库会话集成测试：Testcontainers 启动真实 Milvus，
 * 验证 RealMilvusConnector 全链路（探针→建连→原子切换→增查）。
 * 仅通过 mvn test -Dtest=MilvusSessionIT 显式运行（需要 Docker）。
 */
@Testcontainers
class MilvusSessionIT {

    @Container
    static MilvusContainer milvus = new MilvusContainer("milvusdb/milvus:v2.4.0");

    static MilvusSession session;
    static final int DIMENSION = 512;

    @BeforeAll
    static void setUp() {
        MilvusConfig config = mock(MilvusConfig.class);
        when(config.getMilvusHost()).thenReturn(milvus.getHost());
        when(config.getMilvusPort()).thenReturn(milvus.getMappedPort(19530));
        when(config.getMilvusCollectionName()).thenReturn("test_collection");
        when(config.getMilvusDimension()).thenReturn(DIMENSION);
        session = new MilvusSession(config, new RealMilvusConnector());
    }

    @Test
    void shouldConnectSwapStoreAndSearchThroughRealMilvus() {
        assertThat(session.connect()).isNull();
        assertThat(session.status().state()).isEqualTo(MilvusSession.State.CONNECTED);
        assertThat(session.nativeClient()).isNotNull();

        EmbeddingStoreManager mgr = session.storeManager();
        float[] vector = new float[DIMENSION];
        vector[0] = 0.1f;
        vector[1] = 0.2f;
        mgr.add(Embedding.from(vector), TextSegment.from("session it content"));

        EmbeddingSearchRequest request = EmbeddingSearchRequest.builder()
                .queryEmbedding(Embedding.from(vector))
                .maxResults(5)
                .minScore(0.5)
                .build();
        EmbeddingSearchResult<TextSegment> result = mgr.search(request);
        assertThat(result.matches()).hasSize(1);
        assertThat(result.matches().get(0).embedded().text()).isEqualTo("session it content");
    }

    @Test
    void shouldProbeRealMilvus() {
        MilvusSession.ProbeResult p = session.probe();
        assertThat(p.reachable()).isTrue();
        assertThat(p.version()).isNotBlank();
    }
}
```

- [ ] **Step 2: 运行集成测试**

Run: `mvn test -q -Dtest=MilvusSessionIT`
Expected: 2 个测试 PASS（需 Docker daemon）

- [ ] **Step 3: 全量回归**

Run: `mvn test -q && mvn compile -q`
Expected: 全绿

- [ ] **Step 4: 手工验证重连场景（原活 bug 的端到端确认）**

1. `docker compose down` → `mvn clean package -q -DskipTests` → 启动 jar：日志出现"降级到内存存储"，启动不卡顿（探针 2s 快速失败）
2. `docker compose up -d` → `curl -X POST http://localhost:8080/api/env/reconnect-milvus`：返回 `{"success":true,...}`
3. **关键断言**：`curl http://localhost:8080/api/kg/status` 正常，且对一个已导入文档 `POST /api/kg/build/{docId}` 能成功从 Milvus 回查文本（重连前这里必失败——旧代码 KG 持有 null 客户端）
4. `curl http://localhost:8080/api/env/status`：milvus 检测项 OK

- [ ] **Step 5: 更新文档**

`CLAUDE.md` 关键入口表追加一行：

```markdown
- `me.maxt.rag.web.service.vector.MilvusSession` — 向量库会话（连接生命周期、降级/重连、原子切换）
```

`docs/modules.md` 相应段落更新（EmbeddingStoreManager 职责改为"存储门面 + 文档索引"；新增 MilvusSession 条目）。测试数量如有变化同步 `CLAUDE.md` 的"126 个单元测试"表述（以 `mvn test` 实际输出为准）。

- [ ] **Step 6: 提交**

```bash
git add src/test/java/me/maxt/rag/web/service/vector/MilvusSessionIT.java CLAUDE.md docs/modules.md
git commit -m "验证: MilvusSession 集成测试与文档更新，重连场景端到端确认"
```

---

## Self-Review 记录

- **决议覆盖**：Q1 原子会话→Task 3 `connect()` 原子性；Q2/Q9 纯拉→Task 4 消费者换 Supplier；Q3 推迟→ADR-0001（不实现）；Q4 Supplier 注入→Task 2；Q5 单一 connect()→Task 3；Q6 checker 委托→Task 4；Q8 连接器接缝→Task 1/3；Q10 命名落位→包路径即 `service.vector`；无监听器→全局约束。✓
- **占位符扫描**：Task 4 Step 7(b) 中 chatModel builder 标注"原样保留"——该构造器在现文件 102-109 行，未变动，非占位符。其余步骤均含完整代码。✓
- **类型一致性**：`replaceAllIndex(Map<String, DocEntry>)`（Task 2 定义，Task 3 调用）；`ProbeResult(boolean, String, String)`（Task 3 定义，Task 4 映射）；`Connection(EmbeddingStore<TextSegment>, MilvusClientV2)`（Task 1 定义，Task 3/4 使用）；`Supplier<MilvusClientV2>`（Task 4 KG/Sparse/WebApplication 一致）。✓
- **中间态自洽**（复查发现并修正）：Task 2 初稿用双字段（`storeSupplier` + 旧 `embeddingStore`）会让 Task 2→4 窗口内的 `swapStore` 写旧字段、读走 supplier，重连静默失效。已改为单一 `volatile storeSupplier` 表示：旧构造器/旧 `swapStore` 变成委托 shim，中间每个提交都完整可用。✓
