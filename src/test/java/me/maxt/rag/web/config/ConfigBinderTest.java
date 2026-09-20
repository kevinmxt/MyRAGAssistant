package me.maxt.rag.web.config;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;

class ConfigBinderTest {

    /** 测试用配置节：组件命名沿用 getter 风格（与生产节 record 约定一致） */
    record SampleSection(
            @Key(json = "llm.apiKey", env = "RAG_LLM_API_KEY", def = "demo") String getApiKey,
            @Key(json = "llm.maxTokens", env = "RAG_LLM_MAX_TOKENS", def = "4096") int getMaxTokens,
            @Key(json = "llm.temperature", env = "RAG_LLM_TEMPERATURE", def = "0.7") double getTemperature,
            @Key(json = "llm.enabled", env = "RAG_LLM_ENABLED", def = "true") boolean isEnabled,
            @Key(json = "llm.modes", env = "RAG_LLM_MODES", def = "dense,sparse") List<String> getModes,
            @Key(json = "document.chunking.mode", env = "RAG_CHUNKING_MODE", def = "auto") String getChunkingMode) {
    }

    private static final Function<String, String> NO_ENV = name -> null;

    @Test
    void shouldBindAllSupportedTypesFromDefaults() {
        SampleSection section = ConfigBinder.bind(SampleSection.class, Map.of(), NO_ENV);

        assertThat(section.getApiKey()).isEqualTo("demo");
        assertThat(section.getMaxTokens()).isEqualTo(4096);
        assertThat(section.getTemperature()).isEqualTo(0.7);
        assertThat(section.isEnabled()).isTrue();
        assertThat(section.getModes()).containsExactly("dense", "sparse");
        assertThat(section.getChunkingMode()).isEqualTo("auto");
    }

    @Test
    void shouldOverrideDefaultsFromConfigJsonIncludingNestedPaths() {
        Map<String, Object> fileJson = Map.of(
                "llm", Map.of("apiKey", "sk-from-file", "maxTokens", 8192,
                        "temperature", 0.3, "enabled", false),
                "document", Map.of("chunking", Map.of("mode", "semantic")));

        SampleSection section = ConfigBinder.bind(SampleSection.class, fileJson, NO_ENV);

        assertThat(section.getApiKey()).isEqualTo("sk-from-file");
        assertThat(section.getMaxTokens()).isEqualTo(8192);
        assertThat(section.getTemperature()).isEqualTo(0.3);
        assertThat(section.isEnabled()).isFalse();
        assertThat(section.getChunkingMode()).isEqualTo("semantic");
    }

    @Test
    void shouldOverrideConfigJsonFromEnvironment() {
        Map<String, Object> fileJson = Map.of("llm", Map.of("apiKey", "sk-from-file"));
        Function<String, String> env = name -> switch (name) {
            case "RAG_LLM_API_KEY" -> "sk-from-env";
            case "RAG_LLM_MAX_TOKENS" -> "1024";
            case "RAG_LLM_TEMPERATURE" -> "0.1";
            case "RAG_LLM_ENABLED" -> "false";
            case "RAG_LLM_MODES" -> "graph,dense";
            default -> null;
        };

        SampleSection section = ConfigBinder.bind(SampleSection.class, fileJson, env);

        assertThat(section.getApiKey()).isEqualTo("sk-from-env");
        assertThat(section.getMaxTokens()).isEqualTo(1024);
        assertThat(section.getTemperature()).isEqualTo(0.1);
        assertThat(section.isEnabled()).isFalse();
        assertThat(section.getModes()).containsExactly("graph", "dense");
    }

    @Test
    void shouldBindListFromJsonArrayAndCommaSeparatedString() {
        Map<String, Object> fileJson = Map.of("llm", Map.of(
                "modes", java.util.Arrays.asList("a", "b", "c")));
        assertThat(ConfigBinder.bind(SampleSection.class, fileJson, NO_ENV).getModes())
                .containsExactly("a", "b", "c");

        Map<String, Object> commaJson = Map.of("llm", Map.of("modes", "a, b"));
        assertThat(ConfigBinder.bind(SampleSection.class, commaJson, NO_ENV).getModes())
                .containsExactly("a", " b");
    }

    @Test
    void shouldFallbackToDefaultOnInvalidEnvNumber() {
        Function<String, String> env = name -> "RAG_LLM_MAX_TOKENS".equals(name) ? "abc" : null;

        SampleSection section = ConfigBinder.bind(SampleSection.class, Map.of(), env);

        assertThat(section.getMaxTokens()).isEqualTo(4096);
    }

    @Test
    void shouldFallbackToDefaultOnWrongJsonTypeOrMissingKey() {
        Map<String, Object> fileJson = Map.of("llm", Map.of("apiKey", 12345));

        SampleSection section = ConfigBinder.bind(SampleSection.class, fileJson, NO_ENV);

        assertThat(section.getApiKey()).isEqualTo("demo");
    }
}
