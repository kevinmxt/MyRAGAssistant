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
