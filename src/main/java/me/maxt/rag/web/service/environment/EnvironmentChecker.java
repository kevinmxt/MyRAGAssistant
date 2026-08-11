package me.maxt.rag.web.service.environment;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import me.maxt.rag.web.config.EnvCheckConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * 环境检测编排器：并行检测、安装队列、SSE 事件广播、启动日志。
 */
public class EnvironmentChecker {

    private static final Logger log = LoggerFactory.getLogger(EnvironmentChecker.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final EnvCheckConfig config;
    private final List<DependencyChecker> checkers;
    private final CopyOnWriteArrayList<Consumer<String>> sseListeners = new CopyOnWriteArrayList<>();
    private volatile List<CheckResult> results = new ArrayList<>();
    private volatile long checkDurationMs = 0;
    private final AtomicBoolean installInProgress = new AtomicBoolean(false);

    public EnvironmentChecker(EnvCheckConfig config, List<DependencyChecker> checkers) {
        this.config = config;
        this.checkers = checkers;
    }

    // ========== SSE 监听器管理 ==========

    /** 注册 SSE 事件监听器（一个外部 SSE 连接调用一次） */
    public void addSseListener(Consumer<String> listener) {
        sseListeners.add(listener);
        // 如果已有检测结果，立即推送当前快照
        if (!results.isEmpty()) {
            sendSseEvent(listener, "status", toJson());
        }
    }

    /** 移除 SSE 监听器 */
    public void removeSseListener(Consumer<String> listener) {
        sseListeners.remove(listener);
    }

    private void broadcast(String event, Map<String, Object> data) {
        try {
            String json = MAPPER.writeValueAsString(data);
            broadcastRaw(event, json);
        } catch (JsonProcessingException e) {
            log.warn("SSE JSON serialization failed: {}", e.getMessage());
        }
    }

    private void broadcastRaw(String event, String data) {
        String formatted = "event: " + event + "\ndata: " + data + "\n\n";
        for (Consumer<String> listener : sseListeners) {
            try {
                listener.accept(formatted);
            } catch (Exception e) {
                log.debug("SSE broadcast to listener failed: {}", e.getMessage());
            }
        }
    }

    private void sendSseEvent(Consumer<String> listener, String event, Map<String, Object> data) {
        try {
            String json = MAPPER.writeValueAsString(data);
            listener.accept("event: " + event + "\ndata: " + json + "\n\n");
        } catch (JsonProcessingException e) {
            log.warn("SSE JSON serialization failed: {}", e.getMessage());
        }
    }

    // ========== 检测 ==========

    /** 后台非阻塞启动检测 */
    public void run() {
        if (!config.isEnvCheckEnabled()) {
            log.info("环境检测已禁用 (environment.enabled=false)");
            return;
        }
        Thread t = new Thread(() -> {
            checkAll();
            // 日志输出
            log.info("\n{}", toReport());
            for (CheckResult r : results) {
                if (r.status() == CheckResult.Status.MISSING || r.status() == CheckResult.Status.ERROR) {
                    log.warn("环境依赖缺失: [{}] {}", r.name(), r.message());
                }
            }
        }, "env-check-startup");
        t.setDaemon(true);
        t.start();
    }

    /** 全量检测（同步，供 API 调用） */
    public synchronized List<CheckResult> checkAll() {
        long start = System.currentTimeMillis();

        broadcastRaw("check-start", "{\"total\":" + checkers.size() + "}");

        int poolSize = Math.min(4, checkers.size());
        ExecutorService pool = Executors.newFixedThreadPool(poolSize);
        List<Callable<CheckResult>> tasks = checkers.stream()
                .<Callable<CheckResult>>map(c -> c::check)
                .toList();

        List<CheckResult> resultList = new ArrayList<>();
        try {
            List<Future<CheckResult>> futures = pool.invokeAll(
                    tasks, config.getEnvCheckTimeoutSeconds(), TimeUnit.SECONDS);

            for (int i = 0; i < checkers.size(); i++) {
                Future<CheckResult> future = futures.get(i);
                CheckResult r;
                if (future.isDone()) {
                    r = future.get();
                } else {
                    r = CheckResult.skipped(checkers.get(i).name(), "检查超时");
                }
                resultList.add(r);
                broadcast("check-result", toJson(r));
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            for (DependencyChecker c : checkers) {
                CheckResult r = CheckResult.skipped(c.name(), "检测被中断");
                resultList.add(r);
                broadcast("check-result", toJson(r));
            }
        } catch (Exception e) {
            log.error("环境检测异常: {}", e.getMessage());
        } finally {
            pool.shutdownNow();
        }

        this.results = resultList;
        this.checkDurationMs = System.currentTimeMillis() - start;

        broadcast("status", toJson());
        return resultList;
    }

    // ========== 安装 ==========

    /** 尝试安装指定依赖（同步，供 API 调用） */
    public synchronized boolean install(String dependencyName) {
        if (!installInProgress.compareAndSet(false, true)) {
            return false; // 已有安装进行中
        }

        DependencyChecker target = checkers.stream()
                .filter(c -> c.name().equals(dependencyName))
                .findFirst().orElse(null);
        if (target == null || !target.canAutoInstall()) {
            installInProgress.set(false);
            return false;
        }

        try {
            // 标记为 INSTALLING
            CheckResult installing = new CheckResult(
                    target.name(), results.stream()
                    .filter(r -> r.name().equals(target.name()))
                    .findFirst().map(CheckResult::category).orElse(CheckResult.Category.PIP),
                    CheckResult.Status.INSTALLING, null, "正在安装...");
            updateResult(installing);
            broadcast("check-result", toJson(installing));

            boolean ok = target.autoInstall(line -> {
                broadcastRaw("install-log", "{\"name\":\"" + target.name() + "\",\"line\":" + toJsonString(line) + "}");
            });

            // 重检
            CheckResult afterCheck = target.check();
            updateResult(afterCheck);
            broadcast("check-result", toJson(afterCheck));

            String message = ok ? "安装成功" : "安装失败，请检查日志";
            broadcastRaw("install-done",
                    "{\"name\":\"" + target.name() + "\",\"success\":" + ok + ",\"message\":\"" + message + "\"}");
            broadcast("status", toJson());

            return ok;
        } catch (Exception e) {
            log.error("安装 {} 失败: {}", dependencyName, e.getMessage());
            broadcastRaw("install-done",
                    "{\"name\":\"" + dependencyName + "\",\"success\":false,\"message\":\"" + e.getMessage() + "\"}");
            return false;
        } finally {
            installInProgress.set(false);
        }
    }

    /** 是否有安装任务进行中 */
    public boolean isInstallInProgress() {
        return installInProgress.get();
    }

    // ========== 查询 ==========

    public List<CheckResult> getResults() {
        return results;
    }

    public long getCheckDurationMillis() {
        return checkDurationMs;
    }

    public Map<String, Object> getSummary() {
        int ok = 0, missing = 0, error = 0, skipped = 0;
        for (CheckResult r : results) {
            switch (r.status()) {
                case OK -> ok++;
                case MISSING, INSTALLING -> missing++;
                case ERROR -> error++;
                case SKIPPED -> skipped++;
            }
        }
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("total", results.size());
        summary.put("ok", ok);
        summary.put("missing", missing);
        summary.put("error", error);
        summary.put("skipped", skipped);
        return summary;
    }

    public Map<String, Object> toJson() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("enabled", config.isEnvCheckEnabled());
        map.put("autoInstall", config.isAutoInstallEnabled());
        map.put("checkDurationMs", checkDurationMs);
        map.put("summary", getSummary());
        map.put("installInProgress", installInProgress.get());

        List<Map<String, Object>> deps = new ArrayList<>();
        for (CheckResult r : results) {
            deps.add(toJson(r));
        }
        map.put("dependencies", deps);
        return map;
    }

    private Map<String, Object> toJson(CheckResult r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", r.name());
        m.put("category", r.category().name());
        m.put("status", r.status().name());
        m.put("version", r.version());
        m.put("message", r.message());
        m.put("canAutoInstall", checkers.stream()
                .filter(c -> c.name().equals(r.name()))
                .findFirst().map(DependencyChecker::canAutoInstall).orElse(false));
        return m;
    }

    public String toReport() {
        StringBuilder sb = new StringBuilder();
        sb.append("========== 环境检测 (").append(checkDurationMs).append("ms) ==========\n");
        for (CheckResult r : results) {
            String flag = switch (r.status()) {
                case OK -> "[OK]     ";
                case MISSING -> "[MISSING]";
                case INSTALLING -> "[INSTALL]";
                case ERROR -> "[ERROR]  ";
                case SKIPPED -> "[SKIPPED]";
            };
            sb.append(flag).append(" ").append(r.name());
            String ver = r.version();
            if (ver != null && !ver.isEmpty()) {
                sb.append(" (").append(ver).append(")");
            }
            sb.append(": ").append(r.message()).append("\n");
        }
        sb.append("==========================================");
        return sb.toString();
    }

    private void updateResult(CheckResult updated) {
        List<CheckResult> newList = new ArrayList<>(results);
        for (int i = 0; i < newList.size(); i++) {
            if (newList.get(i).name().equals(updated.name())) {
                newList.set(i, updated);
                break;
            }
        }
        this.results = newList;
    }

    private static String toJsonString(String s) {
        try {
            return MAPPER.writeValueAsString(s);
        } catch (JsonProcessingException e) {
            return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
        }
    }
}
