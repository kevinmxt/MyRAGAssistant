package me.maxt.rag.web.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class AppConfigTest {

    @TempDir
    Path tempDir;

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
        assertThat(config.getMilvusHost()).isEqualTo("localhost");
        assertThat(config.getMilvusPort()).isEqualTo(19530);
        assertThat(config.getMilvusCollectionName()).isEqualTo("rag_knowledge_base");
        assertThat(config.getMilvusDimension()).isEqualTo(512);
    }

    @Test
    void shouldImplementAllConfigInterfaces() {
        AppConfig config = new AppConfig();
        assertThat(config).isInstanceOf(LlmConfig.class);
        assertThat(config).isInstanceOf(RetrievalConfig.class);
        assertThat(config).isInstanceOf(DocumentConfig.class);
        assertThat(config).isInstanceOf(ServerConfig.class);
        assertThat(config).isInstanceOf(QueryEnhancementConfig.class);
        assertThat(config).isInstanceOf(MilvusConfig.class);
        assertThat(config).isInstanceOf(RecallConfig.class);
        assertThat(config).isInstanceOf(RerankConfig.class);
        assertThat(config).isInstanceOf(EnvCheckConfig.class);
        assertThat(config).isInstanceOf(ModelConfig.class);
    }

    @Test
    void shouldLoadFromConfigJson() throws Exception {
        Path configFile = tempDir.resolve("config.json");
        ObjectMapper mapper = new ObjectMapper();
        Map<String, Object> json = Map.of(
                "llm", Map.of("modelName", "test-model", "apiKey", "sk-test"),
                "server", Map.of("port", 9090),
                "retrieval", Map.of("maxResults", 5),
                "document", Map.of("chunkSize", 500, "supportedExtensions", ".txt,.md")
        );
        mapper.writeValue(configFile.toFile(), json);

        AppConfig config = AppConfig.load(configFile, name -> null);

        assertThat(config.getApiKey()).isEqualTo("sk-test");
        assertThat(config.getModelName()).isEqualTo("test-model");
        assertThat(config.getPort()).isEqualTo(9090);
        assertThat(config.getMaxResults()).isEqualTo(5);
        assertThat(config.getChunkSize()).isEqualTo(500);
        assertThat(config.getSupportedFileExtensions()).contains(".txt", ".md");
        // 未覆盖的键回落默认值
        assertThat(config.getTemperature()).isEqualTo(0.7);
    }

    @Test
    void shouldFallBackToDefaultsWhenConfigFileMissing() {
        AppConfig config = AppConfig.load(tempDir.resolve("nonexistent.json"), name -> null);

        assertThat(config.getPort()).isEqualTo(8080);
        assertThat(config.getModelName()).isEqualTo("deepseek-v4-flash");
    }

    @Test
    void shouldBindRecallModesFromCommaSeparatedString() throws Exception {
        Path configFile = tempDir.resolve("config.json");
        new ObjectMapper().writeValue(configFile.toFile(), Map.of(
                "multiRecall", Map.of("modes", "dense,graph")));

        AppConfig config = AppConfig.load(configFile, name -> null);

        assertThat(config.getRecallModes()).containsExactly("dense", "graph");
    }

    @Test
    void shouldBindSameCollectionNameInMilvusAndRecallSections() throws Exception {
        Path configFile = tempDir.resolve("config.json");
        new ObjectMapper().writeValue(configFile.toFile(), Map.of(
                "milvus", Map.of("collectionName", "my_collection")));
        @SuppressWarnings("unchecked")
        Map<String, Object> fileConfig = new ObjectMapper().readValue(configFile.toFile(), Map.class);

        String fromMilvus = ConfigBinder.bind(MilvusSettings.class, fileConfig, name -> null)
                .getMilvusCollectionName();
        String fromRecall = ConfigBinder.bind(RecallSettings.class, fileConfig, name -> null)
                .getMilvusCollectionName();

        assertThat(fromMilvus).isEqualTo(fromRecall).isEqualTo("my_collection");
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

    @Test
    void shouldHaveQueryEnhancementDefaults() {
        AppConfig config = new AppConfig();
        assertThat(config.isQueryEnhancementEnabled()).isTrue();
        assertThat(config.getDefaultEnhancementMode()).isEqualTo("auto");
        assertThat(config.getRrfK()).isEqualTo(60);
        assertThat(config.getHydeMaxTokens()).isEqualTo(200);
    }

    @Test
    void shouldHaveMilvusDefaults() {
        AppConfig config = new AppConfig();
        assertThat(config.getMilvusHost()).isEqualTo("localhost");
        assertThat(config.getMilvusPort()).isEqualTo(19530);
        assertThat(config.getMilvusCollectionName()).isEqualTo("rag_knowledge_base");
        assertThat(config.getMilvusDimension()).isEqualTo(512);
    }

    @Test
    void shouldHaveDefaultEnvCheckConfig() {
        AppConfig config = new AppConfig();
        assertThat(config.isEnvCheckEnabled()).isTrue();
        assertThat(config.isAutoInstallEnabled()).isFalse();
        assertThat(config.getEnvCheckTimeoutSeconds()).isEqualTo(15);
        assertThat(config.getProbeTimeoutSeconds()).isEqualTo(5);
    }

    @Test
    void shouldHaveDefaultRerankConfig() {
        AppConfig config = new AppConfig();
        assertThat(config.getRerankModelPath()).isEqualTo("models/bge-reranker-v2-m3");
        assertThat(config.getRerankExpansionFactor()).isEqualTo(3);
        assertThat(config.getRerankTopK()).isEqualTo(5);
    }

    @Test
    void shouldHaveDefaultModelConfig() {
        AppConfig config = new AppConfig();
        assertThat(config.isAutoDownload()).isTrue();
        assertThat(config.getDownloadMirror()).isEqualTo("https://hf-mirror.com");
    }
}
