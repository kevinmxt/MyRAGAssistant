package me.maxt.rag.web.service.vector.rerank;

import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import me.maxt.rag.web.config.RerankConfig;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class CrossEncoderRerankerTest {

    @Test
    @SuppressWarnings("unchecked")
    void shouldDegradeWhenModelNotFound() {
        RerankConfig config = mock(RerankConfig.class);
        when(config.getRerankModelPath()).thenReturn("./nonexistent/path");
        when(config.isRerankAutoDownload()).thenReturn(false);
        when(config.getRerankExpansionFactor()).thenReturn(3);
        when(config.getRerankTopK()).thenReturn(5);

        CrossEncoderReranker reranker = new CrossEncoderReranker(config);
        assertThat(reranker.isAvailable()).isFalse();
        assertThat(reranker.name()).isEqualTo("cross-encoder");
    }

    @Test
    @SuppressWarnings("unchecked")
    void shouldReturnCandidatesAsIsWhenNotAvailable() {
        RerankConfig config = mock(RerankConfig.class);
        when(config.getRerankModelPath()).thenReturn("./nonexistent/path");
        when(config.isRerankAutoDownload()).thenReturn(false);
        CrossEncoderReranker reranker = new CrossEncoderReranker(config);

        TextSegment seg1 = TextSegment.from("candidate 1");
        TextSegment seg2 = TextSegment.from("candidate 2");
        TextSegment seg3 = TextSegment.from("candidate 3");

        EmbeddingMatch<TextSegment> m1 = mock(EmbeddingMatch.class);
        when(m1.embedded()).thenReturn(seg1);
        when(m1.score()).thenReturn(0.9);
        EmbeddingMatch<TextSegment> m2 = mock(EmbeddingMatch.class);
        when(m2.embedded()).thenReturn(seg2);
        when(m2.score()).thenReturn(0.8);
        EmbeddingMatch<TextSegment> m3 = mock(EmbeddingMatch.class);
        when(m3.embedded()).thenReturn(seg3);
        when(m3.score()).thenReturn(0.7);

        var candidates = List.of(m1, m2, m3);
        var result = reranker.rerank("test query", candidates, 2);

        assertThat(result).hasSize(2);
        assertThat(result.get(0).embedded().text()).isEqualTo("candidate 1");
        assertThat(result.get(1).embedded().text()).isEqualTo("candidate 2");
    }

    @Test
    @SuppressWarnings("unchecked")
    void shouldHandleEmptyCandidates() {
        RerankConfig config = mock(RerankConfig.class);
        when(config.getRerankModelPath()).thenReturn("./nonexistent/path");
        when(config.isRerankAutoDownload()).thenReturn(false);
        CrossEncoderReranker reranker = new CrossEncoderReranker(config);

        var result = reranker.rerank("test query", List.of(), 5);
        assertThat(result).isEmpty();
    }
}
