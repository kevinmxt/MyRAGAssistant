package me.maxt.rag.web.service.chunking.converter;

import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.document.loader.FileSystemDocumentLoader;
import dev.langchain4j.data.document.parser.apache.tika.ApacheTikaDocumentParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

public class MarkdownConverter {

    private static final Logger log = LoggerFactory.getLogger(MarkdownConverter.class);
    private final boolean pandocAvailable;

    public MarkdownConverter() {
        this.pandocAvailable = checkPandoc();
        if (pandocAvailable) {
            log.info("Pandoc detected — 将使用 Pandoc 进行文档转 Markdown");
        } else {
            log.info("Pandoc 未安装，将使用 Tika 作为 fallback");
        }
    }

    public boolean isAvailable() {
        return pandocAvailable;
    }

    public String convert(Path filePath) {
        if (pandocAvailable) {
            try {
                return convertWithPandoc(filePath);
            } catch (Exception e) {
                log.warn("Pandoc 转换失败，降级到 Tika: {}", filePath, e);
            }
        }
        return convertWithTika(filePath);
    }

    private String convertWithPandoc(Path filePath) throws IOException, InterruptedException {
        ProcessBuilder pb = new ProcessBuilder(
                "pandoc", filePath.toString(),
                "-t", "markdown",
                "--wrap=none",
                "--extract-media=./data/media"
        );
        pb.redirectErrorStream(true);
        Process process = pb.start();

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        process.getInputStream().transferTo(out);

        if (!process.waitFor(60, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            throw new IOException("Pandoc 超时: " + filePath);
        }

        if (process.exitValue() != 0) {
            throw new IOException("Pandoc 返回非零: " + process.exitValue());
        }

        return out.toString(StandardCharsets.UTF_8);
    }

    private String convertWithTika(Path filePath) {
        ApacheTikaDocumentParser parser = new ApacheTikaDocumentParser();
        Document document = FileSystemDocumentLoader.loadDocument(filePath, parser);
        return document.text();
    }

    private static boolean checkPandoc() {
        try {
            ProcessBuilder pb = new ProcessBuilder("pandoc", "--version");
            Process process = pb.start();
            boolean finished = process.waitFor(5, TimeUnit.SECONDS);
            return finished && process.exitValue() == 0;
        } catch (Exception e) {
            return false;
        }
    }
}
