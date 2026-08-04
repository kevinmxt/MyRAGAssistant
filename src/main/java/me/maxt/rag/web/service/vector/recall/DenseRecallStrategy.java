package me.maxt.rag.web.service.vector.recall;

import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import dev.langchain4j.store.embedding.EmbeddingSearchRequest;
import dev.langchain4j.store.embedding.EmbeddingSearchResult;
import me.maxt.rag.web.service.EmbeddingStoreManager;

import java.util.List;

/** 稠密向量检索策略，委托 EmbeddingStoreManager */
public class DenseRecallStrategy implements RecallStrategy {

    private final EmbeddingStoreManager storeManager;
    private final EmbeddingModel embeddingModel;

    public DenseRecallStrategy(EmbeddingStoreManager storeManager, EmbeddingModel embeddingModel) {
        this.storeManager = storeManager;
        this.embeddingModel = embeddingModel;
    }

    @Override
    public String name() { return "dense"; }

    @Override
    public List<EmbeddingMatch<TextSegment>> recall(String query, int topK) {
        Embedding qEmbedding = embeddingModel.embed(query).content();
        EmbeddingSearchResult<TextSegment> result = storeManager.search(
                EmbeddingSearchRequest.builder()
                        .queryEmbedding(qEmbedding)
                        .maxResults(topK)
                        .minScore(0.0)
                        .build());
        return result.matches();
    }
}
