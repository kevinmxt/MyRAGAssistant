package me.maxt.rag.web.service.evaluation;

import me.maxt.rag.web.config.EvaluationConfig;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 检索评估编排器，对每个 TestCase 运行所有注册的指标。
 */
public class RetrievalEvaluator {

    private final List<EvaluationMetric> metrics;

    public RetrievalEvaluator(EvaluationConfig config) {
        this.metrics = new ArrayList<>();
        int k = config.getEvaluationTopK();
        metrics.add(new RecallAtK(k));
        metrics.add(new PrecisionAtK(k));
        metrics.add(new MRR());
        metrics.add(new NDCGAtK(k));
    }

    /**
     * 注册额外的自定义指标。
     */
    public void registerMetric(EvaluationMetric metric) {
        metrics.add(metric);
    }

    /**
     * 对单个测试用例运行所有指标。
     */
    public Map<String, Double> evaluate(TestCase testCase, List<String> retrievedDocNames) {
        Map<String, Double> results = new LinkedHashMap<>();
        for (EvaluationMetric metric : metrics) {
            results.put(metric.name(), metric.calculate(testCase.query(), retrievedDocNames, testCase));
        }
        return results;
    }

    /**
     * 对全部用例计算平均分数。
     */
    public Map<String, Double> aggregate(Map<String, List<Double>> allScores) {
        Map<String, Double> averages = new LinkedHashMap<>();
        for (var entry : allScores.entrySet()) {
            double avg = entry.getValue().stream().mapToDouble(Double::doubleValue).average().orElse(0.0);
            averages.put(entry.getKey(), avg);
        }
        return averages;
    }

    public List<EvaluationMetric> getMetrics() {
        return metrics;
    }
}
