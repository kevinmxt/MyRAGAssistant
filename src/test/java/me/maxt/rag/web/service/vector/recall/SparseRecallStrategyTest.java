package me.maxt.rag.web.service.vector.recall;

import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import io.milvus.v2.client.MilvusClientV2;
import io.milvus.v2.service.vector.request.SearchReq;
import io.milvus.v2.service.vector.response.SearchResp;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SparseRecallStrategyTest {

    private final MilvusClientV2 milvusClient = mock(MilvusClientV2.class);
    private final SparseRecallStrategy strategy = new SparseRecallStrategy(
            milvusClient, "rag_knowledge_base");

    @Test
    void shouldReturnName() {
        assertThat(strategy.name()).isEqualTo("sparse");
    }

    @Test
    void shouldReturnEmptyOnFailure() {
        when(milvusClient.search(any(SearchReq.class)))
                .thenThrow(new RuntimeException("milvus unavailable"));

        List<EmbeddingMatch<TextSegment>> result = strategy.recall("test query", 5);
        assertThat(result).isEmpty();
    }
}
