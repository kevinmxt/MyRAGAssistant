package me.maxt.rag.web.service.vector.recall;

import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import me.maxt.rag.web.service.KnowledgeGraphService;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class GraphRecallStrategyTest {

    @Test
    void shouldReturnName() {
        KnowledgeGraphService kgService = mock(KnowledgeGraphService.class);
        LightRagBridge bridge = mock(LightRagBridge.class);
        GraphRecallStrategy strategy = new GraphRecallStrategy(kgService, bridge, "hybrid");
        assertThat(strategy.name()).isEqualTo("graph");
    }

    @Test
    void shouldReturnEmptyWhenGraphNotBuilt() {
        KnowledgeGraphService kgService = mock(KnowledgeGraphService.class);
        when(kgService.isBuilt()).thenReturn(false);
        LightRagBridge bridge = mock(LightRagBridge.class);

        GraphRecallStrategy strategy = new GraphRecallStrategy(kgService, bridge, "hybrid");
        List<EmbeddingMatch<TextSegment>> result = strategy.recall("query", 5);

        assertThat(result).isEmpty();
    }

    @Test
    void shouldReturnResultsWhenGraphReady() {
        KnowledgeGraphService kgService = mock(KnowledgeGraphService.class);
        when(kgService.isBuilt()).thenReturn(true);
        LightRagBridge bridge = mock(LightRagBridge.class);
        when(bridge.query(anyString(), anyString())).thenReturn(List.of("result1", "result2"));

        GraphRecallStrategy strategy = new GraphRecallStrategy(kgService, bridge, "hybrid");
        List<EmbeddingMatch<TextSegment>> result = strategy.recall("query", 5);

        assertThat(result).hasSize(2);
        assertThat(result.get(0).embedded().text()).isEqualTo("result1");
    }

    @Test
    void shouldReturnEmptyOnPythonFailure() {
        KnowledgeGraphService kgService = mock(KnowledgeGraphService.class);
        when(kgService.isBuilt()).thenReturn(true);
        LightRagBridge bridge = mock(LightRagBridge.class);
        when(bridge.query(anyString(), anyString())).thenThrow(new RuntimeException("python error"));

        GraphRecallStrategy strategy = new GraphRecallStrategy(kgService, bridge, "hybrid");
        List<EmbeddingMatch<TextSegment>> result = strategy.recall("query", 5);

        assertThat(result).isEmpty();
    }
}
