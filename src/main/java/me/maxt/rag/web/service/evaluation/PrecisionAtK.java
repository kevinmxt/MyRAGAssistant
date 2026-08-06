package me.maxt.rag.web.service.evaluation;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class PrecisionAtK implements EvaluationMetric {

    private final int k;

    public PrecisionAtK(int k) {
        this.k = k;
    }

    @Override
    public String name() {
        return "precisionAtK";
    }

    @Override
    public double calculate(String query, List<String> retrievedDocNames, TestCase testCase) {
        Set<String> relevant = new HashSet<>(testCase.relevantDocs());
        List<String> topK = retrievedDocNames.stream().limit(k).toList();
        long hitCount = topK.stream().filter(name -> RecallAtK.matchesAny(name, relevant)).count();
        return topK.isEmpty() ? 0.0 : (double) hitCount / topK.size();
    }
}
