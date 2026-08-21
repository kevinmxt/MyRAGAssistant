package me.maxt.rag.web.evaluation;

import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.ChatResponseMetadata;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.output.Response;
import dev.langchain4j.model.output.TokenUsage;
import dev.langchain4j.store.embedding.inmemory.InMemoryEmbeddingStore;
import me.maxt.rag.web.config.AppConfig;
import me.maxt.rag.web.service.EmbeddingStoreManager;
import me.maxt.rag.web.service.RAGService;
import me.maxt.rag.web.service.evaluation.*;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 评估测试入口。
 *
 * <p>命令：
 * <ul>
 *   <li>mvn test -P evaluation</li>
 *   <li>mvn test -P evaluation -Dformat=markdown</li>
 *   <li>mvn test -P evaluation -DupdateBaseline=true</li>
 *   <li>mvn test -P evaluation -DskipAnswerQuality=true</li>
 * </ul>
 */
class EvaluationTest {

    private static final Path SRC_RESOURCES = Paths.get("src/test/resources/evaluation");
    private static final Path TARGET_DIR = Paths.get("target/evaluation");

    @Test
    void runEvaluation() {
        String formatFilter = System.getProperty("evaluation.format");
        boolean updateBaseline = "true".equals(System.getProperty("evaluation.updateBaseline"));
        boolean skipAnswerQuality = "true".equals(System.getProperty("evaluation.skipAnswerQuality"));

        // 构建最小依赖（mock 系统边界）
        AppConfig appConfig = new AppConfig();

        // Mock embedding model
        EmbeddingModel embeddingModel = mock(EmbeddingModel.class);
        Response<Embedding> embedResp = mock(Response.class);
        when(embedResp.content()).thenReturn(Embedding.from(new float[]{0.5f, 0.5f, 0.5f}));
        when(embeddingModel.embed(any(String.class))).thenReturn(embedResp);

        // Mock chat model
        ChatModel chatModel = mock(ChatModel.class);
        ChatResponseMetadata metadata = ChatResponseMetadata.builder()
                .tokenUsage(new TokenUsage(10, 10)).build();
        ChatResponse chatResponse = ChatResponse.builder()
                .aiMessage(dev.langchain4j.data.message.AiMessage.from("stub answer"))
                .metadata(metadata).build();
        when(chatModel.chat(any(ChatRequest.class))).thenReturn(chatResponse);

        // 共享组件（无状态，可在各格式间复用）
        DatasetLoader datasetLoader = new DatasetLoader();
        RetrievalEvaluator retrievalEvaluator = new RetrievalEvaluator(appConfig);
        AnswerQualityEvaluator answerQualityEvaluator = new AnswerQualityEvaluator(chatModel);
        BaselineManager baselineManager = new BaselineManager(appConfig);

        List<String> formats = appConfig.getEvaluationFormats();
        if (formatFilter != null && !formatFilter.isBlank()) {
            formats = List.of(formatFilter);
        }

        for (String format : formats) {
            // 每个格式使用独立的 InMemoryEmbeddingStore，
            // 避免上一个格式入库的文档残留导致召回结果跨格式累积
            dev.langchain4j.store.embedding.inmemory.InMemoryEmbeddingStore<dev.langchain4j.data.segment.TextSegment> store =
                    new dev.langchain4j.store.embedding.inmemory.InMemoryEmbeddingStore<>();
            EmbeddingStoreManager storeManager = new EmbeddingStoreManager(() -> store);
            RAGService ragService = new RAGService(appConfig, storeManager, embeddingModel, chatModel);
            KnowledgeBaseSeeder seeder = new KnowledgeBaseSeeder(storeManager, embeddingModel, appConfig, null);

            EvaluationPipeline pipeline = new EvaluationPipeline(appConfig, datasetLoader, seeder,
                    retrievalEvaluator, answerQualityEvaluator, baselineManager, ragService);

            EvaluationReport report = pipeline.run(format, SRC_RESOURCES, TARGET_DIR,
                    updateBaseline, skipAnswerQuality);
            if (report != null) {
                System.out.println("[" + format + "] recallAtK=" +
                        report.metrics.get("recallAtK").score);
            }
        }
    }
}
