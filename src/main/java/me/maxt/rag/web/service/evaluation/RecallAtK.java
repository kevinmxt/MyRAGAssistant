package me.maxt.rag.web.service.evaluation;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class RecallAtK implements EvaluationMetric {

    private final int k;

    public RecallAtK(int k) {
        this.k = k;
    }

    @Override
    public String name() {
        return "recallAtK";
    }

    @Override
    public double calculate(String query, List<String> retrievedDocNames, TestCase testCase) {
        Set<String> relevant = new HashSet<>(testCase.relevantDocs());
        List<String> topK = retrievedDocNames.stream().limit(k).toList();
        long hitCount = topK.stream().filter(name -> matchesAny(name, relevant)).count();
        return relevant.isEmpty() ? 0.0 : (double) hitCount / relevant.size();
    }

    static boolean matchesAny(String retrievedName, Set<String> relevantNames) {
        for (String rn : relevantNames) {
            if (retrievedName.equals(rn) || retrievedName.endsWith("/" + rn) || retrievedName.endsWith("\\" + rn)) {
                return true;
            }
        }
        return false;
    }
}
