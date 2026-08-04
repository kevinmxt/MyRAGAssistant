package me.maxt.rag.web.service.vector;

import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.store.embedding.EmbeddingMatch;

import java.util.*;

/**
 * RRF (Reciprocal Rank Fusion) 融合工具。
 * 支持2路和N路检索结果的去重融合。
 */
public final class RrfFusion {

    private RrfFusion() {}

    /** N路融合 */
    public static List<EmbeddingMatch<TextSegment>> fuseN(
            List<List<EmbeddingMatch<TextSegment>>> resultLists, int topK, int k) {

        Map<String, Double> rrfScores = new LinkedHashMap<>();
        Map<String, EmbeddingMatch<TextSegment>> matchMap = new HashMap<>();

        for (List<EmbeddingMatch<TextSegment>> results : resultLists) {
            accumulateRrf(results, rrfScores, matchMap, k);
        }

        return rrfScores.entrySet().stream()
                .sorted(Map.Entry.<String, Double>comparingByValue().reversed())
                .limit(topK)
                .map(e -> matchMap.get(e.getKey()))
                .toList();
    }

    /** 两路融合 */
    public static List<EmbeddingMatch<TextSegment>> fuse(
            List<EmbeddingMatch<TextSegment>> resultA,
            List<EmbeddingMatch<TextSegment>> resultB,
            int topK, int k) {
        return fuseN(List.of(resultA, resultB), topK, k);
    }

    private static void accumulateRrf(List<EmbeddingMatch<TextSegment>> results,
                                       Map<String, Double> scores,
                                       Map<String, EmbeddingMatch<TextSegment>> matchMap,
                                       int k) {
        for (int i = 0; i < results.size(); i++) {
            EmbeddingMatch<TextSegment> match = results.get(i);
            String key = match.embedded().text();
            scores.merge(key, 1.0 / (k + i + 1), Double::sum);
            matchMap.putIfAbsent(key, match);
        }
    }
}
