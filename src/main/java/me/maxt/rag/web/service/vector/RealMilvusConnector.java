package me.maxt.rag.web.service.vector;

import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.milvus.MilvusEmbeddingStore;
import io.milvus.common.clientenum.ConsistencyLevelEnum;
import io.milvus.v2.client.ConnectConfig;
import io.milvus.v2.client.MilvusClientV2;

import java.net.InetSocketAddress;
import java.net.Socket;

/**
 * 生产适配器：真实 Milvus 的探针与建连。
 */
public class RealMilvusConnector implements MilvusConnector {

    @Override
    public boolean reachable(String host, int port, int timeoutMs) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), timeoutMs);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    @Override
    public String serverVersion(String host, int port) throws Exception {
        MilvusClientV2 client = new MilvusClientV2(ConnectConfig.builder()
                .uri("http://" + host + ":" + port)
                .build());
        try {
            Object version = client.getServerVersion();
            return version != null ? version.toString() : null;
        } finally {
            client.close();
        }
    }

    @Override
    public Connection connect(String host, int port, String collectionName, int dimension) throws Exception {
        EmbeddingStore<TextSegment> store = MilvusEmbeddingStore.builder()
                .host(host)
                .port(port)
                .collectionName(collectionName)
                .dimension(dimension)
                .consistencyLevel(ConsistencyLevelEnum.STRONG)
                .build();
        MilvusClientV2 client = new MilvusClientV2(ConnectConfig.builder()
                .uri("http://" + host + ":" + port)
                .build());
        return new Connection(store, client);
    }
}
