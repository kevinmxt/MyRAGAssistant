package me.maxt.rag.web.service.vector;

import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.output.Response;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import dev.langchain4j.store.embedding.EmbeddingSearchRequest;
import dev.langchain4j.store.embedding.inmemory.InMemoryEmbeddingStore;
import me.maxt.rag.web.config.QueryEnhancementConfig;
import me.maxt.rag.web.config.RecallConfig;
import me.maxt.rag.web.config.RerankConfig;
import me.maxt.rag.web.config.RetrievalConfig;
import me.maxt.rag.web.service.EmbeddingStoreManager;
import me.maxt.rag.web.service.vector.recall.MultiRecallRouter;
import me.maxt.rag.web.service.vector.rerank.Reranker;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class RetrievalPipelineTest {

    private EmbeddingStoreManager storeManager;
    private RetrievalConfig retrievalConfig;
    private QueryEnhancementConfig enhConfig;
    private RecallConfig recallConfig;
    private RerankConfig rerankConfig;
    private Reranker reranker;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        InMemoryEmbeddingStore<TextSegment> store = new InMemoryEmbeddingStore<>();
        storeManager = spy(new EmbeddingStoreManager(() -> store));

        retrievalConfig = mock(RetrievalConfig.class);
        when(retrievalConfig.getMaxResults()).thenReturn(3);
        when(retrievalConfig.getMinScore()).thenReturn(0.0);

        enhConfig = mock(QueryEnhancementConfig.class);
        when(enhConfig.isQueryEnhancementEnabled()).thenReturn(false);

        recallConfig = mock(RecallConfig.class);
        when(recallConfig.isMultiRecallEnabled()).thenReturn(false);

        rerankConfig = mock(RerankConfig.class);
        when(rerankConfig.getRerankTopK()).thenReturn(5);
        when(rerankConfig.getRerankExpansionFactor()).thenReturn(3);

        reranker = mock(Reranker.class);
        when(reranker.isAvailable()).thenReturn(false);
    }

    /** 构造 P 通道管线（E/M 关闭，reranker 可用性由参数控制） */
    private RetrievalPipeline plainPipeline(EmbeddingModel embeddingModel) {
        return new RetrievalPipeline(new RetrievalPipeline.Deps(
                storeManager, embeddingModel, retrievalConfig,
                mock(QueryEnhancementRouter.class), enhConfig,
                new MultiRecallRouter(recallConfig, java.util.Map.of()), recallConfig,
                reranker, rerankConfig));
    }

    private EmbeddingModel fixedVectorModel(float[] vector) {
        EmbeddingModel model = mock(EmbeddingModel.class);
        Response<Embedding> resp = mock(Response.class);
        when(resp.content()).thenReturn(Embedding.from(vector));
        when(model.embed(anyString())).thenReturn(resp);
        return model;
    }

    @Test
    void shouldRetrieveCorrectSources() {
        float[] v1 = {0.5f, 0.5f, 0.5f};
        TextSegment s1 = TextSegment.from("Paris is the capital of France.");
        s1.metadata().put("file_name", "facts.txt");
        storeManager.add(Embedding.from(v1), s1);

        List<RetrievalPipeline.Source> sources =
                plainPipeline(fixedVectorModel(v1)).retrieve("What is the capital of France?", null);

        assertThat(sources).hasSize(1);
        assertThat(sources.get(0).text()).isEqualTo("Paris is the capital of France.");
        assertThat(sources.get(0).fileName()).contains("facts.txt");
        assertThat(sources.get(0).score()).isGreaterThan(0.9);
    }

    @Test
    void shouldIncludeDirectoryPathInFileName() {
        float[] v = {0.3f, 0.3f, 0.3f};
        TextSegment seg = TextSegment.from("London is the capital of UK.");
        seg.metadata().put("file_name", "geo.txt");
        seg.metadata().put("absolute_directory_path", "/docs");
        storeManager.add(Embedding.from(v), seg);

        List<RetrievalPipeline.Source> sources =
                plainPipeline(fixedVectorModel(v)).retrieve("boiling point of water", null);

        assertThat(sources).hasSize(1);
        assertThat(sources.get(0).fileName()).isEqualTo("/docs/geo.txt");
    }

    @Test
    void shouldReturnEmptySourcesWhenNoRelevantDocs() {
        List<RetrievalPipeline.Source> sources =
                plainPipeline(fixedVectorModel(new float[]{1.0f, 0.0f, 0.0f}))
                        .retrieve("random question", null);
        assertThat(sources).isEmpty();
    }

    @Test
    void shouldLimitToMaxResultsWhenRerankerUnavailable() {
        for (int i = 0; i < 5; i++) {
            storeManager.add(Embedding.from(new float[]{0.5f, 0.5f}), TextSegment.from("doc " + i));
        }
        when(retrievalConfig.getMaxResults()).thenReturn(2);

        List<RetrievalPipeline.Source> sources =
                plainPipeline(fixedVectorModel(new float[]{0.5f, 0.5f})).retrieve("query", null);

        assertThat(sources).hasSize(2);
        verify(reranker, never()).rerank(anyString(), anyList(), anyInt());
    }

    @Test
    @SuppressWarnings("unchecked")
    void shouldRerankPlainRetrievalAndExpandPoolWhenAvailable() {
        // 行为变化①：朴素路径也精排；且候选池按 expansionFactor 扩展
        when(reranker.isAvailable()).thenReturn(true);
        float[] v = {0.5f, 0.5f};
        storeManager.add(Embedding.from(v), TextSegment.from("candidate"));
        EmbeddingMatch<TextSegment> reranked = mock(EmbeddingMatch.class);
        TextSegment seg = TextSegment.from("reranked text");
        seg.metadata().put("file_name", "r.txt");
        when(reranked.embedded()).thenReturn(seg);
        when(reranked.score()).thenReturn(0.95);
        when(reranker.rerank(eq("query"), anyList(), eq(5))).thenReturn(List.of(reranked));

        List<RetrievalPipeline.Source> sources =
                plainPipeline(fixedVectorModel(v)).retrieve("query", null);

        // 候选池深度 = maxResults(3) × expansion(3) = 9
        ArgumentCaptor<EmbeddingSearchRequest> captor = ArgumentCaptor.forClass(EmbeddingSearchRequest.class);
        verify(storeManager, atLeastOnce()).search(captor.capture());
        assertThat(captor.getValue().maxResults()).isEqualTo(9);
        // 精排结果成为 sources
        assertThat(sources).hasSize(1);
        assertThat(sources.get(0).text()).isEqualTo("reranked text");
    }

    @Test
    @SuppressWarnings("unchecked")
    void shouldNotExpandPoolWhenRerankerUnavailable() {
        float[] v = {0.5f, 0.5f};
        storeManager.add(Embedding.from(v), TextSegment.from("candidate"));

        plainPipeline(fixedVectorModel(v)).retrieve("query", null);

        ArgumentCaptor<EmbeddingSearchRequest> captor = ArgumentCaptor.forClass(EmbeddingSearchRequest.class);
        verify(storeManager, atLeastOnce()).search(captor.capture());
        assertThat(captor.getValue().maxResults()).isEqualTo(3);
    }
}
