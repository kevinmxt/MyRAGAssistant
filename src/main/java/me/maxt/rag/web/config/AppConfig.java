package me.maxt.rag.web.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * 应用配置管理类，负责从 config.json 和环境变量中加载配置。
 *
 * <p>配置加载优先级链（后者覆盖前者）：</p>
 * <ol>
 *   <li>代码中的默认值</li>
 *   <li>工作目录下的 config.json 文件</li>
 *   <li>环境变量（如 {@code RAG_LLM_API_KEY}）</li>
 * </ol>
 *
 * <p>配置分为以下几个部分：LLM 参数、检索参数、文档参数、对话参数、服务器参数、存储参数。</p>
 *
 * @author maxt
 * @since 1.0
 */
public class AppConfig implements LlmConfig, RetrievalConfig, DocumentConfig, ServerConfig {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Logger log = LoggerFactory.getLogger(AppConfig.class);

    // ========== LLM 配置 ==========

    /** DeepSeek API Key，可通过环境变量 {@code RAG_LLM_API_KEY} 覆盖 */
    private String apiKey;

    /** DeepSeek API 基础地址，可通过环境变量 {@code RAG_LLM_BASE_URL} 覆盖 */
    private String baseUrl;

    /** 模型名称，可通过环境变量 {@code RAG_LLM_MODEL_NAME} 覆盖 */
    private String modelName;

    /** 系统提示词，可通过环境变量 {@code RAG_LLM_SYSTEM_PROMPT} 覆盖 */
    private String systemPrompt;

    /** 模型温度参数（0~1），可通过环境变量 {@code RAG_LLM_TEMPERATURE} 覆盖 */
    private double temperature;

    /** 最大输出 Token 数，可通过环境变量 {@code RAG_LLM_MAX_TOKENS} 覆盖 */
    private int maxTokens;

    /** API 超时秒数，可通过环境变量 {@code RAG_LLM_TIMEOUT} 覆盖 */
    private int timeoutSeconds;

    // ========== 检索参数 ==========

    /** 检索返回的最大结果数，可通过环境变量 {@code RAG_RETRIEVAL_MAX_RESULTS} 覆盖 */
    private int maxResults;

    /** 检索最低相似度阈值（0~1），可通过环境变量 {@code RAG_RETRIEVAL_MIN_SCORE} 覆盖 */
    private double minScore;

    // ========== 文档参数 ==========

    /** 默认文档目录，可通过环境变量 {@code RAG_DOCUMENT_DIR} 覆盖 */
    private String documentDir;

    /** 文档分块大小（字符数），可通过环境变量 {@code RAG_CHUNK_SIZE} 覆盖 */
    private int chunkSize;

    /** 文档分块重叠大小（字符数），可通过环境变量 {@code RAG_CHUNK_OVERLAP} 覆盖 */
    private int chunkOverlap;

    // ========== 对话参数 ==========

    /** 对话记忆窗口大小（消息数），可通过环境变量 {@code RAG_CHAT_MEMORY_SIZE} 覆盖 */
    private int memorySize;

    // ========== 服务器参数 ==========

    /** HTTP 服务器端口，可通过环境变量 {@code RAG_SERVER_PORT} 覆盖 */
    private int port;

    // ========== 存储参数 ==========

    /** 向量存储文件路径，可通过环境变量 {@code RAG_STORE_PATH} 覆盖 */
    private String storeFilePath;

    // ========== 文档解析参数 ==========

    /** 支持的文件扩展名列表，可通过环境变量 {@code RAG_SUPPORTED_EXTENSIONS}（逗号分隔）覆盖 */
    private List<String> supportedFileExtensions;

    // ========== Chunking 参数 ==========

    /** 分块模式："auto" | "structure" | "semantic" | "recursive"，可通过环境变量 {@code RAG_CHUNKING_MODE} 覆盖 */
    private String chunkingMode;

    /** 语义断点相似度阈值，可通过环境变量 {@code RAG_CHUNKING_SEMANTIC_THRESHOLD} 覆盖 */
    private double semanticThreshold;

    /** 是否启用 Agent 精炼，可通过环境变量 {@code RAG_CHUNKING_AGENT_REFINER} 覆盖 */
    private boolean enableAgentRefiner;

    /** 单块最大字符数上限，可通过环境变量 {@code RAG_CHUNKING_MAX_SIZE} 覆盖 */
    private int maxChunkSize;

    /**
     * 使用默认值构造配置实例。
     */
    public AppConfig() {
        // Set defaults
        this.apiKey = "demo";
        this.baseUrl = "https://api.deepseek.com";
        this.modelName = "deepseek-v4-flash";
        this.systemPrompt = "你是一个基于本地知识库的智能助手，请根据提供的文档内容回答用户问题。如果文档中没有相关信息，请如实告知。";
        this.temperature = 0.7;
        this.maxTokens = 4096;
        this.timeoutSeconds = 120;
        this.maxResults = 3;
        this.minScore = 0.5;
        this.documentDir = "./documents";
        this.chunkSize = 300;
        this.chunkOverlap = 0;
        this.memorySize = 10;
        this.port = 8080;
        this.storeFilePath = "./data/embedding-store.json";
        this.supportedFileExtensions = Arrays.asList(
                ".txt", ".pdf", ".docx", ".doc", ".png", ".jpg", ".jpeg",
                ".md", ".html", ".csv", ".json", ".xlsx", ".pptx");
        this.chunkingMode = "auto";
        this.semanticThreshold = 0.6;
        this.enableAgentRefiner = false;
        this.maxChunkSize = 2000;
    }

