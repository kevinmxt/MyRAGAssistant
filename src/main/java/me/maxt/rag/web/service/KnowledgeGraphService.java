package me.maxt.rag.web.service;

import io.milvus.v2.client.MilvusClientV2;
import io.milvus.v2.service.vector.request.QueryReq;
import io.milvus.v2.service.vector.response.QueryResp;
import me.maxt.rag.web.config.RecallConfig;
import me.maxt.rag.web.service.vector.recall.LightRagBridge;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * LightRAG 知识图谱构建和管理服务。
 * 通过 LightRagBridge（JPype）调用 Python LightRAG 库进行索引构建和检索。
 * 索引由用户通过 API 手动触发，支持按目录或单文档构建。
 */
public class KnowledgeGraphService {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeGraphService.class);

    /** 单文档回查 Milvus 的 chunk 数量上限 */
    private static final long MAX_CHUNKS_PER_DOC = 10000;

    private final RecallConfig config;
    private final EmbeddingStoreManager storeManager;
    private final MilvusClientV2 milvusClient;
    private final LightRagBridge bridge;
    private final AtomicBoolean built = new AtomicBoolean(false);
    private final AtomicReference<String> buildStatus = new AtomicReference<>("idle");
    private final Set<String> indexedDocs = ConcurrentHashMap.newKeySet();

    public KnowledgeGraphService(RecallConfig config, EmbeddingStoreManager storeManager,
                                 MilvusClientV2 milvusClient, LightRagBridge bridge) {
        this.config = config;
        this.storeManager = storeManager;
        this.milvusClient = milvusClient;
        this.bridge = bridge;
    }

    public boolean buildForDirectory(String directoryPath) {
        if (storeManager == null) {
            log.warn("EmbeddingStoreManager not available, cannot build KG");
            return false;
        }
        buildStatus.set("building");
        try {
            // 收集该目录下所有文档内容
            Map<String, String> docs = new LinkedHashMap<>();
            for (Map.Entry<String, EmbeddingStoreManager.DocEntry> entry :
                    storeManager.getDocumentIndex().entrySet()) {
                EmbeddingStoreManager.DocEntry docEntry = entry.getValue();
                if (isInDirectory(docEntry, directoryPath)) {
                    docs.put(entry.getKey(), "");
                }
            }
            if (docs.isEmpty()) {
                log.warn("No documents found in directory: {}", directoryPath);
                built.set(false);
                buildStatus.set("no_documents");
                return false;
            }
            // 调用 LightRAG Python 脚本构建图谱
            boolean success = runLightRagInsert(docs);
            if (success) {
                indexedDocs.addAll(docs.keySet());
                built.set(true);
                buildStatus.set("completed");
            } else {
                buildStatus.set("failed");
            }
            return success;
        } catch (Exception e) {
            log.error("KG build failed for directory: {}", directoryPath, e);
            buildStatus.set("failed: " + e.getMessage());
            return false;
        }
    }

    public boolean buildForDocument(String docId) {
        if (storeManager == null) {
            log.warn("EmbeddingStoreManager not available, cannot build KG for document: {}", docId);
            return false;
        }
        buildStatus.set("building");
        try {
            Map<String, EmbeddingStoreManager.DocEntry> docIndex = storeManager.getDocumentIndex();
            EmbeddingStoreManager.DocEntry entry = docIndex.get(docId);
            if (entry == null) {
                log.warn("Document not found: {}", docId);
                buildStatus.set("not_found");
                return false;
            }
            Map<String, String> docs = Map.of(docId, "");
            boolean success = runLightRagInsert(docs);
            if (success) {
                indexedDocs.add(docId);
                built.set(true);
                buildStatus.set("completed");
            } else {
                buildStatus.set("failed");
            }
            return success;
        } catch (Exception e) {
            log.error("KG build failed for document: {}", docId, e);
            buildStatus.set("failed: " + e.getMessage());
            return false;
        }
    }

    public Map<String, Object> getStatus() {
        Map<String, Object> status = new LinkedHashMap<>();
        status.put("built", built.get());
        status.put("buildStatus", buildStatus.get());
        status.put("indexedDocuments", new ArrayList<>(indexedDocs));
        status.put("workingDir", config.getLightRagWorkingDir());
        return status;
    }

    public String getWorkingDir() { return config.getLightRagWorkingDir(); }

    /** 已索引文档集合，供 GraphRecallStrategy 检查图谱是否就绪 */
    public boolean isBuilt() { return built.get(); }

    /** LightRAG 检索入口，由 GraphRecallStrategy 调用，委托给 LightRagBridge */
    public List<String> query(String queryText, String mode) {
        if (bridge == null) {
            log.warn("LightRagBridge not available, cannot query");
            return List.of();
        }
        return bridge.query(queryText, mode);
    }

    /**
     * 目录归属判断：directoryPath 为空表示全部文档；
     * 否则要求文档目录与目标目录完全相等或位于其子目录（含路径分隔符边界，
     * 避免 "C:/a" 误匹配 "C:/ab"）。
     */
    private boolean isInDirectory(EmbeddingStoreManager.DocEntry docEntry, String directoryPath) {
        if (directoryPath == null || directoryPath.isEmpty()) {
            return true;
        }
        String dir = docEntry.directory;
        if (dir == null) {
            return false;
        }
        return dir.equals(directoryPath)
                || dir.startsWith(directoryPath + "/")
                || dir.startsWith(directoryPath + "\\");
    }

    /**
     * 调用 LightRAG 插入文档：先从 Milvus 按 file_name 回查 chunk 文本并拼接，
     * 再通过 LightRagBridge 交给 Python LightRAG 建立索引。
     */
    private boolean runLightRagInsert(Map<String, String> docs) {
        if (bridge == null) {
            log.warn("LightRagBridge not available, cannot build KG");
            return false;
        }
        if (milvusClient == null) {
            log.warn("MilvusClientV2 not available, cannot load document text");
            return false;
        }
        Map<String, String> docsWithText = new LinkedHashMap<>();
        for (String fileName : docs.keySet()) {
            String text = loadDocumentText(fileName);
            if (text == null || text.isBlank()) {
                log.warn("No text found in Milvus for document {}, skipping", fileName);
                continue;
            }
            docsWithText.put(fileName, text);
        }
        if (docsWithText.isEmpty()) {
            log.warn("No document text loaded from Milvus, KG build aborted");
            return false;
        }
        return bridge.insert(docsWithText);
    }

    /** 从 Milvus 按 file_name 过滤查询所有 chunk 的 text 字段并拼接 */
    private String loadDocumentText(String fileName) {
        try {
            QueryReq req = QueryReq.builder()
                    .collectionName(config.getMilvusCollectionName())
                    .filter("file_name == \"" + escapeFilterValue(fileName) + "\"")
                    .outputFields(List.of("text"))
                    .limit(MAX_CHUNKS_PER_DOC)
                    .build();
            QueryResp resp = milvusClient.query(req);
            StringBuilder sb = new StringBuilder();
            for (QueryResp.QueryResult qr : resp.getQueryResults()) {
                Object text = qr.getEntity().get("text");
                if (text != null) {
                    sb.append(text).append('\n');
                }
            }
            return sb.toString().trim();
        } catch (Exception e) {
            log.warn("Failed to load document text from Milvus for {}: {}", fileName, e.getMessage());
            return null;
        }
    }

    /** 转义 Milvus 过滤表达式字符串字面量中的双引号与反斜杠 */
    private String escapeFilterValue(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
