package me.maxt.rag.web.config;

import java.util.List;

/**
 * 多路召回配置节：九键含 LightRAG 四键（json 嵌套 multiRecall.lightrag 下）与
 * milvus.collectionName（与 {@link MilvusSettings} 绑定同键，取值恒等）。
 *
 * @author maxt
 * @since 1.0
 */
public record RecallSettings(
        @Key(json = "multiRecall.enabled", env = "RAG_MULTI_RECALL_ENABLED",
                def = "false") boolean isMultiRecallEnabled,
        @Key(json = "multiRecall.modes", env = "RAG_MULTI_RECALL_MODES", def = "dense") List<String> getRecallModes,
        @Key(json = "multiRecall.topK", env = "RAG_MULTI_RECALL_TOP_K", def = "5") int getRecallTopK,
        @Key(json = "multiRecall.rrfK", env = "RAG_MULTI_RECALL_RRF_K", def = "60") int getRecallRrfK,
        @Key(json = "multiRecall.lightrag.pythonPath", env = "RAG_LIGHTRAG_PYTHON",
                def = "python") String getLightRagPythonPath,
        @Key(json = "multiRecall.lightrag.workingDir", env = "RAG_LIGHTRAG_WORKDIR",
                def = "data/kg") String getLightRagWorkingDir,
        @Key(json = "multiRecall.lightrag.embeddingModelPath", env = "RAG_LIGHTRAG_EMBEDDING",
                def = "models/bge-small-zh-v1.5") String getLightRagEmbeddingModelPath,
        @Key(json = "multiRecall.lightrag.queryMode", env = "RAG_LIGHTRAG_QUERY_MODE",
                def = "hybrid") String getLightRagQueryMode,
        @Key(json = "milvus.collectionName", env = "RAG_MILVUS_COLLECTION",
                def = "rag_knowledge_base") String getMilvusCollectionName) implements RecallConfig {
}
