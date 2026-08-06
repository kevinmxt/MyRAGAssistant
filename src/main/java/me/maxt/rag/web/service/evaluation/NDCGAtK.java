package me.maxt.rag.web.service.evaluation;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class NDCGAtK implements EvaluationMetric {

    private final int k;

    public NDCGAtK(int k) {
        this.k = k;
    }

    @Override
    public String name() {
        return "ndcgAtK";
    }

    @Override
    public double calculate(String query, List<String> retrievedDocNames, TestCase testCase) {
        Set<String> relevant = new HashSet<>(testCase.relevantDocs());
        double dcg = 0.0;
        double idcg = 0.0;

        for (int i = 0; i < Math.min(retrievedDocNames.size(), k); i++) {
            int rel = RecallAtK.matchesAny(retrievedDocNames.get(i), relevant) ? 1 : 0;
            dcg += (Math.pow(2, rel) - 1) / (Math.log(i + 2) / Math.log(2));
        }

        int idealCount = Math.min(relevant.size(), k);
        for (int i = 0; i < idealCount; i++) {
            idcg += 1.0 / (Math.log(i + 2) / Math.log(2));
        }

        return idcg == 0.0 ? 0.0 : dcg / idcg;
    }
}
