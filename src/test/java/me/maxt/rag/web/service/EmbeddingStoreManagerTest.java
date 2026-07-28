package me.maxt.rag.web.service;

import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.store.embedding.EmbeddingSearchRequest;
import dev.langchain4j.store.embedding.EmbeddingSearchResult;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.nio.file.Paths;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class EmbeddingStoreManagerTest {

    private String tempFilePath;

    @BeforeEach
    void setUp() {
        tempFilePath = "target/test-store-" + System.currentTimeMillis() + ".json";
    }

    @AfterEach
    void tearDown() {
        File file = new File(tempFilePath);
        if (file.exists()) file.delete();
        File tmpFile = new File(tempFilePath + ".tmp");
        if (tmpFile.exists()) tmpFile.delete();
    }

    private EmbeddingStoreManager createManager() {
        return new EmbeddingStoreManager(tempFilePath);
    }

    @Test
    void shouldStartEmpty() {
        EmbeddingStoreManager mgr = createManager();
        assertThat(mgr.getEntryCount()).isEqualTo(0);
        assertThat(mgr.listDocuments()).isEmpty();
    }

    @Test
    void shouldAddAndSearch() {
        EmbeddingStoreManager mgr = createManager();
        float[] vector = {0.1f, 0.2f, 0.3f};
        TextSegment segment = TextSegment.from("hello world");
        String id = mgr.add(Embedding.from(vector), segment);

        assertThat(id).isNotEmpty();
        assertThat(mgr.getEntryCount()).isEqualTo(1);

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
        EmbeddingStoreManager mgr = createManager();
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
        assertThat(mgr.getEntryCount()).isEqualTo(2);
    }

    @Test
    void shouldListDocuments() {
        EmbeddingStoreManager mgr = createManager();
        TextSegment seg = TextSegment.from("content");
        seg.metadata().put("file_name", "test.pdf");
        seg.metadata().put("file_type", "PDF");
        seg.metadata().put("absolute_directory_path", "/docs");
        mgr.add(Embedding.from(new float[]{1.0f, 2.0f}), seg);

        List<EmbeddingStoreManager.StoredEntry> entries = mgr.listDocuments();
        assertThat(entries).hasSize(1);
        assertThat(entries.get(0).getText()).isEqualTo("content");
        assertThat(entries.get(0).getMetadata()).containsEntry("file_name", "test.pdf");
    }

    @Test
    void shouldRemoveAll() {
        EmbeddingStoreManager mgr = createManager();
        mgr.add(Embedding.from(new float[]{1.0f}), TextSegment.from("data"));
        assertThat(mgr.getEntryCount()).isEqualTo(1);

        mgr.removeAll();
        assertThat(mgr.getEntryCount()).isEqualTo(0);
        assertThat(mgr.listDocuments()).isEmpty();
    }

    @Test
    void shouldPersistAndReload() {
        EmbeddingStoreManager mgr1 = createManager();
        TextSegment seg = TextSegment.from("persistent content");
        seg.metadata().put("file_name", "doc.txt");
        mgr1.add(Embedding.from(new float[]{0.5f, 0.6f}), seg);

        // Reload from same file
        EmbeddingStoreManager mgr2 = new EmbeddingStoreManager(tempFilePath);
        assertThat(mgr2.getEntryCount()).isEqualTo(1);
        List<EmbeddingStoreManager.StoredEntry> entries = mgr2.listDocuments();
        assertThat(entries.get(0).getText()).isEqualTo("persistent content");
    }

    @Test
    void shouldCreateContentRetriever() {
        EmbeddingStoreManager mgr = createManager();
        dev.langchain4j.model.embedding.EmbeddingModel model = org.mockito.Mockito.mock(
                dev.langchain4j.model.embedding.EmbeddingModel.class);
        dev.langchain4j.rag.content.retriever.ContentRetriever retriever =
                mgr.createContentRetriever(model, 3, 0.5);
        assertThat(retriever).isNotNull();
    }

    @Test
    void shouldFilterByMinScore() {
        EmbeddingStoreManager mgr = createManager();
        mgr.add(Embedding.from(new float[]{1.0f, 0.0f}), TextSegment.from("target"));

        // Search with orthogonal vector — cosine similarity ≈ 0, gets filtered
        EmbeddingSearchRequest request = EmbeddingSearchRequest.builder()
                .queryEmbedding(Embedding.from(new float[]{0.0f, 1.0f}))
                .maxResults(5)
                .minScore(0.9)
                .build();
        EmbeddingSearchResult<TextSegment> result = mgr.search(request);
        assertThat(result.matches()).isEmpty();
    }
}
