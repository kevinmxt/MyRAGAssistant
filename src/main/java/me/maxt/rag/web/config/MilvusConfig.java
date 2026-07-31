package me.maxt.rag.web.config;

/**
 * Milvus 向量数据库配置接口。
 */
public interface MilvusConfig {

    /** @return Milvus 服务主机地址 */
    String getMilvusHost();

    /** @return Milvus gRPC 端口 */
    int getMilvusPort();

    /** @return Milvus collection 名称 */
    String getMilvusCollectionName();

    /** @return 向量维度（BgeSmallZhV15 = 512） */
    int getMilvusDimension();
}
