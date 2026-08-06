package me.maxt.rag.web.service.evaluation;

import java.util.List;

/**
 * 检索评估指标接口。新指标只需实现此接口并注册到 RetrievalEvaluator。
 */
public interface EvaluationMetric {
    String name();
    double calculate(String query, List<String> retrievedDocNames, TestCase testCase);
}
