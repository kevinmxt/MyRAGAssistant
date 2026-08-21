package me.maxt.rag.web.service;

import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.store.embedding.EmbeddingSearchRequest;
import dev.langchain4j.store.embedding.EmbeddingSearchResult;
import dev.langchain4j.store.embedding.EmbeddingStore;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * 向量存储门面：通过 store 供应商解析当前活跃存储，并维护文档元数据索引。
 *
 * <p>构造函数接收 {@link Supplier Supplier&lt;EmbeddingStore&gt;}，
 * 存储切换（降级/重连）由供应商方（向量库会话）原子控制，本类不再持有切换逻辑。</p>
 *
 * <p>内部维护轻量级文档元数据索引，支持 {@link #getDocumentIndex()} 查询已索引文档摘要。</p>
 */
public class EmbeddingStoreManager {

    /** store 供应商：每次操作解析当前 store（会话切换由供应商方原子控制） */
    private final Supplier<EmbeddingStore<TextSegment>> storeSupplier;

    /** 文档元数据索引：file_name → DocEntry */
    private final Map<String, DocEntry> docIndex = new ConcurrentHashMap<>();

    /** 通过 store 供应商构造：每次操作解析当前 store（会话切换由供应商方控制）。 */
    public EmbeddingStoreManager(Supplier<EmbeddingStore<TextSegment>> storeSupplier) {
        this.storeSupplier = storeSupplier;
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
