package me.maxt.rag.web.controller;

import io.javalin.http.Context;
import me.maxt.rag.web.service.KnowledgeGraphService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;

/** KG 构建和管理 API 控制器 */
public class KnowledgeGraphController {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeGraphController.class);
    private final KnowledgeGraphService kgService;

    public KnowledgeGraphController(KnowledgeGraphService kgService) {
        this.kgService = kgService;
    }

    public void handleBuildForDirectory(Context ctx) {
        String path = ctx.queryParam("path");
        if (path == null || path.trim().isEmpty()) {
            ctx.status(400).json(Map.of("error", "path parameter is required"));
            return;
        }
        log.info("Building KG for directory: {}", path);
        boolean ok = kgService.buildForDirectory(path.trim());
        ctx.json(Map.of("success", ok, "status", kgService.getStatus()));
    }

    public void handleBuildForDocument(Context ctx) {
        String docId = ctx.pathParam("docId");
        if (docId == null || docId.trim().isEmpty()) {
            ctx.status(400).json(Map.of("error", "docId is required"));
            return;
        }
        log.info("Building KG for document: {}", docId);
        boolean ok = kgService.buildForDocument(docId.trim());
        Map<String, Object> status = kgService.getStatus();
        Map<String, Object> resp = new java.util.LinkedHashMap<>();
        resp.put("success", ok);
        resp.put("status", status);
        if (!ok) {
            String err = (String) status.getOrDefault("lastError", "");
            resp.put("error", err.isEmpty() ? "构建失败" : err);
        }
        ctx.json(resp);
    }

    public void handleGetStatus(Context ctx) {
        ctx.json(kgService.getStatus());
    }
}
