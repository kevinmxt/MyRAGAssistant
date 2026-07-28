package me.maxt.rag.web.service;

import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.document.DocumentSplitter;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.document.loader.FileSystemDocumentLoader;
import dev.langchain4j.data.document.parser.apache.tika.ApacheTikaDocumentParser;
import dev.langchain4j.data.document.splitter.DocumentSplitters;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

/**
 * 文档摄入服务，负责从目录加载多模态文档并进行分块和向量化。
 *
 * <p>支持的文件格式由 {@code supportedExtensions} 配置决定，默认为 TXT、PDF、DOCX、PNG 等常见格式。
 * 文档解析基于 Apache Tika，可自动识别 MIME 类型并选择合适的解析器。</p>
 *
 * @author maxt
 * @since 1.0
 */
public class DocumentService {

    private final EmbeddingStoreManager storeManager;
    private final EmbeddingModel embeddingModel;
    private final int chunkSize;
    private final int chunkOverlap;
    private final List<String> supportedExtensions;
    private static final Logger log = LoggerFactory.getLogger(DocumentService.class);

    /**
     * 创建文档服务实例。
     *
     * @param storeManager       嵌入存储管理器
     * @param embeddingModel     嵌入模型（共享实例）
     * @param chunkSize          文档分块大小（字符数）
     * @param chunkOverlap       分块重叠大小（字符数）
     * @param supportedExtensions 支持的文件扩展名列表，如 {@code [".txt", ".pdf", ".docx"]}
     */
    public DocumentService(EmbeddingStoreManager storeManager, EmbeddingModel embeddingModel,
                           int chunkSize, int chunkOverlap, List<String> supportedExtensions) {
        this.storeManager = storeManager;
        this.embeddingModel = embeddingModel;
        this.chunkSize = chunkSize;
        this.chunkOverlap = chunkOverlap;
        this.supportedExtensions = supportedExtensions;
    }

    /**
     * 摄入指定目录下的所有支持格式的文档。
     *
     * <p>该方法会递归扫描目录中的文件，过滤出支持的文件类型，使用 Apache Tika 解析文档内容，
     * 通过递归分块器将文档分割为文本片段，并对每个片段生成嵌入向量，最后持久化到向量存储中。</p>
     *
     * @param directoryPath 待摄入的文档目录路径
     * @return 摄入结果，包含处理的文件数、创建的片段数和状态消息
     * @throws IllegalArgumentException 如果目录不存在或不是有效目录
     */
    public IngestResult ingestDirectory(String directoryPath) {
        Path dir = Paths.get(directoryPath);
        File dirFile = dir.toFile();

        if (!dirFile.exists() || !dirFile.isDirectory()) {
            throw new IllegalArgumentException("Directory not found: " + directoryPath);
        }

        // Find all files with supported extensions
        File[] matchingFiles = dirFile.listFiles((d, name) -> {
            String lowerName = name.toLowerCase();
            for (String ext : supportedExtensions) {
                if (lowerName.endsWith(ext.toLowerCase())) {
                    return true;
                }
            }
            return false;
        });

        if (matchingFiles == null || matchingFiles.length == 0) {
            return new IngestResult(0, 0,
                    "No supported files found in directory: " + directoryPath
                    + " (supported: " + String.join(", ", supportedExtensions) + ")");
        }

        DocumentSplitter splitter = DocumentSplitters.recursive(chunkSize, chunkOverlap);
        ApacheTikaDocumentParser parser = new ApacheTikaDocumentParser();

        int filesProcessed = 0;
        int totalSegments = 0;

        for (File file : matchingFiles) {
            try {
                Document document = FileSystemDocumentLoader.loadDocument(file.toPath(), parser);
                List<TextSegment> segments = splitter.split(document);

                // Attach file metadata to each segment for traceability
                String fileName = file.getName();
                String fileType = detectFileType(fileName);
                for (TextSegment segment : segments) {
                    segment.metadata().put("file_name", fileName);
                    segment.metadata().put("file_type", fileType);
                }

                List<Embedding> embeddings = embeddingModel.embedAll(segments).content();
                storeManager.addAll(embeddings, segments);

                filesProcessed++;
                totalSegments += segments.size();
            } catch (Exception e) {
                log.error("Failed to process file: {}", file.getName(), e);
            }
        }

        return new IngestResult(filesProcessed, totalSegments,
                "Successfully processed " + filesProcessed + " files, created " + totalSegments + " segments.");
    }

    /**
     * 根据文件扩展名推断文件类型描述。
     */
    private static String detectFileType(String fileName) {
        String lower = fileName.toLowerCase();
        if (lower.endsWith(".pdf")) return "PDF";
        if (lower.endsWith(".docx")) return "DOCX";
        if (lower.endsWith(".doc")) return "DOC";
        if (lower.endsWith(".png")) return "PNG";
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) return "JPEG";
        if (lower.endsWith(".txt")) return "TXT";
        if (lower.endsWith(".md")) return "Markdown";
        if (lower.endsWith(".html")) return "HTML";
        if (lower.endsWith(".csv")) return "CSV";
        if (lower.endsWith(".json")) return "JSON";
        if (lower.endsWith(".xlsx")) return "XLSX";
        if (lower.endsWith(".pptx")) return "PPTX";
        return "Unknown";
    }

    /**
     * 文档摄入结果 DTO。
     */
    public static class IngestResult {
        public boolean success;
        public int filesProcessed;
        public int segmentsCreated;
        public String message;

        /**
         * 构造摄入结果。
         *
         * @param filesProcessed 处理的文件数
         * @param segmentsCreated 创建的分段数
         * @param message 状态消息
         */
        public IngestResult(int filesProcessed, int segmentsCreated, String message) {
            this.success = filesProcessed > 0;
            this.filesProcessed = filesProcessed;
            this.segmentsCreated = segmentsCreated;
            this.message = message;
        }
    }
}
