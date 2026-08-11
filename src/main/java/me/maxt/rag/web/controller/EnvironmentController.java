package me.maxt.rag.web.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.javalin.http.Context;
import me.maxt.rag.web.service.environment.EnvironmentChecker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;

/**
 * 环境管理 REST 端点。
 */
public class EnvironmentController {

    private static final Logger log = LoggerFactory.getLogger(EnvironmentController.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final EnvironmentChecker environmentChecker;

    public EnvironmentController(EnvironmentChecker environmentChecker) {
        this.environmentChecker = environmentChecker;
    }

    /** GET /api/env/status — 当前环境状态快照 */
    public void handleStatus(Context ctx) {
        ctx.json(environmentChecker.toJson());
    }

    /** POST /api/env/check — 触发全量重检（异步，结果通过 SSE 推送） */
    public void handleCheck(Context ctx) {
        ctx.json(Map.of("status", "started"));
        Thread t = new Thread(environmentChecker::checkAll, "env-check-api");
        t.setDaemon(true);
        t.start();
    }

    /** POST /api/env/install — 触发安装 {"name": "lightrag"} */
    public void handleInstall(Context ctx) {
        String name;
        try {
            Map<String, Object> body = MAPPER.readValue(ctx.body(), Map.class);
            name = (String) body.get("name");
        } catch (Exception e) {
            ctx.status(400).json(Map.of("error", "invalid request body"));
            return;
        }

        if (name == null || name.isEmpty()) {
            ctx.status(400).json(Map.of("error", "missing 'name' field"));
            return;
        }

        if (environmentChecker.isInstallInProgress()) {
            ctx.status(409).json(Map.of("error", "已有安装任务进行中"));
            return;
        }

        ctx.json(Map.of("status", "started", "name", name));

        Thread t = new Thread(() -> {
            environmentChecker.install(name);
        }, "env-install-" + name);
        t.setDaemon(true);
        t.start();
    }
}