    /**
     * 按优先级链加载配置：代码默认 → config.json → 环境变量。
     *
     * @return 加载完成的配置实例
     */
    public static AppConfig load() {
        AppConfig config = new AppConfig();

        // 1. Try loading config.json from working directory
        File configFile = new File(System.getProperty("user.dir"), "config.json");
        if (configFile.exists()) {
            try {
                @SuppressWarnings("unchecked")
                Map<String, Object> fileConfig = MAPPER.readValue(configFile, Map.class);
                applyFileConfig(config, fileConfig);
            } catch (IOException e) {
                log.warn("Failed to parse config.json, using defaults.", e);
            }
        }

        // 2. Apply environment variable overrides
        applyEnvOverrides(config);

        return config;
    }

    /**
     * 从 config.json 解析的 Map 中读取各组配置并应用到实例。
     */
    @SuppressWarnings("unchecked")
    private static void applyFileConfig(AppConfig config, Map<String, Object> fileConfig) {
        Map<String, Object> llm = (Map<String, Object>) fileConfig.get("llm");
        if (llm != null) {
            config.apiKey = getString(llm, "apiKey", config.apiKey);
            config.baseUrl = getString(llm, "baseUrl", config.baseUrl);
            config.modelName = getString(llm, "modelName", config.modelName);
            config.systemPrompt = getString(llm, "systemPrompt", config.systemPrompt);
            config.temperature = getDouble(llm, "temperature", config.temperature);
            config.maxTokens = getInt(llm, "maxTokens", config.maxTokens);
            config.timeoutSeconds = getInt(llm, "timeoutSeconds", config.timeoutSeconds);
        }

        Map<String, Object> retrieval = (Map<String, Object>) fileConfig.get("retrieval");
        if (retrieval != null) {
            config.maxResults = getInt(retrieval, "maxResults", config.maxResults);
            config.minScore = getDouble(retrieval, "minScore", config.minScore);
        }

        Map<String, Object> document = (Map<String, Object>) fileConfig.get("document");
        if (document != null) {
            config.documentDir = getString(document, "dir", config.documentDir);
            config.chunkSize = getInt(document, "chunkSize", config.chunkSize);
            config.chunkOverlap = getInt(document, "chunkOverlap", config.chunkOverlap);

            // supportedExtensions can be a JSON array or comma-separated string
            Object extObj = document.get("supportedExtensions");
            if (extObj instanceof List) {
                @SuppressWarnings("unchecked")
                List<String> extList = (List<String>) extObj;
                config.supportedFileExtensions = extList;
            } else if (extObj instanceof String) {
                config.supportedFileExtensions = Arrays.asList(((String) extObj).split(","));
            }

            // Chunking config
            Map<String, Object> chunking = (Map<String, Object>) document.get("chunking");
            if (chunking != null) {
                config.chunkingMode = getString(chunking, "mode", config.chunkingMode);
                config.semanticThreshold = getDouble(chunking, "semanticThreshold", config.semanticThreshold);
                config.enableAgentRefiner = getBoolean(chunking, "enableAgentRefiner", config.enableAgentRefiner);
                config.maxChunkSize = getInt(chunking, "maxChunkSize", config.maxChunkSize);
            }
        }

        Map<String, Object> chat = (Map<String, Object>) fileConfig.get("chat");
        if (chat != null) {
            config.memorySize = getInt(chat, "memorySize", config.memorySize);
        }

        Map<String, Object> server = (Map<String, Object>) fileConfig.get("server");
        if (server != null) {
            config.port = getInt(server, "port", config.port);
        }

        Map<String, Object> store = (Map<String, Object>) fileConfig.get("store");
        if (store != null) {
            config.storeFilePath = getString(store, "filePath", config.storeFilePath);
        }
    }

