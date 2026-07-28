package me.maxt.rag.web.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.javalin.http.Context;
import me.maxt.rag.web.service.DocumentService;
import me.maxt.rag.web.service.EmbeddingStoreManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 文档管理控制器，处理文档摄入、浏览和列表相关的 HTTP 请求。
 *
 * @author maxt
 * @since 1.0
 */
public class DocumentController {

    private static final Logger log = LoggerFactory.getLogger(DocumentController.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final DocumentService documentService;
    private final EmbeddingStoreManager storeManager;

    /**
     * @param documentService 文档摄入服务
     * @param storeManager    嵌入存储管理器
     */
    public DocumentController(DocumentService documentService, EmbeddingStoreManager storeManager) {
        this.documentService = documentService;
        this.storeManager = storeManager;
    }

    /**
     * 处理文档摄入请求。
     *
     * <p>请求格式：{@code POST /api/ingest}，Body: {@code {"directory": "/path/to/docs"}}</p>
     * <p>响应格式：{@code {"success": true, "filesProcessed": 3, "segmentsCreated": 45, "message": "..."}}</p>
     *
     * @param ctx Javalin HTTP 上下文
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
     * 处理文档列表查询请求。
     *
     * <p>请求格式：{@code GET /api/documents}</p>
     * <p>响应格式：JSON 数组，每个元素包含 {@code fileName}、{@code segmentCount}、{@code directory}</p>
     *
     * @param ctx Javalin HTTP 上下文
     */
    public void handleListDocuments(Context ctx) {
        try {
            List<EmbeddingStoreManager.StoredEntry> entries = storeManager.listDocuments();

            // Group by file name to produce document list
            Map<String, List<EmbeddingStoreManager.StoredEntry>> grouped = entries.stream()
                    .collect(Collectors.groupingBy(e -> {
                        Map<String, Object> meta = e.getMetadata();
                        String fileName = meta != null ?
                                (String) meta.get("file_name") : null;
                        return fileName != null ? fileName : "unknown";
                    }));

            List<Map<String, Object>> documents = new ArrayList<>();
            for (Map.Entry<String, List<EmbeddingStoreManager.StoredEntry>> group : grouped.entrySet()) {
                Map<String, Object> doc = new LinkedHashMap<>();
                doc.put("fileName", group.getKey());
                doc.put("segmentCount", group.getValue().size());

                // Get directory and file type from metadata of first entry
                Map<String, Object> meta = group.getValue().get(0).getMetadata();
                String dir = meta != null ? (String) meta.get("absolute_directory_path") : null;
                doc.put("directory", dir != null ? dir : "");

                String fileType = meta != null ? (String) meta.get("file_type") : null;
                doc.put("fileType", fileType != null ? fileType : "");

                documents.add(doc);
            }

            ctx.json(documents);
        } catch (Exception e) {
            log.error("List documents error", e);
            ctx.status(500).json(Map.of("error", "Failed to list documents: " + e.getMessage()));
        }
    }

    /**
     * 处理目录浏览请求，返回指定路径下的子目录列表。
     *
     * <p>请求格式：{@code POST /api/browse}，Body: {@code {"path": "C:/some/dir"}}</p>
     * <p>当 path 为空时，在 Windows 上返回系统根目录列表（驱动器列表），在 Unix 上返回根目录。</p>
     * <p>响应格式：</p>
     * <pre>{@code
     * {
     *   "currentPath": "C:/some/dir",
     *   "parentPath": "C:/some",
     *   "directories": ["dir1", "dir2", ...]
     * }
     * }</pre>
     *
     * @param ctx Javalin HTTP 上下文
     */
    public void handleBrowse(Context ctx) {
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> body = ctx.bodyAsClass(Map.class);
            String path = (String) body.get("path");

            // If path is empty or null, return root drives (Windows) or root (Unix)
            if (path == null || path.trim().isEmpty()) {
                ctx.json(browseRoot());
                return;
            }

            File dir = new File(path.trim());
            if (!dir.exists() || !dir.isDirectory()) {
                ctx.status(400).json(Map.of("error", "Directory not found: " + path));
                return;
            }

            // Get parent path
            String parentPath = null;
            if (dir.getParent() != null) {
                parentPath = dir.getParent();
            }

            // List subdirectories only
            File[] subDirs = dir.listFiles(File::isDirectory);
            List<String> directories = new ArrayList<>();
            if (subDirs != null) {
                for (File sub : subDirs) {
                    directories.add(sub.getAbsolutePath());
                }
                directories.sort(String.CASE_INSENSITIVE_ORDER);
            }

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("currentPath", dir.getAbsolutePath());
            result.put("parentPath", parentPath);
            result.put("directories", directories);

            ctx.json(result);
        } catch (Exception e) {
            log.error("Browse error", e);
            ctx.status(500).json(Map.of("error", "Failed to browse directory: " + e.getMessage()));
        }
    }

    /**
     * 浏览根目录，返回系统的根目录列表。
     * Windows 上返回驱动器列表（C:\、D:\ 等），Unix 上返回 "/"。
     */
    private Map<String, Object> browseRoot() {
        List<String> roots = new ArrayList<>();
        File[] rootDirs = File.listRoots();
        if (rootDirs != null) {
            for (File root : rootDirs) {
                roots.add(root.getAbsolutePath());
            }
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("currentPath", "");
        result.put("parentPath", null);
        result.put("directories", roots);
        return result;
    }
}
