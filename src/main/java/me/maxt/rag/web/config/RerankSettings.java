package me.maxt.rag.web.config;

/**
 * 重排序配置节。
 *
 * @author maxt
 * @since 1.0
 */
public record RerankSettings(
        @Key(json = "rerank.modelPath", env = "RAG_RERANK_MODEL_PATH",
                def = "models/bge-reranker-v2-m3") String getRerankModelPath,
        @Key(json = "rerank.expansionFactor", env = "RAG_RERANK_EXPANSION_FACTOR",
                def = "3") int getRerankExpansionFactor,
        @Key(json = "rerank.topK", env = "RAG_RERANK_TOP_K", def = "5") int getRerankTopK) implements RerankConfig {
}
