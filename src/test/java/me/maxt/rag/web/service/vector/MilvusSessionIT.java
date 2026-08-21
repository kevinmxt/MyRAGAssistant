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
