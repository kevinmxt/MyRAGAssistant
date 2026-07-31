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

    @Test
    void shouldTrackDocumentIndex() {
        TextSegment seg = TextSegment.from("content");
        seg.metadata().put("file_name", "test.pdf");
        seg.metadata().put("file_type", "PDF");
        seg.metadata().put("absolute_directory_path", "/docs");
        mgr.add(Embedding.from(new float[]{0.5f}), seg);

        assertThat(mgr.getDocumentIndex()).containsKey("test.pdf");
        EmbeddingStoreManager.DocEntry entry = mgr.getDocumentIndex().get("test.pdf");
        assertThat(entry.segmentCount).isEqualTo(1);
        assertThat(entry.fileType).isEqualTo("PDF");
        assertThat(entry.directory).isEqualTo("/docs");
    }

    @Test
    void shouldMergeSegmentCountInIndex() {
        TextSegment seg1 = TextSegment.from("part1");
        seg1.metadata().put("file_name", "doc.txt");
        TextSegment seg2 = TextSegment.from("part2");
        seg2.metadata().put("file_name", "doc.txt");

        mgr.add(Embedding.from(new float[]{0.1f}), seg1);
        mgr.add(Embedding.from(new float[]{0.2f}), seg2);

        assertThat(mgr.getDocumentIndex().get("doc.txt").segmentCount).isEqualTo(2);
    }
}
