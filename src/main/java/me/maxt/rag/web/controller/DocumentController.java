package me.maxt.rag.web.controller;

import io.javalin.http.Context;
import me.maxt.rag.web.service.DocumentService;
import me.maxt.rag.web.service.EmbeddingStoreManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;

/**
 * 文档管理控制器，薄层：JSON 解析 → 调 service → JSON 序列化。
 *
 * @author maxt
 * @since 1.0
 */
public class DocumentController {

    private static final Logger log = LoggerFactory.getLogger(DocumentController.class);

    private final DocumentService documentService;
    private final EmbeddingStoreManager storeManager;

    public DocumentController(DocumentService documentService, EmbeddingStoreManager storeManager) {
        this.documentService = documentService;
        this.storeManager = storeManager;
    }

    /**
     * 处理文档摄入请求：POST /api/ingest，Body: {"directory": "/path/to/docs"}
     */
    public void handleIngest(Context ctx) {
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> body = ctx.bodyAsClass(Map.class);
            String directory = (String) body.get("directory");

            if (directory == null || directory.trim().isEmpty()) {
                ctx.status(400).json(Map.of("error", "Directory path is required"));
                return;
            }

            log.info("Ingesting documents from: {}", directory);
            DocumentService.IngestResult result = documentService.ingestDirectory(directory.trim());
            ctx.json(result);
        } catch (IllegalArgumentException e) {
            ctx.status(400).json(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            log.error("Ingest error", e);
            ctx.status(500).json(Map.of("error", "Failed to ingest documents: " + e.getMessage()));
        }
    }

    /**
     * 处理文档列表查询：GET /api/documents
     */
    public void handleListDocuments(Context ctx) {
        try {
            ctx.json(documentService.listDocuments());
        } catch (Exception e) {
            log.error("List documents error", e);
            ctx.status(500).json(Map.of("error", "Failed to list documents: " + e.getMessage()));
        }
    }

    /**
     * 处理目录浏览请求：POST /api/browse，Body: {"path": "C:/some/dir"}
     */
    public void handleBrowse(Context ctx) {
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> body = ctx.bodyAsClass(Map.class);
            String path = (String) body.get("path");
            ctx.json(documentService.browseDirectory(path != null ? path : ""));
        } catch (IllegalArgumentException e) {
            ctx.status(400).json(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            log.error("Browse error", e);
            ctx.status(500).json(Map.of("error", "Failed to browse directory: " + e.getMessage()));
        }
    }
}
