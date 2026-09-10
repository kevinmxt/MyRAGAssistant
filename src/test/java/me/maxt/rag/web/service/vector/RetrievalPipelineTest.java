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

    // ===== E 通道 =====

    private RetrievalPipeline enhancedPipeline(EmbeddingModel embeddingModel, QueryEnhancementRouter router) {
        when(enhConfig.isQueryEnhancementEnabled()).thenReturn(true);
        when(enhConfig.getDefaultEnhancementMode()).thenReturn("rewrite");
        when(enhConfig.getRrfK()).thenReturn(60);
        return new RetrievalPipeline(new RetrievalPipeline.Deps(
                storeManager, embeddingModel, retrievalConfig,
                router, enhConfig,
                new MultiRecallRouter(recallConfig, java.util.Map.of()), recallConfig,
                reranker, rerankConfig));
    }

    @Test
    void shouldUseQueryEnhancementSingleVariant() {
        float[] v1 = {0.5f, 0.5f, 0.5f};
        TextSegment s1 = TextSegment.from("安装教程：下载后解压运行");
        s1.metadata().put("file_name", "guide.txt");
        storeManager.add(Embedding.from(v1), s1);

        QueryEnhancementRouter router = mock(QueryEnhancementRouter.class);
        when(router.route("怎么装", "rewrite")).thenReturn(List.of("安装教程"));

        List<RetrievalPipeline.Source> sources =
                enhancedPipeline(fixedVectorModel(v1), router).retrieve("怎么装", null);

        assertThat(sources).hasSize(1);
        assertThat(sources.get(0).text()).isEqualTo("安装教程：下载后解压运行");
    }

    @Test
    void shouldFuseMultiVariantsAndRerank() {
        // 行为变化①④：多变体融合（fuseN）后统一精排
        when(reranker.isAvailable()).thenReturn(true);
        float[] v = {0.5f, 0.5f};
        storeManager.add(Embedding.from(v), TextSegment.from("变体命中"));
        EmbeddingMatch<TextSegment> reranked = mock(EmbeddingMatch.class);
        TextSegment seg = TextSegment.from("精排结果");
        seg.metadata().put("file_name", "fused.txt");
        when(reranked.embedded()).thenReturn(seg);
        when(reranked.score()).thenReturn(0.9);
        when(reranker.rerank(eq("原始问题"), anyList(), eq(5))).thenReturn(List.of(reranked));

        QueryEnhancementRouter router = mock(QueryEnhancementRouter.class);
        when(router.route(anyString(), eq("both"))).thenReturn(List.of("变体1", "变体2"));

        // 配置默认模式设为 both（在 enhancedPipeline 打桩之后覆盖），使 route 桩真正命中、
        // 多变体融合路径被真实执行
        RetrievalPipeline pipeline = enhancedPipeline(fixedVectorModel(v), router);
        when(enhConfig.getDefaultEnhancementMode()).thenReturn("both");
        List<RetrievalPipeline.Source> sources = pipeline.retrieve("原始问题", null);

        // 每个变体各检索一次（2 次 embed + search），精排结果成为 sources
        verify(storeManager, times(2)).search(any(EmbeddingSearchRequest.class));
        assertThat(sources).hasSize(1);
        assertThat(sources.get(0).text()).isEqualTo("精排结果");
        verify(reranker).rerank(eq("原始问题"), anyList(), eq(5));
    }

    @Test
    void shouldPreferOverrideModeOverConfigDefault() {
        float[] v = {0.5f, 0.5f};
        storeManager.add(Embedding.from(v), TextSegment.from("命中"));
        QueryEnhancementRouter router = mock(QueryEnhancementRouter.class);
        when(router.route("q", "hyde")).thenReturn(List.of("假设文档"));

        enhancedPipeline(fixedVectorModel(v), router)
                .retrieve("q", new RetrievalPipeline.RetrievalOverrides("hyde", null));

        verify(router).route("q", "hyde");
    }

    @Test
    void shouldDefaultModeToNoneWhenConfigNull() {
        float[] v = {0.5f, 0.5f};
        storeManager.add(Embedding.from(v), TextSegment.from("命中"));
        QueryEnhancementRouter router = mock(QueryEnhancementRouter.class);
        when(router.route("q", "none")).thenReturn(List.of("q"));

        RetrievalPipeline pipeline = enhancedPipeline(fixedVectorModel(v), router);
        // 覆盖 enhancedPipeline 的默认桩：最后一次打桩生效，配置默认模式为 null
        when(enhConfig.getDefaultEnhancementMode()).thenReturn(null);
        pipeline.retrieve("q", null);

        verify(router).route("q", "none");
    }
}
