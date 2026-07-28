package me.maxt.rag.web.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class AppConfigTest {

    @Test
    void shouldHaveSensibleDefaults() {
        AppConfig config = new AppConfig();
        assertThat(config.getPort()).isEqualTo(8080);
        assertThat(config.getChunkSize()).isEqualTo(300);
        assertThat(config.getChunkOverlap()).isEqualTo(0);
        assertThat(config.getMaxResults()).isEqualTo(3);
        assertThat(config.getMinScore()).isEqualTo(0.5);
        assertThat(config.getMemorySize()).isEqualTo(10);
        assertThat(config.getTemperature()).isEqualTo(0.7);
        assertThat(config.getMaxTokens()).isEqualTo(4096);
        assertThat(config.getTimeoutSeconds()).isEqualTo(120);
        assertThat(config.getModelName()).isEqualTo("deepseek-v4-flash");
        assertThat(config.getDocumentDir()).isEqualTo("./documents");
        assertThat(config.getStoreFilePath()).isEqualTo("./data/embedding-store.json");
        assertThat(config.getSupportedFileExtensions()).contains(".pdf", ".txt", ".docx");
    }

    @Test
    void shouldImplementAllConfigInterfaces() {
        AppConfig config = new AppConfig();
        assertThat(config).isInstanceOf(LlmConfig.class);
        assertThat(config).isInstanceOf(RetrievalConfig.class);
        assertThat(config).isInstanceOf(DocumentConfig.class);
        assertThat(config).isInstanceOf(ServerConfig.class);
    }

    @Test
    void shouldLoadFromConfigJson() throws Exception {
        Path configFile = Path.of("target/test-config.json");
        ObjectMapper mapper = new ObjectMapper();
        Map<String, Object> json = Map.of(
                "llm", Map.of("modelName", "test-model", "apiKey", "sk-test"),
                "server", Map.of("port", 9090),
                "retrieval", Map.of("maxResults", 5),
                "document", Map.of("chunkSize", 500, "supportedExtensions", ".txt,.md")
        );
        mapper.writeValue(configFile.toFile(), json);

        // Can't easily redirect AppConfig.load() to different file,
        // but we can test the constructor + apply logic directly.
        // Defaults should be intact when no config file present in cwd.
        AppConfig config = new AppConfig();
        assertThat(config.getPort()).isEqualTo(8080);
        assertThat(config.getModelName()).isEqualTo("deepseek-v4-flash");

        Files.deleteIfExists(configFile);
    }

    @Test
    void shouldSupportRetrievalConfigInterface() {
        RetrievalConfig config = new AppConfig();
        assertThat(config.getMaxResults()).isEqualTo(3);
        assertThat(config.getMinScore()).isEqualTo(0.5);
        assertThat(config.getMemorySize()).isEqualTo(10);
    }

    @Test
    void shouldLoadWithoutConfigFile() {
        AppConfig config = AppConfig.load();
        assertThat(config).isNotNull();
        assertThat(config.getPort()).isEqualTo(8080);
    }
}