    /**
     * 应用环境变量覆盖配置值。
     */
    private static void applyEnvOverrides(AppConfig config) {
        config.apiKey = env("RAG_LLM_API_KEY", config.apiKey);
        config.baseUrl = env("RAG_LLM_BASE_URL", config.baseUrl);
        config.modelName = env("RAG_LLM_MODEL_NAME", config.modelName);
        config.systemPrompt = env("RAG_LLM_SYSTEM_PROMPT", config.systemPrompt);
        config.temperature = envDouble("RAG_LLM_TEMPERATURE", config.temperature);
        config.maxTokens = envInt("RAG_LLM_MAX_TOKENS", config.maxTokens);
        config.timeoutSeconds = envInt("RAG_LLM_TIMEOUT", config.timeoutSeconds);
        config.maxResults = envInt("RAG_RETRIEVAL_MAX_RESULTS", config.maxResults);
        config.minScore = envDouble("RAG_RETRIEVAL_MIN_SCORE", config.minScore);
        config.chunkSize = envInt("RAG_CHUNK_SIZE", config.chunkSize);
        config.chunkOverlap = envInt("RAG_CHUNK_OVERLAP", config.chunkOverlap);
        config.memorySize = envInt("RAG_CHAT_MEMORY_SIZE", config.memorySize);
        config.port = envInt("RAG_SERVER_PORT", config.port);
        config.documentDir = env("RAG_DOCUMENT_DIR", config.documentDir);
        config.storeFilePath = env("RAG_STORE_PATH", config.storeFilePath);
        String extEnv = System.getenv("RAG_SUPPORTED_EXTENSIONS");
        if (extEnv != null && !extEnv.isEmpty()) {
            config.supportedFileExtensions = Arrays.asList(extEnv.split(","));
        }
        config.chunkingMode = env("RAG_CHUNKING_MODE", config.chunkingMode);
        config.semanticThreshold = envDouble("RAG_CHUNKING_SEMANTIC_THRESHOLD", config.semanticThreshold);
        config.enableAgentRefiner = envBool("RAG_CHUNKING_AGENT_REFINER", config.enableAgentRefiner);
        config.maxChunkSize = envInt("RAG_CHUNKING_MAX_SIZE", config.maxChunkSize);
    }

    private static String getString(Map<String, Object> map, String key, String defaultVal) {
        Object val = map.get(key);
        return (val instanceof String) ? (String) val : defaultVal;
    }

    private static int getInt(Map<String, Object> map, String key, int defaultVal) {
        Object val = map.get(key);
        if (val instanceof Integer) return (Integer) val;
        if (val instanceof Number) return ((Number) val).intValue();
        return defaultVal;
    }

    private static double getDouble(Map<String, Object> map, String key, double defaultVal) {
        Object val = map.get(key);
        if (val instanceof Double) return (Double) val;
        if (val instanceof Number) return ((Number) val).doubleValue();
        return defaultVal;
    }

    private static String env(String name, String defaultVal) {
        String val = System.getenv(name);
        return (val != null && !val.isEmpty()) ? val : defaultVal;
    }

    private static int envInt(String name, int defaultVal) {
        String val = System.getenv(name);
        if (val != null && !val.isEmpty()) {
            try { return Integer.parseInt(val); } catch (NumberFormatException ignored) {}
        }
        return defaultVal;
    }

    private static double envDouble(String name, double defaultVal) {
        String val = System.getenv(name);
        if (val != null && !val.isEmpty()) {
            try { return Double.parseDouble(val); } catch (NumberFormatException ignored) {}
        }
        return defaultVal;
    }

    private static boolean getBoolean(Map<String, Object> map, String key, boolean defaultVal) {
        Object val = map.get(key);
        if (val instanceof Boolean) return (Boolean) val;
        return defaultVal;
    }

    private static boolean envBool(String name, boolean defaultVal) {
        String val = System.getenv(name);
        if (val != null && !val.isEmpty()) return Boolean.parseBoolean(val);
        return defaultVal;
    }

    // ========== Getters ==========

    /** @return DeepSeek API Key */
    public String getApiKey() { return apiKey; }
    /** @return DeepSeek API 基础地址 */
    public String getBaseUrl() { return baseUrl; }
    /** @return 模型名称 */
    public String getModelName() { return modelName; }
    /** @return 系统提示词 */
    public String getSystemPrompt() { return systemPrompt; }
    /** @return 模型温度参数（0~1） */
    public double getTemperature() { return temperature; }
    /** @return 最大输出 Token 数 */
    public int getMaxTokens() { return maxTokens; }
    /** @return API 超时秒数 */
    public int getTimeoutSeconds() { return timeoutSeconds; }
    /** @return 检索返回的最大结果数 */
    public int getMaxResults() { return maxResults; }
    /** @return 检索最低相似度阈值（0~1） */
    public double getMinScore() { return minScore; }
    /** @return 默认文档目录路径 */
    public String getDocumentDir() { return documentDir; }
    /** @return 文档分块大小（字符数） */
    public int getChunkSize() { return chunkSize; }
    /** @return 文档分块重叠大小（字符数） */
    public int getChunkOverlap() { return chunkOverlap; }
    /** @return 对话记忆窗口大小（消息数） */
    public int getMemorySize() { return memorySize; }
    /** @return HTTP 服务器端口 */
    public int getPort() { return port; }
    /** @return 向量存储文件路径 */
    public String getStoreFilePath() { return storeFilePath; }
    /** @return 支持的文件扩展名列表 */
    public List<String> getSupportedFileExtensions() { return supportedFileExtensions; }
    /** @return 分块模式 */
    public String getChunkingMode() { return chunkingMode; }
    /** @return 语义断点相似度阈值 */
    public double getSemanticThreshold() { return semanticThreshold; }
    /** @return 是否启用 Agent 精炼 */
    public boolean isAgentRefinerEnabled() { return enableAgentRefiner; }
    /** @return 单块最大字符数上限 */
    public int getMaxChunkSize() { return maxChunkSize; }
}
