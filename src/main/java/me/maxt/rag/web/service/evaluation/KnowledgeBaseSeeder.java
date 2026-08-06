package me.maxt.rag.web.service.evaluation;

import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.document.loader.FileSystemDocumentLoader;
import dev.langchain4j.data.document.parser.apache.tika.ApacheTikaDocumentParser;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import me.maxt.rag.web.config.DocumentConfig;
import me.maxt.rag.web.service.EmbeddingStoreManager;
import me.maxt.rag.web.service.chunking.ChunkingPipeline;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

/**
 * 将评估用文档走完整切分→向量化→入库管线。
 * 使用独立的 InMemoryEmbeddingStore，不污染生产数据。
 */
public class KnowledgeBaseSeeder {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeBaseSeeder.class);

    private final EmbeddingStoreManager storeManager;
    private final EmbeddingModel embeddingModel;
    private final DocumentConfig docConfig;
    private final ChunkingPipeline chunkingPipeline;

    public KnowledgeBaseSeeder(EmbeddingStoreManager storeManager, EmbeddingModel embeddingModel,
                               DocumentConfig docConfig, ChunkingPipeline chunkingPipeline) {
        this.storeManager = storeManager;
        this.embeddingModel = embeddingModel;
        this.docConfig = docConfig;
        this.chunkingPipeline = chunkingPipeline;
    }

    /**
     * 将指定格式的 docs/ 目录下所有文档入库。
     *
     * @param format 格式名（如 "markdown"）
     * @return 成功入库的文档数
     */
    public int seed(String format) {
        // 使用 classpath 资源路径解析 docs 目录
        String docsResource = "evaluation/" + format + "/docs";
        var classLoader = getClass().getClassLoader();
        var url = classLoader.getResource(docsResource);
        if (url == null) {
            log.warn("评估文档目录不存在: {}", docsResource);
            return 0;
        }

        Path docsPath;
        try {
            docsPath = Paths.get(url.toURI());
        } catch (Exception e) {
            log.error("无法解析文档路径: {}", docsResource, e);
            return 0;
        }

        File dir = docsPath.toFile();
        if (!dir.exists() || !dir.isDirectory()) {
            log.warn("评估文档目录无效: {}", docsPath);
            return 0;
        }

        // 使用 Tika 解析器加载所有文档
        List<Document> documents = FileSystemDocumentLoader.loadDocuments(
                docsPath, new ApacheTikaDocumentParser());

        int count = 0;
        for (Document doc : documents) {
            try {
                List<TextSegment> chunks = chunk(doc);
                if (chunks.isEmpty()) {
                    // 降级：简单按整篇作为一个片段
                    chunks = List.of(TextSegment.from(doc.text()));
                }

                for (TextSegment chunk : chunks) {
                    Embedding embedding = embeddingModel.embed(chunk.text()).content();
                    storeManager.add(embedding, chunk);
                }
                count++;
            } catch (Exception e) {
                log.error("文档 {} 入库失败: {}", doc.metadata().getString("file_name"), e.getMessage());
            }
        }
        log.info("评估知识库入库完成: {} 个文档 (格式: {})", count, format);
        return count;
    }

    /**
     * 走 ChunkingPipeline 完整切分管线；管线不可用时返回空列表由调用方降级。
     */
    private List<TextSegment> chunk(Document doc) {
        if (chunkingPipeline == null) {
            return List.of();
        }
        String fileName = doc.metadata().getString("file_name");
        String dir = doc.metadata().getString("absolute_directory_path");
        String fileType = doc.metadata().getString("file_type");
        if (fileName == null || dir == null) {
            return List.of();
        }
        try {
            return chunkingPipeline.execute(Paths.get(dir, fileName), fileType != null ? fileType : "UNKNOWN");
        } catch (Exception e) {
            log.warn("文档 {} 切分失败，降级为整篇: {}", fileName, e.getMessage());
            return List.of();
        }
    }
}
