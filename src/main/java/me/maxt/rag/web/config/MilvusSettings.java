package me.maxt.rag.web.config;

/**
 * Milvus 向量库配置节。
 *
 * <p>collectionName 同时被 {@link RecallSettings} 绑定（RecallConfig 接口亦声明该方法），
 * 两处 json 键与环境变量相同，取值恒等。</p>
 *
 * @author maxt
 * @since 1.0
 */
public record MilvusSettings(
        @Key(json = "milvus.host", env = "RAG_MILVUS_HOST", def = "localhost") String getMilvusHost,
        @Key(json = "milvus.port", env = "RAG_MILVUS_PORT", def = "19530") int getMilvusPort,
        @Key(json = "milvus.collectionName", env = "RAG_MILVUS_COLLECTION",
                def = "rag_knowledge_base") String getMilvusCollectionName,
        @Key(json = "milvus.dimension", env = "RAG_MILVUS_DIMENSION", def = "512") int getMilvusDimension) implements MilvusConfig {
}
