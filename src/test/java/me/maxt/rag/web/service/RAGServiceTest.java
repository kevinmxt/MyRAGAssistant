package me.maxt.rag.web.service;

import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.ChatResponseMetadata;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.output.Response;
import dev.langchain4j.model.output.TokenUsage;
import dev.langchain4j.store.embedding.inmemory.InMemoryEmbeddingStore;
import me.maxt.rag.web.config.QueryEnhancementConfig;
import me.maxt.rag.web.config.RetrievalConfig;
import me.maxt.rag.web.service.vector.QueryEnhancementRouter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RAGServiceTest {

    private EmbeddingStoreManager storeManager;
    private RetrievalConfig config;
    private ChatModel chatModel;

    @BeforeEach
    void setUp() {
        storeManager = new EmbeddingStoreManager(new InMemoryEmbeddingStore<>());
        config = mock(RetrievalConfig.class);
        when(config.getMaxResults()).thenReturn(3);
        when(config.getMinScore()).thenReturn(0.5);
        when(config.getMemorySize()).thenReturn(10);

        // Build a complete ChatModel mock that AiServices can use
        chatModel = mock(ChatModel.class);
        ChatResponseMetadata metadata = ChatResponseMetadata.builder()
                .tokenUsage(new TokenUsage(10, 10))
                .build();
        ChatResponse chatResponse = ChatResponse.builder()
                .aiMessage(AiMessage.from("stub answer"))
                .metadata(metadata)
                .build();
        when(chatModel.chat(any(ChatRequest.class))).thenReturn(chatResponse);
    }

    @Test
    @SuppressWarnings("unchecked")
    void shouldRetrieveCorrectSources() {
        // Add documents to store
        float[] v1 = {0.5f, 0.5f, 0.5f};
        TextSegment s1 = TextSegment.from("Paris is the capital of France.");
        s1.metadata().put("file_name", "facts.txt");
        storeManager.add(Embedding.from(v1), s1);

        float[] v2 = {-0.5f, -0.5f, -0.5f};
        TextSegment s2 = TextSegment.from("London is the capital of UK.");
        s2.metadata().put("file_name", "geo.txt");
        s2.metadata().put("absolute_directory_path", "/docs");
        storeManager.add(Embedding.from(v2), s2);

        // Mock embedding model to return the query vector that matches v1 best
        EmbeddingModel embeddingModel = mock(EmbeddingModel.class);
        Response<Embedding> embedResp = mock(Response.class);
        when(embedResp.content()).thenReturn(Embedding.from(v1));
        when(embeddingModel.embed(anyString())).thenReturn(embedResp);


        RAGService service = new RAGService(config, storeManager, embeddingModel, chatModel);
        RAGService.AnswerWithSources result = service.answerWithSources("What is the capital of France?");

        assertThat(result.sources).hasSize(1);
        assertThat(result.sources.get(0).text).isEqualTo("Paris is the capital of France.");
        assertThat(result.sources.get(0).score).isGreaterThan(0.9);
    }

    @Test
    @SuppressWarnings("unchecked")
    void shouldReturnEmptySourcesWhenNoRelevantDocs() {
        EmbeddingModel embeddingModel = mock(EmbeddingModel.class);
        Response<Embedding> embedResp = mock(Response.class);
        when(embedResp.content()).thenReturn(Embedding.from(new float[]{1.0f, 0.0f, 0.0f}));
        when(embeddingModel.embed(anyString())).thenReturn(embedResp);


        RAGService service = new RAGService(config, storeManager, embeddingModel, chatModel);
        RAGService.AnswerWithSources result = service.answerWithSources("random question");

        assertThat(result.answer).isNotNull();
        assertThat(result.sources).isEmpty();
    }

    @Test
    @SuppressWarnings("unchecked")
    void shouldIncludeFileNameInSourceMetadata() {
        float[] vector = {0.3f, 0.3f, 0.3f};
        TextSegment segment = TextSegment.from("water boils at 100 degrees.");
        segment.metadata().put("file_name", "science.txt");
        storeManager.add(Embedding.from(vector), segment);

        EmbeddingModel embeddingModel = mock(EmbeddingModel.class);
        Response<Embedding> embedResp = mock(Response.class);
        when(embedResp.content()).thenReturn(Embedding.from(vector));
        when(embeddingModel.embed(anyString())).thenReturn(embedResp);


        RAGService service = new RAGService(config, storeManager, embeddingModel, chatModel);
        RAGService.AnswerWithSources result = service.answerWithSources("boiling point of water");

        assertThat(result.sources).hasSize(1);
        assertThat(result.sources.get(0).fileName).contains("science.txt");
    }

    @Test
    @SuppressWarnings("unchecked")
    void shouldRespectMaxResultsLimit() {
        // Add 5 documents
        for (int i = 0; i < 5; i++) {
            TextSegment seg = TextSegment.from("doc " + i);
            storeManager.add(Embedding.from(new float[]{0.5f, 0.5f}), seg);
        }
        when(config.getMaxResults()).thenReturn(2);

        EmbeddingModel embeddingModel = mock(EmbeddingModel.class);
        Response<Embedding> embedResp = mock(Response.class);
        when(embedResp.content()).thenReturn(Embedding.from(new float[]{0.5f, 0.5f}));
        when(embeddingModel.embed(anyString())).thenReturn(embedResp);


        RAGService service = new RAGService(config, storeManager, embeddingModel, chatModel);
        RAGService.AnswerWithSources result = service.answerWithSources("query");

        assertThat(result.sources).hasSize(2);
    }

    @Test
    @SuppressWarnings("unchecked")
    void shouldUseQueryEnhancementWhenEnabled() {
        // Add document
        float[] v1 = {0.5f, 0.5f, 0.5f, 0.5f};
        TextSegment s1 = TextSegment.from("安装教程：下载后解压运行");
        s1.metadata().put("file_name", "guide.txt");
        storeManager.add(Embedding.from(v1), s1);

        // Mock embedding model
        EmbeddingModel embeddingModel = mock(EmbeddingModel.class);
        Response<Embedding> embedResp = mock(Response.class);
        when(embedResp.content()).thenReturn(Embedding.from(v1));
        when(embeddingModel.embed(anyString())).thenReturn(embedResp);

        // Mock Router — return a rewritten query
        QueryEnhancementRouter mockRouter = mock(QueryEnhancementRouter.class);
        when(mockRouter.route("怎么装", "rewrite")).thenReturn(List.of("安装教程"));

        QueryEnhancementConfig mockEnhConfig = mock(QueryEnhancementConfig.class);
        when(mockEnhConfig.isQueryEnhancementEnabled()).thenReturn(true);
        when(mockEnhConfig.getDefaultEnhancementMode()).thenReturn("rewrite");

        RAGService service = new RAGService(config, storeManager, embeddingModel, chatModel,
                mockRouter, mockEnhConfig);
        RAGService.AnswerWithSources result = service.answerWithSources("怎么装", "rewrite");

        assertThat(result.sources).hasSize(1);
        assertThat(result.sources.get(0).text).isEqualTo("安装教程：下载后解压运行");
    }
}
