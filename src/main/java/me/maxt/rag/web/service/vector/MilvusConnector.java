package me.maxt.rag.web.service.vector;

import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.store.embedding.EmbeddingStore;
import io.milvus.v2.client.MilvusClientV2;

/**
 * Milvus 连接操作的内部接缝：生产适配器连真实 Milvus，
 * 测试注入假件控制可达性与建连成败。
 * 接口由 MilvusSession 持有，不对其他消费者开放。
 */
public interface MilvusConnector {

    /** TCP 探针：快速判断可达性，不建立会话、不持有状态 */
    boolean reachable(String host, int port, int timeoutMs);

    /** 查询服务端版本（best-effort，失败抛异常，不影响可达性判断） */
    String serverVersion(String host, int port) throws Exception;

    /** 建立连接：返回 store + 原生客户端；失败抛异常 */
    Connection connect(String host, int port, String collectionName, int dimension) throws Exception;

    record Connection(EmbeddingStore<TextSegment> store, MilvusClientV2 client) {}
}
