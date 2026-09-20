package me.maxt.rag.web.config;

/**
 * 检索配置节：含跨节键 chat.memorySize（对话记忆窗口，json 路径在 chat 节下）。
 *
 * @author maxt
 * @since 1.0
 */
public record RetrievalSettings(
        @Key(json = "retrieval.maxResults", env = "RAG_RETRIEVAL_MAX_RESULTS", def = "3") int getMaxResults,
        @Key(json = "retrieval.minScore", env = "RAG_RETRIEVAL_MIN_SCORE", def = "0.5") double getMinScore,
        @Key(json = "chat.memorySize", env = "RAG_CHAT_MEMORY_SIZE", def = "10") int getMemorySize) implements RetrievalConfig {
}
