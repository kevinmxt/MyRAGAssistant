package me.maxt.rag.web.service.evaluation;

import me.maxt.rag.web.config.EvaluationConfig;
import me.maxt.rag.web.service.RAGService;
import me.maxt.rag.web.service.vector.RetrievalPipeline;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 评估管线分流测试：答案质量要评估才调 LLM，否则纯检索指标走 pipeline.retrieve（零 LLM 调用）。
 */
class EvaluationPipelineTest {

    @TempDir
    Path srcResourcesDir;

    @TempDir
    Path targetDir;

    private DatasetLoader datasetLoader;
    private KnowledgeBaseSeeder seeder;
    private RetrievalEvaluator retrievalEvaluator;
    private AnswerQualityEvaluator answerQualityEvaluator;
    private BaselineManager baselineManager;
    private EvaluationConfig config;
    private RAGService ragService;
    private RetrievalPipeline retrievalPipeline;

    @BeforeEach
    void setUp() {
        config = mock(EvaluationConfig.class);
        datasetLoader = mock(DatasetLoader.class);
        seeder = mock(KnowledgeBaseSeeder.class);
        retrievalEvaluator = mock(RetrievalEvaluator.class);
        answerQualityEvaluator = mock(AnswerQualityEvaluator.class);
        baselineManager = mock(BaselineManager.class);
        ragService = mock(RAGService.class);
        retrievalPipeline = mock(RetrievalPipeline.class);

        // 单用例数据集，驱动 run() 走到逐用例循环（validate/aggregate mock 默认返回空集合）
        DatasetFile dataset = new DatasetFile("md", "1.0", "2026-09-10",
                List.of(new TestCase("t1", "query", List.of("a.md"), null, null)));
        when(datasetLoader.load(anyString())).thenReturn(dataset);
        when(seeder.seed(any())).thenReturn(1);
        when(answerQualityEvaluator.isAvailable()).thenReturn(true);
        when(retrievalPipeline.retrieve(anyString(), any())).thenReturn(List.of());
        when(retrievalEvaluator.evaluate(any(), any())).thenReturn(Map.of(
                "recallAtK", 1.0, "precisionAtK", 1.0, "mrr", 1.0, "ndcgAtK", 1.0));
    }

    private EvaluationPipeline pipeline() {
        return new EvaluationPipeline(config, datasetLoader, seeder, retrievalEvaluator,
                answerQualityEvaluator, baselineManager, ragService, retrievalPipeline);
    }

    @Test
    void shouldSkipLlmWhenAnswerQualitySkipped() {
        // skipAnswerQuality=true → 只调 pipeline.retrieve，不生成答案
        pipeline().run("md", srcResourcesDir, targetDir, false, true);

        verify(retrievalPipeline).retrieve(anyString(), any());
        verify(ragService, never()).answerWithSources(anyString());
    }

    @Test
    void shouldSkipLlmWhenEvaluatorUnavailable() {
        when(answerQualityEvaluator.isAvailable()).thenReturn(false);

        pipeline().run("md", srcResourcesDir, targetDir, false, false);

        verify(retrievalPipeline).retrieve(anyString(), any());
        verify(ragService, never()).answerWithSources(anyString());
    }

    @Test
    void shouldGenerateAnswerWhenQualityEvaluated() {
        when(ragService.answerWithSources(anyString())).thenReturn(
                new RAGService.AnswerWithSources("answer", List.of()));

        pipeline().run("md", srcResourcesDir, targetDir, false, false);

        verify(ragService).answerWithSources(anyString());
    }
}
