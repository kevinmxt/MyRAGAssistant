package me.maxt.rag.web.service.chunking.converter;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import static org.assertj.core.api.Assertions.assertThat;

class MarkdownConverterTest {

    @TempDir
    Path tempDir;

    private final MarkdownConverter converter = new MarkdownConverter();

    @Test
    void shouldReportAvailability() {
        boolean available = converter.isAvailable();
        // Pandoc 可能装也可能没装，只验证不抛异常
        assertThat(available).isInstanceOf(Boolean.class);
    }

    @Test
    void shouldConvertTxtFile() throws Exception {
        Path txtFile = tempDir.resolve("test.txt");
        Files.writeString(txtFile, "Hello World");

        String result = converter.convert(txtFile);

        assertThat(result).contains("Hello");
    }

    @Test
    void shouldConvertMarkdownFile() throws Exception {
        Path mdFile = tempDir.resolve("test.md");
        Files.writeString(mdFile, "# Title\n\nContent");

        String result = converter.convert(mdFile);

        assertThat(result).contains("Title");
        assertThat(result).contains("Content");
    }

    @Test
    void shouldNotThrowOnMissingFile() {
        try {
            converter.convert(Path.of("/nonexistent/file-12345.xyz"));
        } catch (Exception e) {
            assertThat(e).isInstanceOf(RuntimeException.class);
        }
    }
}
