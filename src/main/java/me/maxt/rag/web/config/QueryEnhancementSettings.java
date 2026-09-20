package me.maxt.rag.web.config;

/**
 * 查询增强配置节。
 *
 * @author maxt
 * @since 1.0
 */
public record QueryEnhancementSettings(
        @Key(json = "queryEnhancement.enabled", env = "RAG_QUERY_ENHANCEMENT_ENABLED",
                def = "true") boolean isQueryEnhancementEnabled,
        @Key(json = "queryEnhancement.defaultMode", env = "RAG_QUERY_ENHANCEMENT_MODE",
                def = "auto") String getDefaultEnhancementMode,
        @Key(json = "queryEnhancement.rrfK", env = "RAG_QUERY_ENHANCEMENT_RRF_K", def = "60") int getRrfK,
        @Key(json = "queryEnhancement.hydeMaxTokens", env = "RAG_QUERY_ENHANCEMENT_HYDE_MAX_TOKENS",
                def = "200") int getHydeMaxTokens) implements QueryEnhancementConfig {
}
