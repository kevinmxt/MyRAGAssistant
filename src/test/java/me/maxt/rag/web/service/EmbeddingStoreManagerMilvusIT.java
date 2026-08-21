package me.maxt.rag.web.service;

import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.store.embedding.EmbeddingSearchRequest;
import dev.langchain4j.store.embedding.EmbeddingSearchResult;
import dev.langchain4j.store.embedding.milvus.MilvusEmbeddingStore;
import io.milvus.common.clientenum.ConsistencyLevelEnum;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.milvus.MilvusContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Milvus 集成测试：使用 Testcontainers 启动真实 Milvus，验证
 * {@link EmbeddingStoreManager} 基于 MilvusEmbeddingStore 的增查全链路。
 *
 * <p>仅通过 {@code mvn test -Dtest=EmbeddingStoreManagerMilvusIT} 显式运行，
 * 默认 surefire 模式不会执行 *IT 类（需要 Docker daemon）。</p>
 */
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
                // MilvusEmbeddingStore 默认 EVENTUALLY（最终一致），写入后立即查询会查不到；
                // 集成测试需要确定性的结果，显式使用强一致性
                .consistencyLevel(ConsistencyLevelEnum.STRONG)
                .build();
        mgr = new EmbeddingStoreManager(() -> store);
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
