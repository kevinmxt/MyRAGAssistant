package me.maxt.rag.web.service.vector.recall;

import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import io.milvus.v2.client.MilvusClientV2;
import io.milvus.v2.service.collection.request.DescribeCollectionReq;
import io.milvus.v2.service.collection.response.DescribeCollectionResp;
import io.milvus.v2.service.vector.request.SearchReq;
import io.milvus.v2.service.vector.response.SearchResp;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SparseRecallStrategyTest {

    private static final String COLLECTION = "rag_knowledge_base";

    private static DescribeCollectionResp describeResp(boolean withSparse) {
        if (withSparse) {
            return DescribeCollectionResp.builder()
                    .fieldNames(List.of("text", "file_name", "absolute_directory_path", "sparse_vector"))
                    .build();
        }
        return DescribeCollectionResp.builder()
                .fieldNames(List.of("text", "file_name", "absolute_directory_path"))
                .build();
    }

    @Test
    void shouldReturnName() {
        MilvusClientV2 milvusClient = mock(MilvusClientV2.class);
        when(milvusClient.describeCollection(any(DescribeCollectionReq.class)))
                .thenReturn(describeResp(true));
        SparseRecallStrategy strategy = new SparseRecallStrategy(milvusClient, COLLECTION);
        assertThat(strategy.name()).isEqualTo("sparse");
    }

    @Test
    void shouldReturnEmptyOnSearchFailure() {
        MilvusClientV2 milvusClient = mock(MilvusClientV2.class);
        when(milvusClient.describeCollection(any(DescribeCollectionReq.class)))
                .thenReturn(describeResp(true));
        when(milvusClient.search(any(SearchReq.class)))
                .thenThrow(new RuntimeException("milvus unavailable"));

        SparseRecallStrategy strategy = new SparseRecallStrategy(milvusClient, COLLECTION);
        List<EmbeddingMatch<TextSegment>> result = strategy.recall("test query", 5);
        assertThat(result).isEmpty();
    }

    @Test
    void shouldReturnEmptyWhenSparseFieldMissing() {
        MilvusClientV2 milvusClient = mock(MilvusClientV2.class);
        when(milvusClient.describeCollection(any(DescribeCollectionReq.class)))
                .thenReturn(describeResp(false));

        SparseRecallStrategy strategy = new SparseRecallStrategy(milvusClient, COLLECTION);
        List<EmbeddingMatch<TextSegment>> result = strategy.recall("test query", 5);
        assertThat(result).isEmpty();
    }

    @Test
    void shouldReturnEmptyWhenSchemaProbeFails() {
        MilvusClientV2 milvusClient = mock(MilvusClientV2.class);
        when(milvusClient.describeCollection(any(DescribeCollectionReq.class)))
                .thenThrow(new RuntimeException("milvus down"));

        SparseRecallStrategy strategy = new SparseRecallStrategy(milvusClient, COLLECTION);
        List<EmbeddingMatch<TextSegment>> result = strategy.recall("test query", 5);
        assertThat(result).isEmpty();
    }

    @Test
    void shouldReturnResultsWhenSearchSucceeds() {
        MilvusClientV2 milvusClient = mock(MilvusClientV2.class);
        when(milvusClient.describeCollection(any(DescribeCollectionReq.class)))
                .thenReturn(describeResp(true));

        SearchResp.SearchResult hit = SearchResp.SearchResult.builder()
                .score(2.5f)
                .id("id-1")
                .entity(java.util.Map.of("text", "bm25 hit"))
                .build();
        SearchResp resp = SearchResp.builder()
                .searchResults(List.of(List.of(hit)))
                .build();
        when(milvusClient.search(any(SearchReq.class))).thenReturn(resp);

        SparseRecallStrategy strategy = new SparseRecallStrategy(milvusClient, COLLECTION);
        List<EmbeddingMatch<TextSegment>> result = strategy.recall("test query", 5);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).embedded().text()).isEqualTo("bm25 hit");
    }
}
