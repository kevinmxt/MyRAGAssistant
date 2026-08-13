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

    private volatile EmbeddingStore<TextSegment> embeddingStore;

    /** 文档元数据索引：file_name → DocEntry */
    private final Map<String, DocEntry> docIndex = new ConcurrentHashMap<>();

    public EmbeddingStoreManager(EmbeddingStore<TextSegment> store) {
        this.embeddingStore = store;
    }

    /**
     * 运行时切换底层存储（如 Milvus 重连成功后从内存存储切回 Milvus）。
     * 切换后清空文档索引，由调用方决定是否从新存储重建。
     */
    public synchronized void swapStore(EmbeddingStore<TextSegment> newStore) {
        this.embeddingStore = newStore;
        this.docIndex.clear();
    }

    public String add(Embedding embedding, TextSegment textSegment) {
        String id = embeddingStore.add(embedding, textSegment);
        indexDoc(textSegment);
        return id;
    }

    public List<String> addAll(List<Embedding> embeddings, List<TextSegment> textSegments) {
        List<String> ids = new ArrayList<>();
        for (int i = 0; i < embeddings.size(); i++) {
            ids.add(UUID.randomUUID().toString());
        }
        embeddingStore.addAll(ids, embeddings, textSegments);
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
        return embeddingStore.search(request);
    }

    public dev.langchain4j.rag.content.retriever.ContentRetriever createContentRetriever(
            dev.langchain4j.model.embedding.EmbeddingModel embeddingModel,
            int maxResults,
            double minScore) {
        return dev.langchain4j.rag.content.retriever.EmbeddingStoreContentRetriever.builder()
                .embeddingStore(embeddingStore)
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
