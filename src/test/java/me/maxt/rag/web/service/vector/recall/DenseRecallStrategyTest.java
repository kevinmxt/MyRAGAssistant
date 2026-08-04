package me.maxt.rag.web.service.vector.recall;

import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import dev.langchain4j.store.embedding.EmbeddingSearchRequest;
import dev.langchain4j.store.embedding.EmbeddingSearchResult;
import me.maxt.rag.web.service.EmbeddingStoreManager;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DenseRecallStrategyTest {

    private final EmbeddingStoreManager storeManager = mock(EmbeddingStoreManager.class);
    private final EmbeddingModel embeddingModel = mock(EmbeddingModel.class);
    private final DenseRecallStrategy strategy = new DenseRecallStrategy(storeManager, embeddingModel);

    @Test
    void shouldReturnName() {
        assertThat(strategy.name()).isEqualTo("dense");
    }

    @Test
    @SuppressWarnings("unchecked")
    void shouldRecallUsingVectorSearch() {
        Embedding emb = mock(Embedding.class);
        when(embeddingModel.embed("test query")).thenReturn(mock(dev.langchain4j.model.output.Response.class));
        when(embeddingModel.embed("test query").content()).thenReturn(emb);

        EmbeddingMatch<TextSegment> match = mock(EmbeddingMatch.class);
        TextSegment seg = TextSegment.from("result");
        when(match.embedded()).thenReturn(seg);
        when(match.score()).thenReturn(0.9);

        EmbeddingSearchResult<TextSegment> result = mock(EmbeddingSearchResult.class);
        when(result.matches()).thenReturn(List.of(match));
        when(storeManager.search(any(EmbeddingSearchRequest.class))).thenReturn(result);

        List<EmbeddingMatch<TextSegment>> matches = strategy.recall("test query", 5);

        assertThat(matches).hasSize(1);
        assertThat(matches.get(0).embedded().text()).isEqualTo("result");
    }
}
