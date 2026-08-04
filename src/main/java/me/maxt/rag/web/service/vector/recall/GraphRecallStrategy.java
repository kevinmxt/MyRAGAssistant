package me.maxt.rag.web.service.vector.recall;

import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import me.maxt.rag.web.service.KnowledgeGraphService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * 知识图谱检索策略，通过 LightRagBridge 调用 LightRAG Python 库。
 * 图谱未构建或 Python 环境不可用时自动降级返回空列表。
 */
public class GraphRecallStrategy implements RecallStrategy {

    private static final Logger log = LoggerFactory.getLogger(GraphRecallStrategy.class);

    private final KnowledgeGraphService kgService;
    private final LightRagBridge bridge;
    private final String queryMode;

    public GraphRecallStrategy(KnowledgeGraphService kgService,
                               LightRagBridge bridge, String queryMode) {
        this.kgService = kgService;
        this.bridge = bridge;
        this.queryMode = queryMode;
    }

    @Override
    public String name() { return "graph"; }

    @Override
    public List<EmbeddingMatch<TextSegment>> recall(String query, int topK) {
        if (!kgService.isBuilt()) {
            log.debug("KG not built, skipping graph recall");
            return List.of();
        }
        try {
            List<String> texts = bridge.query(query, queryMode);
            return texts.stream()
                    .limit(topK)
                    // 图谱检索无 embedding 向量，EmbeddingMatch.embedding 传 null
                    .<EmbeddingMatch<TextSegment>>map(text -> new EmbeddingMatch<>(
                            0.8, "graph-" + text.hashCode(), null, TextSegment.from(text)))
                    .toList();
        } catch (Exception e) {
            log.warn("Graph recall failed: {}", e.getMessage());
            return List.of();
        }
    }
}
