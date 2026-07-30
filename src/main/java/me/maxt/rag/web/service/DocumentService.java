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
import me.maxt.rag.web.service.chunking.ChunkingPipeline;
import me.maxt.rag.web.service.vector.ContextualEnricher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

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
    private final ChunkingPipeline chunkingPipeline;
    private final ContextualEnricher contextualEnricher;
    private static final Logger log = LoggerFactory.getLogger(DocumentService.class);

    /**
     * 创建文档服务实例（向后兼容，使用旧的递归切分）。
     *
     * @param storeManager       嵌入存储管理器
     * @param embeddingModel     嵌入模型（共享实例）
     * @param chunkSize          文档分块大小（字符数）
     * @param chunkOverlap       分块重叠大小（字符数）
     * @param supportedExtensions 支持的文件扩展名列表，如 {@code [".txt", ".pdf", ".docx"]}
     */
    public DocumentService(EmbeddingStoreManager storeManager, EmbeddingModel embeddingModel,
                           int chunkSize, int chunkOverlap, List<String> supportedExtensions) {
        this(storeManager, embeddingModel, chunkSize, chunkOverlap, supportedExtensions, null);
    }

    /**
     * 创建文档服务实例。
     *
     * @param storeManager       嵌入存储管理器
     * @param embeddingModel     嵌入模型（共享实例）
     * @param chunkSize          文档分块大小（字符数）
     * @param chunkOverlap       分块重叠大小（字符数）
     * @param supportedExtensions 支持的文件扩展名列表
     * @param chunkingPipeline   Chunking 管线（为 null 时降级为递归字符切分）
     */
    public DocumentService(EmbeddingStoreManager storeManager, EmbeddingModel embeddingModel,
                           int chunkSize, int chunkOverlap, List<String> supportedExtensions,
                           ChunkingPipeline chunkingPipeline) {
        this(storeManager, embeddingModel, chunkSize, chunkOverlap, supportedExtensions, chunkingPipeline, null);
    }

    /**
     * 创建文档服务实例（含上下文增强器）。
     *
     * @param storeManager       嵌入存储管理器
     * @param embeddingModel     嵌入模型（共享实例）
     * @param chunkSize          文档分块大小（字符数）
     * @param chunkOverlap       分块重叠大小（字符数）
     * @param supportedExtensions 支持的文件扩展名列表
     * @param chunkingPipeline   Chunking 管线（为 null 时降级为递归字符切分）
     * @param contextualEnricher 上下文增强器（为 null 时不增强）
     */
    public DocumentService(EmbeddingStoreManager storeManager, EmbeddingModel embeddingModel,
                           int chunkSize, int chunkOverlap, List<String> supportedExtensions,
                           ChunkingPipeline chunkingPipeline, ContextualEnricher contextualEnricher) {
        this.storeManager = storeManager;
        this.embeddingModel = embeddingModel;
        this.chunkSize = chunkSize;
        this.chunkOverlap = chunkOverlap;
        this.supportedExtensions = supportedExtensions;
        this.chunkingPipeline = chunkingPipeline;
        this.contextualEnricher = contextualEnricher;
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

        ApacheTikaDocumentParser parser = new ApacheTikaDocumentParser();

        int filesProcessed = 0;
        int totalSegments = 0;

        for (File file : matchingFiles) {
            try {
                Document document = FileSystemDocumentLoader.loadDocument(file.toPath(), parser);
                List<TextSegment> segments;
                if (chunkingPipeline != null) {
                    try {
                        segments = chunkingPipeline.execute(file.toPath(), detectFileType(file.getName()));
                    } catch (Exception e) {
                        log.warn("新切分管线失败，降级到递归字符切分: {}", file.getName(), e);
                        DocumentSplitter fallbackSplitter = DocumentSplitters.recursive(chunkSize, chunkOverlap);
                        segments = fallbackSplitter.split(document);
                    }
                } else {
                    DocumentSplitter fallbackSplitter = DocumentSplitters.recursive(chunkSize, chunkOverlap);
                    segments = fallbackSplitter.split(document);
                }

                // Attach file metadata to each segment for traceability
                String fileName = file.getName();
                String fileType = detectFileType(fileName);
                for (TextSegment segment : segments) {
                    segment.metadata().put("file_name", fileName);
                    segment.metadata().put("file_type", fileType);
                }

                // 上下文增强：在嵌入前为每个 chunk 加上文件/标题前缀
                if (contextualEnricher != null) {
                    segments = contextualEnricher.enrich(segments, fileName);
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
     * 列出已索引的文档，按文件名分组聚合。
     *
     * @return 文档摘要列表
     */
    public List<DocumentSummary> listDocuments() {
        List<EmbeddingStoreManager.StoredEntry> entries = storeManager.listDocuments();

        Map<String, List<EmbeddingStoreManager.StoredEntry>> grouped = entries.stream()
                .collect(Collectors.groupingBy(e -> {
                    Map<String, Object> meta = e.getMetadata();
                    String fileName = meta != null ? (String) meta.get("file_name") : null;
                    return fileName != null ? fileName : "unknown";
                }));

        List<DocumentSummary> documents = new ArrayList<>();
        for (Map.Entry<String, List<EmbeddingStoreManager.StoredEntry>> group : grouped.entrySet()) {
            Map<String, Object> meta = group.getValue().get(0).getMetadata();
            String dir = meta != null ? (String) meta.get("absolute_directory_path") : null;
            String fileType = meta != null ? (String) meta.get("file_type") : null;
            documents.add(new DocumentSummary(
                    group.getKey(),
                    group.getValue().size(),
                    dir != null ? dir : "",
                    fileType != null ? fileType : ""));
        }
        return documents;
    }

    /**
     * 浏览文件系统目录，返回子目录列表。
     *
     * @param path 目录路径，空字符串表示查询根目录
     * @return 浏览结果
     */
    public BrowseResult browseDirectory(String path) {
        if (path == null || path.trim().isEmpty()) {
            return browseRoot();
        }

        File dir = new File(path.trim());
        if (!dir.exists() || !dir.isDirectory()) {
            throw new IllegalArgumentException("Directory not found: " + path);
        }

        String parentPath = dir.getParent();
        File[] subDirs = dir.listFiles(File::isDirectory);
        List<String> directories = new ArrayList<>();
        if (subDirs != null) {
            for (File sub : subDirs) {
                directories.add(sub.getAbsolutePath());
            }
            directories.sort(String.CASE_INSENSITIVE_ORDER);
        }

        return new BrowseResult(dir.getAbsolutePath(), parentPath, directories);
    }

    private BrowseResult browseRoot() {
        List<String> roots = new ArrayList<>();
        File[] rootDirs = File.listRoots();
        if (rootDirs != null) {
            for (File root : rootDirs) {
                roots.add(root.getAbsolutePath());
            }
        }
        return new BrowseResult("", null, roots);
    }

    /** 已索引文档摘要。 */
    public static class DocumentSummary {
        public String fileName;
        public int segmentCount;
        public String directory;
        public String fileType;

        public DocumentSummary(String fileName, int segmentCount, String directory, String fileType) {
            this.fileName = fileName;
            this.segmentCount = segmentCount;
            this.directory = directory;
            this.fileType = fileType;
        }
    }

    /** 目录浏览结果。 */
    public static class BrowseResult {
        public String currentPath;
        public String parentPath;
        public List<String> directories;

        public BrowseResult(String currentPath, String parentPath, List<String> directories) {
            this.currentPath = currentPath;
            this.parentPath = parentPath;
            this.directories = directories;
        }
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
