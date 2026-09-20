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
        assertThat(config.server().getPort()).isEqualTo(8080);
        assertThat(config.document().getChunkSize()).isEqualTo(300);
        assertThat(config.document().getChunkOverlap()).isEqualTo(0);
        assertThat(config.retrieval().getMaxResults()).isEqualTo(3);
        assertThat(config.retrieval().getMinScore()).isEqualTo(0.5);
        assertThat(config.retrieval().getMemorySize()).isEqualTo(10);
        assertThat(config.llm().getTemperature()).isEqualTo(0.7);
        assertThat(config.llm().getMaxTokens()).isEqualTo(4096);
        assertThat(config.llm().getTimeoutSeconds()).isEqualTo(120);
        assertThat(config.llm().getModelName()).isEqualTo("deepseek-v4-flash");
        assertThat(config.document().getDocumentDir()).isEqualTo("./documents");
        assertThat(config.server().getStoreFilePath()).isEqualTo("./data/embedding-store.json");
        assertThat(config.document().getSupportedFileExtensions()).contains(".pdf", ".txt", ".docx");
        assertThat(config.milvus().getMilvusHost()).isEqualTo("localhost");
        assertThat(config.milvus().getMilvusPort()).isEqualTo(19530);
        assertThat(config.milvus().getMilvusCollectionName()).isEqualTo("rag_knowledge_base");
        assertThat(config.milvus().getMilvusDimension()).isEqualTo(512);
    }

    @Test
    void sectionsShouldImplementConfigInterfaces() {
        AppConfig config = new AppConfig();
        assertThat(config.llm()).isInstanceOf(LlmConfig.class);
        assertThat(config.retrieval()).isInstanceOf(RetrievalConfig.class);
        assertThat(config.document()).isInstanceOf(DocumentConfig.class);
        assertThat(config.document()).isInstanceOf(ChunkingConfig.class);
        assertThat(config.server()).isInstanceOf(ServerConfig.class);
        assertThat(config.queryEnhancement()).isInstanceOf(QueryEnhancementConfig.class);
        assertThat(config.milvus()).isInstanceOf(MilvusConfig.class);
        assertThat(config.recall()).isInstanceOf(RecallConfig.class);
        assertThat(config.rerank()).isInstanceOf(RerankConfig.class);
        assertThat(config.evaluation()).isInstanceOf(EvaluationConfig.class);
        assertThat(config.envCheck()).isInstanceOf(EnvCheckConfig.class);
        assertThat(config.model()).isInstanceOf(ModelConfig.class);
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

        assertThat(config.llm().getApiKey()).isEqualTo("sk-test");
        assertThat(config.llm().getModelName()).isEqualTo("test-model");
        assertThat(config.server().getPort()).isEqualTo(9090);
        assertThat(config.retrieval().getMaxResults()).isEqualTo(5);
        assertThat(config.document().getChunkSize()).isEqualTo(500);
        assertThat(config.document().getSupportedFileExtensions()).contains(".txt", ".md");
        // 未覆盖的键回落默认值
        assertThat(config.llm().getTemperature()).isEqualTo(0.7);
    }

    @Test
    void shouldSupportRetrievalConfigInterface() {
        RetrievalConfig config = new AppConfig().retrieval();
        assertThat(config.getMaxResults()).isEqualTo(3);
        assertThat(config.getMinScore()).isEqualTo(0.5);
        assertThat(config.getMemorySize()).isEqualTo(10);
    }

    @Test
    void shouldLoadWithoutConfigFile() {
        AppConfig config = AppConfig.load();
        assertThat(config).isNotNull();
        assertThat(config.server().getPort()).isEqualTo(8080);
    }

    @Test
    void shouldHaveQueryEnhancementDefaults() {
        AppConfig config = new AppConfig();
        assertThat(config.queryEnhancement().isQueryEnhancementEnabled()).isTrue();
        assertThat(config.queryEnhancement().getDefaultEnhancementMode()).isEqualTo("auto");
        assertThat(config.queryEnhancement().getRrfK()).isEqualTo(60);
        assertThat(config.queryEnhancement().getHydeMaxTokens()).isEqualTo(200);
    }

    @Test
    void shouldHaveMilvusDefaults() {
        AppConfig config = new AppConfig();
        assertThat(config.milvus().getMilvusHost()).isEqualTo("localhost");
        assertThat(config.milvus().getMilvusPort()).isEqualTo(19530);
        assertThat(config.milvus().getMilvusCollectionName()).isEqualTo("rag_knowledge_base");
        assertThat(config.milvus().getMilvusDimension()).isEqualTo(512);
    }

    @Test
    void shouldHaveDefaultEnvCheckConfig() {
        AppConfig config = new AppConfig();
        assertThat(config.envCheck().isEnvCheckEnabled()).isTrue();
        assertThat(config.envCheck().isAutoInstallEnabled()).isFalse();
        assertThat(config.envCheck().getEnvCheckTimeoutSeconds()).isEqualTo(15);
        assertThat(config.envCheck().getProbeTimeoutSeconds()).isEqualTo(5);
    }

    @Test
    void shouldHaveDefaultRerankConfig() {
        AppConfig config = new AppConfig();
        assertThat(config.rerank().getRerankModelPath()).isEqualTo("models/bge-reranker-v2-m3");
        assertThat(config.rerank().getRerankExpansionFactor()).isEqualTo(3);
        assertThat(config.rerank().getRerankTopK()).isEqualTo(5);
    }

    @Test
    void shouldHaveDefaultModelConfig() {
        AppConfig config = new AppConfig();
        assertThat(config.model().isAutoDownload()).isTrue();
        assertThat(config.model().getDownloadMirror()).isEqualTo("https://hf-mirror.com");
    }

    @Test
    void shouldFallBackToDefaultsWhenConfigFileMissing() {
        AppConfig config = AppConfig.load(tempDir.resolve("nonexistent.json"), name -> null);

        assertThat(config.server().getPort()).isEqualTo(8080);
        assertThat(config.llm().getModelName()).isEqualTo("deepseek-v4-flash");
    }

    @Test
    void shouldBindRecallModesFromCommaSeparatedString() throws Exception {
        Path configFile = tempDir.resolve("config.json");
        new ObjectMapper().writeValue(configFile.toFile(), Map.of(
                "multiRecall", Map.of("modes", "dense,graph")));

        AppConfig config = AppConfig.load(configFile, name -> null);

        assertThat(config.recall().getRecallModes()).containsExactly("dense", "graph");
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
}
