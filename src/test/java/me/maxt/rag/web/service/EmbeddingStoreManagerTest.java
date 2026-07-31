package me.maxt.rag.web.service;

import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.store.embedding.EmbeddingSearchRequest;
import dev.langchain4j.store.embedding.EmbeddingSearchResult;
import dev.langchain4j.store.embedding.inmemory.InMemoryEmbeddingStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class EmbeddingStoreManagerTest {

    private InMemoryEmbeddingStore<TextSegment> memoryStore;
    private EmbeddingStoreManager mgr;

    @BeforeEach
    void setUp() {
        memoryStore = new InMemoryEmbeddingStore<>();
        mgr = new EmbeddingStoreManager(memoryStore);
    }

    @Test
    void shouldAddAndSearch() {
        float[] vector = {0.1f, 0.2f, 0.3f};
        TextSegment segment = TextSegment.from("hello world");
        String id = mgr.add(Embedding.from(vector), segment);

        assertThat(id).isNotEmpty();

        EmbeddingSearchRequest request = EmbeddingSearchRequest.builder()
                .queryEmbedding(Embedding.from(new float[]{0.1f, 0.2f, 0.3f}))
                .maxResults(5)
                .minScore(0.5)
                .build();
        EmbeddingSearchResult<TextSegment> result = mgr.search(request);
        assertThat(result.matches()).hasSize(1);
        assertThat(result.matches().get(0).embedded().text()).isEqualTo("hello world");
    }

    @Test
    void shouldAddAllBatch() {
        List<Embedding> embeddings = List.of(
                Embedding.from(new float[]{1.0f, 0.0f}),
                Embedding.from(new float[]{0.0f, 1.0f})
        );
        List<TextSegment> segments = List.of(
                TextSegment.from("first"),
                TextSegment.from("second")
        );

        List<String> ids = mgr.addAll(embeddings, segments);
        assertThat(ids).hasSize(2);
    }

    @Test
    void shouldCreateContentRetriever() {
        dev.langchain4j.model.embedding.EmbeddingModel model =
                org.mockito.Mockito.mock(dev.langchain4j.model.embedding.EmbeddingModel.class);
        dev.langchain4j.rag.content.retriever.ContentRetriever retriever =
                mgr.createContentRetriever(model, 3, 0.5);
        assertThat(retriever).isNotNull();
    }

    @Test
    void shouldFilterByMinScore() {
        mgr.add(Embedding.from(new float[]{1.0f, 0.0f}), TextSegment.from("target"));

        EmbeddingSearchRequest request = EmbeddingSearchRequest.builder()
                .queryEmbedding(Embedding.from(new float[]{0.0f, 1.0f}))
                .maxResults(5)
                .minScore(0.9)
                .build();
        EmbeddingSearchResult<TextSegment> result = mgr.search(request);
        assertThat(result.matches()).isEmpty();
    }
}
