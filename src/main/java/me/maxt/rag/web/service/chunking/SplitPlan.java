package me.maxt.rag.web.service.chunking;

import java.util.List;
import java.util.Map;

public record SplitPlan(
    List<StrategyEntry> strategies,
    boolean needsAgentRefinement,
    int targetChunkSize
) {
    public static SplitPlan fallback(int chunkSize) {
        return new SplitPlan(
            List.of(new StrategyEntry("recursive", Map.of("chunkSize", chunkSize, "chunkOverlap", 0))),
            false, chunkSize);
    }
}
