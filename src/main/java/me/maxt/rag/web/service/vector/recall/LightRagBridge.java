package me.maxt.rag.web.service.vector.recall;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * LightRAG Python 调用桥接层。
 *
 * <p>以常驻 Python 子进程方式调用 LightRAG：Java 启动 {@code python lightrag_bridge.py}，
 * 每次请求在 stdin 上写一行 JSON、在 stdout 上读一行 JSON 响应（请求-响应严格一一对应），
 * 子进程 stderr 中的日志由后台线程接管输出到 Java 日志。子进程常驻避免了每次调用
 * 重新加载 embedding model 的开销。</p>
 *
 * <p>部署要求：Python 环境已安装 {@code lightrag}、{@code requests} 包，且 lightrag
 * 需要的 embedding model 路径可用。任何初始化失败都会使本桥接层降级为不可用，
 * 由调用方（GraphRecallStrategy / KnowledgeGraphService）返回空结果，不影响其他召回路。</p>
 */
public class LightRagBridge implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(LightRagBridge.class);

    /** Python bridge 脚本的 classpath 资源路径（Maven 打包在 jar 内） */
    private static final String BRIDGE_SCRIPT_RESOURCE = "/lightrag/lightrag_bridge.py";

    private final ObjectMapper mapper = new ObjectMapper();

    private final String pythonPath;
    private final String workingDir;
    private final String embeddingModelPath;
    private final String queryMode;
    private final String apiKey;
    private final String baseUrl;
    private final String modelName;

    /** 请求-响应串行化锁（协议是严格一问一答，不支持并发） */
    private final Object protocolLock = new Object();

    private volatile boolean initialized = false;
    private Process process;
    private BufferedReader protocolReader;
    private BufferedWriter protocolWriter;

    public LightRagBridge(String pythonPath, String workingDir,
                          String embeddingModelPath, String queryMode) {
        this(pythonPath, workingDir, embeddingModelPath, queryMode, null, null, null);
    }

    public LightRagBridge(String pythonPath, String workingDir,
                          String embeddingModelPath, String queryMode,
                          String apiKey, String baseUrl, String modelName) {
        this.pythonPath = pythonPath;
        this.workingDir = workingDir;
        this.embeddingModelPath = embeddingModelPath;
        this.queryMode = queryMode;
        this.apiKey = apiKey;
        this.baseUrl = baseUrl;
        this.modelName = modelName;
    }

    /**
     * 初始化：把 bridge 脚本从 classpath 提取到工作目录，启动 Python 子进程，
     * 发送 init 请求（内部创建 LightRAG 实例并加载 embedding model）。
     */
    public synchronized void init() {
        if (initialized) return;
        try {
            String scriptPath = extractBridgeScript();
            String interpreter = (pythonPath == null || pythonPath.isBlank()) ? "python" : pythonPath;
            log.info("Starting LightRAG bridge process: {} {}", interpreter, scriptPath);
            process = new ProcessBuilder(interpreter, scriptPath).start();
            protocolReader = new BufferedReader(new InputStreamReader(
                    process.getInputStream(), StandardCharsets.UTF_8));
            protocolWriter = new BufferedWriter(new OutputStreamWriter(
                    process.getOutputStream(), StandardCharsets.UTF_8));
            startLogDrainer();

            JsonNode result = sendRequest("init", Map.of(
                    "workingDir", workingDir,
                    "embeddingModelPath", embeddingModelPath == null ? "" : embeddingModelPath,
                    "apiKey", apiKey == null ? "" : apiKey,
                    "baseUrl", baseUrl == null ? "" : baseUrl,
                    "modelName", modelName == null ? "" : modelName));
            if (!result.asBoolean(false)) {
                throw new IllegalStateException("LightRAG init returned false");
            }
            log.info("LightRagBridge initialized: python={}, workdir={}", interpreter, workingDir);
            initialized = true;
        } catch (Exception e) {
            log.error("Failed to initialize LightRagBridge, graph recall will be unavailable", e);
            closeQuietly();
        }
    }

    /** 检索：向 Python 子进程发送 query 请求，返回结果字符串列表 */
    public List<String> query(String queryText, String mode) {
        if (!initialized) {
            log.warn("LightRagBridge not initialized, returning empty");
            return List.of();
        }
        try {
            JsonNode result = sendRequest("query", Map.of(
                    "text", queryText,
                    "mode", mode == null ? queryMode : mode));
            List<String> out = new ArrayList<>();
            if (result.isArray()) {
                result.forEach(n -> out.add(n.asText()));
            }
            log.debug("LightRAG query returned {} results", out.size());
            return out;
        } catch (Exception e) {
            log.warn("LightRAG query failed: {}", e.getMessage());
            return List.of();
        }
    }

    /** 插入文档：向 Python 子进程发送 insert 请求，docs 为 {file_name: text_content} */
    public boolean insert(Map<String, String> docs) {
        if (!initialized) {
            log.warn("LightRagBridge not initialized, cannot insert");
            return false;
        }
        if (docs == null || docs.isEmpty()) {
            log.warn("LightRAG insert called with empty docs");
            return false;
        }
        try {
            JsonNode result = sendRequest("insert", Map.of("docs", docs));
            boolean ok = result.asBoolean(false);
            log.info("LightRAG insert: {} documents -> {}", docs.size(), ok);
            return ok;
        } catch (Exception e) {
            log.error("LightRAG insert failed", e);
            return false;
        }
    }

    /**
     * 发送一条请求并同步等待响应。协议严格一问一答，须持 protocolLock。
     * 子进程异常退出时 readLine 返回 null 并抛异常。
     */
    private JsonNode sendRequest(String cmd, Map<String, Object> payload) throws IOException {
        Map<String, Object> req = new LinkedHashMap<>(payload);
        req.put("cmd", cmd);
        String requestJson = mapper.writeValueAsString(req);
        synchronized (protocolLock) {
            protocolWriter.write(requestJson);
            protocolWriter.newLine();
            protocolWriter.flush();
            String line = protocolReader.readLine();
            if (line == null) {
                throw new IOException("lightrag_bridge process exited unexpectedly (cmd=" + cmd + ")");
            }
            JsonNode resp = mapper.readTree(line);
            if (!resp.path("ok").asBoolean(false)) {
                throw new IOException("lightrag_bridge error: " + resp.path("error").asText());
            }
            return resp.path("result");
        }
    }

    /** 后台线程把子进程 stderr 日志转发到 Java 日志 */
    private void startLogDrainer() {
        Thread drainer = new Thread(() -> {
            try (BufferedReader err = new BufferedReader(new InputStreamReader(
                    process.getErrorStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = err.readLine()) != null) {
                    log.debug("[lightrag] {}", line);
                }
            } catch (IOException e) {
                // 进程退出导致的流关闭，忽略
            }
        }, "lightrag-stderr-drain");
        drainer.setDaemon(true);
        drainer.start();
    }

    /**
     * 从 classpath 提取 bridge 脚本到工作目录下的 lightrag/ 子目录，返回脚本绝对路径。
     * 打包为 Fat JAR 后 classpath 资源无法被 Python 直接执行，必须落到文件系统。
     */
    private String extractBridgeScript() throws IOException {
        Path scriptDir = Paths.get(workingDir, "lightrag");
        Path target = scriptDir.resolve("lightrag_bridge.py");
        try (InputStream in = LightRagBridge.class.getResourceAsStream(BRIDGE_SCRIPT_RESOURCE)) {
            if (in == null) {
                throw new IOException("Cannot find bridge script in classpath: " + BRIDGE_SCRIPT_RESOURCE);
            }
            Files.createDirectories(scriptDir);
            Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
        }
        return target.toString();
    }

    private void closeQuietly() {
        try {
            close();
        } catch (Exception e) {
            log.debug("Error while closing LightRagBridge: {}", e.getMessage());
        }
    }

    @Override
    public synchronized void close() {
        if (process != null && process.isAlive()) {
            try {
                // 优雅退出：发送 exit 命令后等待进程自行结束
                sendRequest("exit", Map.of());
            } catch (Exception e) {
                log.debug("Exit request failed: {}", e.getMessage());
            }
            process.destroy();
            try {
                if (!process.waitFor(5, TimeUnit.SECONDS)) {
                    process.destroyForcibly();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                process.destroyForcibly();
            }
        }
        initialized = false;
        process = null;
        protocolReader = null;
        protocolWriter = null;
    }
}
