package me.maxt.rag.web.service.vector.recall;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;

/**
 * LightRAG Python 调用桥接层。
 * 通过 JPype 启动 Python 解释器，加载 lightrag 模块，提供 query/insert 方法。
 */
public class LightRagBridge implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(LightRagBridge.class);

    private final String pythonPath;
    private final String workingDir;
    private final String embeddingModelPath;
    private final String queryMode;
    private volatile boolean initialized = false;

    public LightRagBridge(String pythonPath, String workingDir,
                          String embeddingModelPath, String queryMode) {
        this.pythonPath = pythonPath;
        this.workingDir = workingDir;
        this.embeddingModelPath = embeddingModelPath;
        this.queryMode = queryMode;
    }

    public synchronized void init() {
        if (initialized) return;
        try {
            // JPype 启动 Python 解释器
            // jpype.startJVM(); -- 实际部署时取消注释
            // 加载 LightRAG 模块
            // PyModule lightrag = Py.import_("lightrag");
            // ...
            log.info("LightRagBridge initialized: python={}, workdir={}",
                    pythonPath, workingDir);
            initialized = true;
        } catch (Exception e) {
            log.error("Failed to initialize LightRagBridge", e);
            initialized = false;
        }
    }

    /** 检索：调用 lightrag.query() */
    @SuppressWarnings("unchecked")
    public List<String> query(String queryText, String mode) {
        if (!initialized) {
            log.warn("LightRagBridge not initialized, returning empty");
            return List.of();
        }
        // JPype 调用: lightrag.query(queryText, mode=mode)
        // PyObject result = lightrag.call("query", queryText, mode);
        // return result.asList().stream().map(Object::toString).toList();
        log.debug("LightRAG query: {} (mode={})", queryText, mode);
        return List.of(); // placeholder — 在集成测试中替换
    }

    /** 插入文档：调用 lightrag.insert() */
    public boolean insert(Map<String, String> docs) {
        if (!initialized) {
            log.warn("LightRagBridge not initialized, cannot insert");
            return false;
        }
        // JPype 调用: lightrag.insert(docs)
        log.info("LightRAG insert: {} documents", docs.size());
        return true; // placeholder
    }

    @Override
    public void close() {
        if (initialized) {
            // jpype.shutdownJVM();
            initialized = false;
        }
    }
}
