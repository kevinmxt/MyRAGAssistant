package me.maxt.rag.web.service.vector.recall;

import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import me.maxt.rag.web.config.RecallConfig;
import me.maxt.rag.web.service.vector.RrfFusion;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * 多路召回路由器。
 * 根据配置中启用的模式，并行调用对应的 RecallStrategy，RRF 融合后返回 topK 结果。
 */
public class MultiRecallRouter {

    private static final Logger log = LoggerFactory.getLogger(MultiRecallRouter.class);

    private final RecallConfig config;
    private final Map<String, RecallStrategy> strategyRegistry;

    public MultiRecallRouter(RecallConfig config, Map<String, RecallStrategy> strategyRegistry) {
        this.config = config;
        this.strategyRegistry = strategyRegistry;
    }

    public List<EmbeddingMatch<TextSegment>> recall(String query, List<String> modes) {
        List<String> effectiveModes = resolveModes(modes);
        int perStrategyTopK = config.getRecallTopK() * 2;

        // 并行调用各策略：总延迟 = max(各路延迟)，单路失败/超时不影响其他路
        List<CompletableFuture<List<EmbeddingMatch<TextSegment>>>> futures = effectiveModes.stream()
                .map(mode -> CompletableFuture.supplyAsync(() -> {
                    RecallStrategy strategy = strategyRegistry.get(mode);
                    if (strategy == null) {
                        log.warn("Unknown recall mode: {}, skipping", mode);
                        return List.<EmbeddingMatch<TextSegment>>of();
                    }
                    try {
                        List<EmbeddingMatch<TextSegment>> matches = strategy.recall(query, perStrategyTopK);
                        log.debug("Recall mode {} returned {} results", mode, matches.size());
                        return matches;
                    } catch (Exception e) {
                        log.warn("Recall strategy {} failed, skipping: {}", mode, e.getMessage());
                        return List.<EmbeddingMatch<TextSegment>>of();
                    }
                }))
                .toList();

        List<List<EmbeddingMatch<TextSegment>>> resultGroups = futures.stream()
                .map(f -> {
                    try {
                        return f.get(30, TimeUnit.SECONDS);
                    } catch (Exception e) {
                        log.warn("Recall strategy timed out or failed: {}", e.getMessage());
                        return List.<EmbeddingMatch<TextSegment>>of();
                    }
                })
                .filter(r -> !r.isEmpty())
                .toList();

        if (resultGroups.isEmpty()) {
            log.warn("All recall strategies failed or returned empty");
            return List.of();
        }

        if (resultGroups.size() == 1) {
            return resultGroups.get(0).stream()
                    .limit(config.getRecallTopK())
                    .toList();
        }

        return RrfFusion.fuseN(resultGroups, config.getRecallTopK(), config.getRecallRrfK());
    }

    private List<String> resolveModes(List<String> requestedModes) {
        if (requestedModes != null && !requestedModes.isEmpty()) {
            return requestedModes;
        }
        return config.getRecallModes();
    }
}
