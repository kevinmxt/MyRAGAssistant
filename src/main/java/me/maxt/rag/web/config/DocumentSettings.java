package me.maxt.rag.web.config;

import java.util.List;

/**
 * 文档配置节：含平铺进来的 chunking 四键（json 路径嵌在 document.chunking 下，
 * ChunkingConfig 无独立消费者，不单建节）。
 *
 * @author maxt
 * @since 1.0
 */
public record DocumentSettings(
        @Key(json = "document.dir", env = "RAG_DOCUMENT_DIR", def = "./documents") String getDocumentDir,
        @Key(json = "document.chunkSize", env = "RAG_CHUNK_SIZE", def = "300") int getChunkSize,
        @Key(json = "document.chunkOverlap", env = "RAG_CHUNK_OVERLAP", def = "0") int getChunkOverlap,
        @Key(json = "document.supportedExtensions", env = "RAG_SUPPORTED_EXTENSIONS",
                def = ".txt,.pdf,.docx,.doc,.png,.jpg,.jpeg,.md,.html,.csv,.json,.xlsx,.pptx") List<String> getSupportedFileExtensions,
        @Key(json = "document.chunking.mode", env = "RAG_CHUNKING_MODE", def = "auto") String getChunkingMode,
        @Key(json = "document.chunking.semanticThreshold", env = "RAG_CHUNKING_SEMANTIC_THRESHOLD",
                def = "0.6") double getSemanticThreshold,
        @Key(json = "document.chunking.enableAgentRefiner", env = "RAG_CHUNKING_AGENT_REFINER",
                def = "false") boolean isAgentRefinerEnabled,
        @Key(json = "document.chunking.maxChunkSize", env = "RAG_CHUNKING_MAX_SIZE", def = "2000") int getMaxChunkSize) implements DocumentConfig {
}
