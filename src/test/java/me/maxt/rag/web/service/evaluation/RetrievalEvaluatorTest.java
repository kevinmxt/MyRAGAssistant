package me.maxt.rag.web.service.evaluation;

import me.maxt.rag.web.config.EvaluationConfig;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RetrievalEvaluatorTest {

    @Test
    void shouldRunAllFourMetrics() {
        EvaluationConfig config = mock(EvaluationConfig.class);
        when(config.getEvaluationTopK()).thenReturn(5);
        RetrievalEvaluator evaluator = new RetrievalEvaluator(config);
        TestCase tc = new TestCase("t1", "query", List.of("a.txt"), null, null);
        Map<String, Double> results = evaluator.evaluate(tc, List.of("a.txt"));
        assertThat(results).containsKeys("recallAtK", "precisionAtK", "mrr", "ndcgAtK");
    }

    @Test
    void shouldAggregateCorrectly() {
        EvaluationConfig config = mock(EvaluationConfig.class);
        when(config.getEvaluationTopK()).thenReturn(5);
        RetrievalEvaluator evaluator = new RetrievalEvaluator(config);
        Map<String, List<Double>> scores = Map.of(
                "recallAtK", List.of(0.8, 1.0),
                "mrr", List.of(0.5, 1.0)
        );
        Map<String, Double> avg = evaluator.aggregate(scores);
        assertThat(avg.get("recallAtK")).isEqualTo(0.9);
        assertThat(avg.get("mrr")).isEqualTo(0.75);
    }
}
