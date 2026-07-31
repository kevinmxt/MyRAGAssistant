package me.maxt.rag.web.service;

import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.store.embedding.EmbeddingSearchRequest;
import dev.langchain4j.store.embedding.EmbeddingSearchResult;
import dev.langchain4j.store.embedding.EmbeddingStore;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 向量存储管理器，封装 Milvus（或其他 EmbeddingStore 实现）。
 *
 * <p>构造函数接收 {@link EmbeddingStore<TextSegment>} 接口，
 * 生产环境注入 MilvusEmbeddingStore，测试注入 InMemoryEmbeddingStore。</p>
 */
public class EmbeddingStoreManager {

    private final EmbeddingStore<TextSegment> embeddingStore;

    public EmbeddingStoreManager(EmbeddingStore<TextSegment> store) {
        this.embeddingStore = store;
    }

    public String add(Embedding embedding, TextSegment textSegment) {
        // langchain4j 1.x 的 EmbeddingStore 接口已移除 add(id, embedding, segment) 三参数方法，
        // id 由实现内部生成（UUID）并返回
        return embeddingStore.add(embedding, textSegment);
    }

    public List<String> addAll(List<Embedding> embeddings, List<TextSegment> textSegments) {
        List<String> ids = new ArrayList<>();
        for (int i = 0; i < embeddings.size(); i++) {
            ids.add(UUID.randomUUID().toString());
        }
        embeddingStore.addAll(ids, embeddings, textSegments);
        return ids;
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
}
