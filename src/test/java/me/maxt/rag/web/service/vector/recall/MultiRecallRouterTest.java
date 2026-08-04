package me.maxt.rag.web.service.vector.recall;

import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import me.maxt.rag.web.config.RecallConfig;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class MultiRecallRouterTest {

    @Test
    @SuppressWarnings("unchecked")
    void shouldRouteToActiveStrategies() {
        RecallConfig config = mock(RecallConfig.class);
        when(config.getRecallModes()).thenReturn(List.of("dense", "sparse"));
        when(config.getRecallTopK()).thenReturn(5);
        when(config.getRecallRrfK()).thenReturn(60);

        RecallStrategy denseStrategy = mock(RecallStrategy.class);
        when(denseStrategy.name()).thenReturn("dense");
        TextSegment denseSeg = TextSegment.from("dense result");
        EmbeddingMatch<TextSegment> denseMatch = mock(EmbeddingMatch.class);
        when(denseMatch.embedded()).thenReturn(denseSeg);
        when(denseMatch.score()).thenReturn(0.9);
        when(denseStrategy.recall(eq("query"), anyInt()))
                .thenReturn(List.of(denseMatch));

        RecallStrategy sparseStrategy = mock(RecallStrategy.class);
        when(sparseStrategy.name()).thenReturn("sparse");
        TextSegment sparseSeg = TextSegment.from("sparse result");
        EmbeddingMatch<TextSegment> sparseMatch = mock(EmbeddingMatch.class);
        when(sparseMatch.embedded()).thenReturn(sparseSeg);
        when(sparseMatch.score()).thenReturn(0.8);
        when(sparseStrategy.recall(eq("query"), anyInt()))
                .thenReturn(List.of(sparseMatch));

        Map<String, RecallStrategy> registry = Map.of(
                "dense", denseStrategy,
                "sparse", sparseStrategy
        );

        MultiRecallRouter router = new MultiRecallRouter(config, registry);
        List<EmbeddingMatch<TextSegment>> result = router.recall("query", List.of("dense", "sparse"));

        assertThat(result).hasSize(2);
        verify(denseStrategy).recall(eq("query"), anyInt());
        verify(sparseStrategy).recall(eq("query"), anyInt());
    }

    @Test
    void shouldSkipUnknownStrategy() {
        RecallConfig config = mock(RecallConfig.class);
        when(config.getRecallModes()).thenReturn(List.of("dense"));
        when(config.getRecallTopK()).thenReturn(5);
        when(config.getRecallRrfK()).thenReturn(60);

        RecallStrategy denseStrategy = mock(RecallStrategy.class);
        when(denseStrategy.name()).thenReturn("dense");
        TextSegment seg = TextSegment.from("result");
        EmbeddingMatch<TextSegment> match = mock(EmbeddingMatch.class);
        when(match.embedded()).thenReturn(seg);
        when(match.score()).thenReturn(0.9);
        when(denseStrategy.recall(eq("query"), anyInt())).thenReturn(List.of(match));

        MultiRecallRouter router = new MultiRecallRouter(config,
                Map.of("dense", denseStrategy));

        // "graph" 未注册，应被忽略
        List<EmbeddingMatch<TextSegment>> result = router.recall("query", List.of("dense", "graph"));

        assertThat(result).hasSize(1);
        verify(denseStrategy).recall(eq("query"), anyInt());
    }

    @Test
    void shouldHandleStrategyFailureGracefully() {
        RecallConfig config = mock(RecallConfig.class);
        when(config.getRecallModes()).thenReturn(List.of("dense", "sparse"));
        when(config.getRecallTopK()).thenReturn(5);
        when(config.getRecallRrfK()).thenReturn(60);

        RecallStrategy denseStrategy = mock(RecallStrategy.class);
        when(denseStrategy.name()).thenReturn("dense");
        TextSegment seg = TextSegment.from("ok");
        EmbeddingMatch<TextSegment> match = mock(EmbeddingMatch.class);
        when(match.embedded()).thenReturn(seg);
        when(match.score()).thenReturn(0.9);
        when(denseStrategy.recall(eq("query"), anyInt())).thenReturn(List.of(match));

        RecallStrategy broken = mock(RecallStrategy.class);
        when(broken.name()).thenReturn("sparse");
        when(broken.recall(eq("query"), anyInt())).thenThrow(new RuntimeException("boom"));

        Map<String, RecallStrategy> registry = Map.of(
                "dense", denseStrategy,
                "sparse", broken
        );

        MultiRecallRouter router = new MultiRecallRouter(config, registry);

        // sparse 失败不影响 dense
        List<EmbeddingMatch<TextSegment>> result = router.recall("query", List.of("dense", "sparse"));

        assertThat(result).hasSize(1);
        assertThat(result.get(0).embedded().text()).isEqualTo("ok");
    }

    @Test
    void shouldDefaultToDenseWhenModesEmpty() {
        RecallConfig config = mock(RecallConfig.class);
        when(config.getRecallModes()).thenReturn(List.of("dense"));
        when(config.getRecallTopK()).thenReturn(5);
        when(config.getRecallRrfK()).thenReturn(60);

        RecallStrategy denseStrategy = mock(RecallStrategy.class);
        when(denseStrategy.name()).thenReturn("dense");
        TextSegment seg = TextSegment.from("result");
        EmbeddingMatch<TextSegment> match = mock(EmbeddingMatch.class);
        when(match.embedded()).thenReturn(seg);
        when(match.score()).thenReturn(0.9);
        when(denseStrategy.recall(eq("query"), anyInt())).thenReturn(List.of(match));

        MultiRecallRouter router = new MultiRecallRouter(config,
                Map.of("dense", denseStrategy));

        List<EmbeddingMatch<TextSegment>> result = router.recall("query", List.of());

        assertThat(result).hasSize(1);
        verify(denseStrategy).recall(eq("query"), anyInt());
    }
}
