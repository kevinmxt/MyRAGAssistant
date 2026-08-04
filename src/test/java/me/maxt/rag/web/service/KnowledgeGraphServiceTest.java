package me.maxt.rag.web.service;

import me.maxt.rag.web.config.RecallConfig;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class KnowledgeGraphServiceTest {

    @Test
    void shouldReturnNotBuiltWhenNoGraphExists() {
        RecallConfig config = mock(RecallConfig.class);
        when(config.getLightRagWorkingDir()).thenReturn("./data/kg_test");
        when(config.getLightRagPythonPath()).thenReturn("python");
        when(config.getLightRagEmbeddingModelPath()).thenReturn("models/bge");
        when(config.getLightRagQueryMode()).thenReturn("hybrid");

        KnowledgeGraphService service = new KnowledgeGraphService(config, null);
        Map<String, Object> status = service.getStatus();

        assertThat(status.get("built")).isEqualTo(false);
        assertThat(status.get("indexedDocuments")).isNotNull();
    }
}
