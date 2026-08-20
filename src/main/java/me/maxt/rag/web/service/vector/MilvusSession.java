package me.maxt.rag.web.service.vector;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.inmemory.InMemoryEmbeddingStore;
import io.milvus.v2.client.MilvusClientV2;
import io.milvus.v2.service.vector.request.QueryReq;
import io.milvus.v2.service.vector.response.QueryResp;
import me.maxt.rag.web.config.MilvusConfig;
import me.maxt.rag.web.service.EmbeddingStoreManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 向量库会话（术语见 CONTEXT.md）：拥有向量存储连接生命周期——当前活跃 store、
 * 原生 Milvus 客户端、以及 DEGRADED（内存降级）↔ CONNECTED 之间的原子切换
 * （换 store + 重建文档索引一步完成）。
 *
 * <p>构造零 I/O，出生即 DEGRADED；{@link #connect()} 是唯一建连路径
 * （探针快速失败 → 连接 → 原子切换），启动初始化与重连共用。消费者每次使用时
 * 通过 {@link #nativeClient()} 拉取当前引用，不缓存——重连后永不过期。</p>
 */
public class MilvusSession {

    private static final Logger log = LoggerFactory.getLogger(MilvusSession.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final int PROBE_TIMEOUT_MS = 2000;

    public enum State { CONNECTED, DEGRADED }

    public record Status(State state, String activeStore, String lastError) {}

    public record ProbeResult(boolean reachable, String version, String message) {}

    private final MilvusConfig config;
    private final MilvusConnector connector;
    private final EmbeddingStore<TextSegment> memoryStore = new InMemoryEmbeddingStore<>();
    private final EmbeddingStoreManager storeManager;
    private volatile MilvusConnector.Connection current;  // null = DEGRADED
    private volatile String lastError = "";

    public MilvusSession(MilvusConfig config, MilvusConnector connector) {
        this.config = config;
        this.connector = connector;
        this.storeManager = new EmbeddingStoreManager(() ->
                current != null ? current.store() : memoryStore);
    }

    /** 唯一建连路径：探针快速失败 → 连接 → 原子切换（换 store + 重建索引）。
     * @return null 表示成功；否则返回错误描述（此时会话为 DEGRADED） */
    public synchronized String connect() {
        String host = config.getMilvusHost();
        int port = config.getMilvusPort();
        if (!connector.reachable(host, port, PROBE_TIMEOUT_MS)) {
            return degrade("Milvus 不可达 (" + host + ":" + port + ") → 请先 docker compose up -d");
        }
        try {
            MilvusConnector.Connection c = connector.connect(
                    host, port, config.getMilvusCollectionName(), config.getMilvusDimension());
            this.current = c;
            this.lastError = "";
            storeManager.replaceAllIndex(loadIndex(c.client()));
            log.info("Milvus 已连接 ({}:{}), 向量存储已切换", host, port);
            return null;
        } catch (Exception e) {
            log.warn("Milvus 存储切换失败: {}", e.getMessage());
            return degrade("Milvus 可达但存储切换失败: " + e.getMessage());
        }
    }

    /** 拉模型：当前原生客户端；null = 降级中 */
    public MilvusClientV2 nativeClient() {
        MilvusConnector.Connection c = current;
        return c != null ? c.client() : null;
    }

    public Status status() {
        MilvusConnector.Connection c = current;
        return new Status(c != null ? State.CONNECTED : State.DEGRADED,
                c != null ? "milvus" : "in-memory", lastError);
    }

    /** 纯读探测（不建会话、不改状态），供环境检测的 Milvus 检测项委托 */
    public ProbeResult probe() {
        String host = config.getMilvusHost();
        int port = config.getMilvusPort();
        if (!connector.reachable(host, port, PROBE_TIMEOUT_MS)) {
            return new ProbeResult(false, null,
                    "Milvus 不可达 (" + host + ":" + port + ") → docker compose up -d 启动 Milvus");
        }
        String version = null;
        try {
            version = connector.serverVersion(host, port);
        } catch (Exception e) {
            // 版本查询失败不影响可达性判断
        }
        return new ProbeResult(true, version, "Milvus 可连接 (" + host + ":" + port + ")");
    }

    public EmbeddingStoreManager storeManager() { return storeManager; }

    /** 进入降级：关闭旧客户端、换回内存 store、清空索引。返回错误描述。 */
    private String degrade(String message) {
        MilvusConnector.Connection c = current;
        current = null;
        if (c != null) {
            try { c.client().close(); } catch (Exception ignored) {}
        }
        storeManager.replaceAllIndex(Map.of());
        this.lastError = message;
        log.warn("向量库会话降级到内存存储: {}", message);
        return message;
    }

    /**
     * 从 Milvus 查询全量 metadata 重建文档索引（迁自原 EmbeddingStoreManager.rebuildIndexFromMilvus）。
     * 查询失败返回空 Map——降级不抛出，与原行为一致。
     */
    @SuppressWarnings("unchecked")
    private Map<String, EmbeddingStoreManager.DocEntry> loadIndex(MilvusClientV2 client) {
        Map<String, EmbeddingStoreManager.DocEntry> index = new LinkedHashMap<>();
        try {
            QueryReq req = QueryReq.builder()
                    .collectionName(config.getMilvusCollectionName())
                    .filter("id != \"\"")
                    .outputFields(List.of("metadata"))
                    .limit(10000)
                    .build();
            QueryResp resp = client.query(req);
            int count = 0;
            for (QueryResp.QueryResult qr : resp.getQueryResults()) {
                Map<String, Object> entity = qr.getEntity();
                Object metaObj = entity.get("metadata");
                if (metaObj == null) continue;
                Map<String, Object> meta;
                if (metaObj instanceof Map) {
                    meta = (Map<String, Object>) metaObj;
                } else {
                    meta = MAPPER.readValue(metaObj.toString(), Map.class);
                }
                String fileName = (String) meta.get("file_name");
                if (fileName == null) continue;
                String fileType = (String) meta.getOrDefault("file_type", "");
                String dir = (String) meta.getOrDefault("absolute_directory_path", "");
                index.merge(fileName, new EmbeddingStoreManager.DocEntry(dir, fileType, 1),
                        (old, n) -> { old.segmentCount += 1; return old; });
                count++;
            }
            log.info("从 Milvus 重建文档索引完成: {} 个文档, {} 个 chunk", index.size(), count);
        } catch (Exception e) {
            log.warn("从 Milvus 重建文档索引失败: {}", e.getMessage());
        }
        return index;
    }
}
