package me.maxt.rag.web.service;

import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.output.Response;
import dev.langchain4j.store.embedding.EmbeddingSearchRequest;
import dev.langchain4j.store.embedding.inmemory.InMemoryEmbeddingStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DocumentServiceTest {

    private String tempDir;
    private EmbeddingStoreManager storeManager;
    private EmbeddingModel embeddingModel;

    @BeforeEach
    void setUp() {
        InMemoryEmbeddingStore<TextSegment> store = new InMemoryEmbeddingStore<>();
        storeManager = new EmbeddingStoreManager(() -> store);
        embeddingModel = mock(EmbeddingModel.class);
    }

    @Test
    void shouldRejectNonExistentDirectory() {
        DocumentService service = new DocumentService(storeManager, embeddingModel, 300, 0, List.of(".txt"));
        assertThatThrownBy(() -> service.ingestDirectory("/nonexistent/path/xyz"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Directory not found");
    }

    @Test
    void shouldReturnEmptyResultWhenNoMatchingFiles() throws Exception {
        tempDir = Files.createTempDirectory("rag-test-empty").toString();
        DocumentService service = new DocumentService(storeManager, embeddingModel, 300, 0, List.of(".xyz"));
        DocumentService.IngestResult result = service.ingestDirectory(tempDir);
        assertThat(result.success).isFalse();
        assertThat(result.filesProcessed).isEqualTo(0);
        new File(tempDir).delete();
    }

    @Test
    void shouldIngestMatchingFiles() throws Exception {
        tempDir = Files.createTempDirectory("rag-test-ingest").toString();
        Path testFile = Path.of(tempDir, "test.txt");
        Files.writeString(testFile, "Sample document content for testing purposes.");

        @SuppressWarnings("unchecked")
        Response<Embedding> mockResponse = mock(Response.class);
        when(mockResponse.content()).thenReturn(Embedding.from(new float[]{0.5f, 0.5f}));

        @SuppressWarnings("unchecked")
        Response<List<Embedding>> mockListResponse = mock(Response.class);
        when(mockListResponse.content()).thenReturn(List.of(Embedding.from(new float[]{0.5f, 0.5f})));
        when(embeddingModel.embedAll(ArgumentMatchers.<List<TextSegment>>any())).thenReturn(mockListResponse);

        DocumentService service = new DocumentService(storeManager, embeddingModel, 300, 0, List.of(".txt"));
        DocumentService.IngestResult result = service.ingestDirectory(tempDir);

        assertThat(result.success).isTrue();
        assertThat(result.filesProcessed).isEqualTo(1);
        assertThat(result.segmentsCreated).isGreaterThan(0);

        // 通过搜索验证向量已入库（getEntryCount 已随 EmbeddingStore 抽象移除）
        assertThat(storeManager.search(EmbeddingSearchRequest.builder()
                .queryEmbedding(Embedding.from(new float[]{0.5f, 0.5f}))
                .maxResults(10)
                .minScore(0.5)
                .build()).matches()).isNotEmpty();

        Files.deleteIfExists(testFile);
        new File(tempDir).delete();
    }

    @Test
    void shouldListDocumentsEmpty() {
        // 通过 docIndex 追踪，listDocuments 应返回已索引文档
        TextSegment seg = TextSegment.from("hello");
        seg.metadata().put("file_name", "readme.txt");
        seg.metadata().put("file_type", "TXT");
        seg.metadata().put("absolute_directory_path", "/docs");
        storeManager.add(Embedding.from(new float[]{0.1f, 0.2f}), seg);

        DocumentService service = new DocumentService(storeManager, embeddingModel, 300, 0, List.of());
        List<DocumentService.DocumentSummary> docs = service.listDocuments();
        assertThat(docs).hasSize(1);
        assertThat(docs.get(0).fileName).isEqualTo("readme.txt");
        assertThat(docs.get(0).segmentCount).isEqualTo(1);
        assertThat(docs.get(0).fileType).isEqualTo("TXT");
        assertThat(docs.get(0).directory).isEqualTo("/docs");
    }

    @Test
    void shouldBrowseDirectory() throws Exception {
        tempDir = Files.createTempDirectory("rag-test-browse").toString();
        Path subDir = Files.createDirectory(Path.of(tempDir, "subfolder"));

        DocumentService service = new DocumentService(storeManager, embeddingModel, 300, 0, List.of());
        DocumentService.BrowseResult result = service.browseDirectory(tempDir);

        assertThat(result.currentPath).isEqualTo(new File(tempDir).getAbsolutePath());
        assertThat(result.directories).contains(new File(tempDir, "subfolder").getAbsolutePath());

        Files.deleteIfExists(subDir);
        new File(tempDir).delete();
    }

    @Test
    void shouldBrowseRoot() {
        DocumentService service = new DocumentService(storeManager, embeddingModel, 300, 0, List.of());
        DocumentService.BrowseResult result = service.browseDirectory("");
        assertThat(result.currentPath).isEmpty();
        assertThat(result.directories).isNotEmpty();
    }

    @Test
    void shouldRejectInvalidBrowsePath() {
        DocumentService service = new DocumentService(storeManager, embeddingModel, 300, 0, List.of());
        assertThatThrownBy(() -> service.browseDirectory("/nonexistent/path/xyz"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Directory not found");
    }
}
