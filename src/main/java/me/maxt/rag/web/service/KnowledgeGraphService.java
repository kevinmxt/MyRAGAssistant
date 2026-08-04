package me.maxt.rag.web.service;

import me.maxt.rag.web.config.RecallConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * LightRAG 知识图谱构建和管理服务。
 * 通过 JPype 调用 Python LightRAG 库进行索引构建和检索。
 * 索引由用户通过 API 手动触发，支持按目录或单文档构建。
 */
public class KnowledgeGraphService {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeGraphService.class);

    private final RecallConfig config;
    private final EmbeddingStoreManager storeManager;
    private final AtomicBoolean built = new AtomicBoolean(false);
    private final AtomicReference<String> buildStatus = new AtomicReference<>("idle");
    private final Set<String> indexedDocs = ConcurrentHashMap.newKeySet();
    private final Object pythonLock = new Object();

    public KnowledgeGraphService(RecallConfig config, EmbeddingStoreManager storeManager) {
        this.config = config;
        this.storeManager = storeManager;
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
                String fileName = entry.getKey();
                EmbeddingStoreManager.DocEntry docEntry = entry.getValue();
                if (directoryPath == null || directoryPath.isEmpty()
                        || docEntry.directory.startsWith(directoryPath)
                        || docEntry.directory.equals(directoryPath)) {
                    docs.put(fileName, ""); // 占位，实际文本需要从 Milvus 查询
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

    // LightRAG 检索入口，由 GraphRecallStrategy 调用
    public List<String> query(String queryText, String mode) {
        // TODO: 实际 lightrag query 调用在 Task 6 (GraphRecallStrategy) 中实现
        // 这里预留接口
        return List.of();
    }

    private boolean runLightRagInsert(Map<String, String> docs) {
        // JPype 调用 LightRAG 的 Python 方法
        // 实际实现在 GraphRecallStrategy 集成时完成（Task 6）
        log.info("LightRAG insert called for {} documents (placeholder)", docs.size());
        return true; // placeholder
    }
}
