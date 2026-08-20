package me.maxt.rag.web.service;

import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.store.embedding.EmbeddingSearchRequest;
import dev.langchain4j.store.embedding.EmbeddingSearchResult;
import dev.langchain4j.store.embedding.EmbeddingStore;
import io.milvus.v2.client.MilvusClientV2;
import io.milvus.v2.service.vector.request.QueryReq;
import io.milvus.v2.service.vector.response.QueryResp;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * 向量存储管理器，封装 Milvus（或其他 EmbeddingStore 实现）。
 *
 * <p>构造函数接收 {@link EmbeddingStore<TextSegment>} 接口，
 * 生产环境注入 MilvusEmbeddingStore，测试注入 InMemoryEmbeddingStore。</p>
 *
 * <p>内部维护轻量级文档元数据索引，支持 {@link #getDocumentIndex()} 查询已索引文档摘要。</p>
 */
public class EmbeddingStoreManager {

    private static final Logger log = LoggerFactory.getLogger(EmbeddingStoreManager.class);

    /** store 供应商：每次操作解析当前 store（volatile——旧 swapStore 路径仍会重赋值，Task 5 后仅构造时赋值） */
    private volatile Supplier<EmbeddingStore<TextSegment>> storeSupplier;

    /** 文档元数据索引：file_name → DocEntry */
    private final Map<String, DocEntry> docIndex = new ConcurrentHashMap<>();

    /** 通过 store 供应商构造：每次操作解析当前 store（会话切换由供应商方控制）。 */
    public EmbeddingStoreManager(Supplier<EmbeddingStore<TextSegment>> storeSupplier) {
        this.storeSupplier = storeSupplier;
    }

    /** 静态 store 便捷构造器（暂留 shim，Task 5 删除）。 */
    public EmbeddingStoreManager(EmbeddingStore<TextSegment> store) {
        this(() -> store);
    }

    /**
     * 运行时切换底层存储（暂留 shim，Task 5 删除——新路径由 MilvusSession 原子切换承担）。
     */
    public synchronized void swapStore(EmbeddingStore<TextSegment> newStore) {
        this.storeSupplier = () -> newStore;
        this.docIndex.clear();
    }

    /** 整体替换文档索引——会话原子切换的第二半（与换 store 同步发生）。 */
    public void replaceAllIndex(Map<String, DocEntry> newIndex) {
        docIndex.clear();
        docIndex.putAll(newIndex);
    }

    public String add(Embedding embedding, TextSegment textSegment) {
        String id = storeSupplier.get().add(embedding, textSegment);
        indexDoc(textSegment);
        return id;
    }

    public List<String> addAll(List<Embedding> embeddings, List<TextSegment> textSegments) {
        List<String> ids = new ArrayList<>();
        for (int i = 0; i < embeddings.size(); i++) {
            ids.add(UUID.randomUUID().toString());
        }
        storeSupplier.get().addAll(ids, embeddings, textSegments);
        for (TextSegment seg : textSegments) {
            indexDoc(seg);
        }
        return ids;
    }

    private void indexDoc(TextSegment seg) {
        Map<String, Object> meta = seg.metadata().toMap();
        String fileName = (String) meta.get("file_name");
        if (fileName == null) return;
        String dir = (String) meta.get("absolute_directory_path");
        if (dir == null) dir = "";
        String fileType = (String) meta.get("file_type");
        if (fileType == null) fileType = "";
        docIndex.merge(fileName, new DocEntry(dir, fileType, 1), (old, n) -> {
            old.segmentCount += 1;
            return old;
        });
    }

    public EmbeddingSearchResult<TextSegment> search(EmbeddingSearchRequest request) {
        return storeSupplier.get().search(request);
    }

    public dev.langchain4j.rag.content.retriever.ContentRetriever createContentRetriever(
            dev.langchain4j.model.embedding.EmbeddingModel embeddingModel,
            int maxResults,
            double minScore) {
        return dev.langchain4j.rag.content.retriever.EmbeddingStoreContentRetriever.builder()
                .embeddingStore(storeSupplier.get())
                .embeddingModel(embeddingModel)
                .maxResults(maxResults)
                .minScore(minScore)
                .build();
    }

    /**
     * 返回文档元数据索引（file_name → DocEntry）。
     * 每次 add/addAll 自动维护，无需外部同步。
     */
    public Map<String, DocEntry> getDocumentIndex() {
        return docIndex;
    }

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * 从 Milvus 重建文档元数据索引，用于重启后恢复。
     * MilvusEmbeddingStore 将 metadata 存为单个 JSON 字段（{@code metadata}），
     * 这里查询全量实体的 metadata JSON，解析后按 file_name 分组统计。
     */
    @SuppressWarnings("unchecked")
    public void rebuildIndexFromMilvus(MilvusClientV2 milvusClient, String collectionName) {
        try {
            QueryReq req = QueryReq.builder()
                    .collectionName(collectionName)
                    .filter("id != \"\"")
                    .outputFields(List.of("metadata"))
                    .limit(10000)
                    .build();
            QueryResp resp = milvusClient.query(req);
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
                docIndex.merge(fileName, new DocEntry(dir, fileType, 1),
                        (old, n) -> { old.segmentCount += 1; return old; });
                count++;
            }
            log.info("从 Milvus 重建文档索引完成: {} 个文档, {} 个 chunk", docIndex.size(), count);
        } catch (Exception e) {
            log.warn("从 Milvus 重建文档索引失败 (Milvus 可能未启动): {}", e.getMessage());
        }
    }

    /**
     * 文档元数据条目。
     */
    public static class DocEntry {
        public String directory;
        public String fileType;
        public int segmentCount;

        public DocEntry(String directory, String fileType, int segmentCount) {
            this.directory = directory;
            this.fileType = fileType;
            this.segmentCount = segmentCount;
        }
    }
}
