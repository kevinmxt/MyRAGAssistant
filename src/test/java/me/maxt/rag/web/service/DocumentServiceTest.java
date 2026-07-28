package me.maxt.rag.web.service;

import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.output.Response;
import org.junit.jupiter.api.AfterEach;
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
    private String storeFilePath;

    @BeforeEach
    void setUp() {
        storeFilePath = "target/test-ds-store-" + System.currentTimeMillis() + ".json";
        storeManager = new EmbeddingStoreManager(storeFilePath);
        embeddingModel = mock(EmbeddingModel.class);
    }

    @AfterEach
    void tearDown() {
        for (String f : new String[]{storeFilePath, storeFilePath + ".tmp"}) {
            File file = new File(f);
            if (file.exists()) file.delete();
        }
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
        assertThat(storeManager.getEntryCount()).isGreaterThan(0);

        Files.deleteIfExists(testFile);
        new File(tempDir).delete();
    }

    @Test
    void shouldListDocumentsWithSummary() {
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
        assertThat(docs.get(0).directory).isEqualTo("/docs");
        assertThat(docs.get(0).fileType).isEqualTo("TXT");
    }

    @Test
    void shouldListDocumentsEmpty() {
        DocumentService service = new DocumentService(storeManager, embeddingModel, 300, 0, List.of());
        assertThat(service.listDocuments()).isEmpty();
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
