package me.maxt.rag.web.service.evaluation;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class MRR implements EvaluationMetric {

    @Override
    public String name() {
        return "mrr";
    }

    @Override
    public double calculate(String query, List<String> retrievedDocNames, TestCase testCase) {
        Set<String> relevant = new HashSet<>(testCase.relevantDocs());
        for (int i = 0; i < retrievedDocNames.size(); i++) {
            if (RecallAtK.matchesAny(retrievedDocNames.get(i), relevant)) {
                return 1.0 / (i + 1);
            }
        }
        return 0.0;
    }
}
