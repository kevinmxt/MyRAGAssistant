package me.maxt.rag.web.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

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
public class AppConfig implements LlmConfig, RetrievalConfig, DocumentConfig, ServerConfig, QueryEnhancementConfig, MilvusConfig, RecallConfig, RerankConfig, EvaluationConfig, EnvCheckConfig, ModelConfig {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Logger log = LoggerFactory.getLogger(AppConfig.class);

    // ========== 已迁配置节（ConfigBinder 装配） ==========

    /** LLM 配置节 record，由 {@link ConfigBinder} 按优先级链装配 */
    private final LlmSettings llm;
    private final RetrievalSettings retrieval;
    private final DocumentSettings document;
    private final ServerSettings server;
    private final QueryEnhancementSettings queryEnhancement;
    private final MilvusSettings milvus;

    // ========== 多路召回参数（未迁） ==========

    /** 是否启用多路召回，可通过环境变量 RAG_MULTI_RECALL_ENABLED 覆盖 */
    private boolean multiRecallEnabled;

    /** 启用的召回模式列表（如 dense/sparse/graph），可通过环境变量 RAG_MULTI_RECALL_MODES（逗号分隔）覆盖 */
    private List<String> recallModes;

    /** 最终返回给 LLM 的结果数，可通过环境变量 RAG_MULTI_RECALL_TOP_K 覆盖 */
    private int recallTopK;

    /** RRF 融合参数 k，可通过环境变量 RAG_MULTI_RECALL_RRF_K 覆盖 */
    private int recallRrfK;

    /** LightRAG Python 可执行文件路径，可通过环境变量 RAG_LIGHTRAG_PYTHON 覆盖 */
    private String lightRagPythonPath;

    /** LightRAG 工作目录，可通过环境变量 RAG_LIGHTRAG_WORKDIR 覆盖 */
    private String lightRagWorkingDir;

    /** LightRAG 嵌入模型路径，可通过环境变量 RAG_LIGHTRAG_EMBEDDING 覆盖 */
    private String lightRagEmbeddingModelPath;

    /** LightRAG 查询模式，可通过环境变量 RAG_LIGHTRAG_QUERY_MODE 覆盖 */
    private String lightRagQueryMode;

    // ========== 重排序参数 ==========

    /** ONNX 精排模型路径，可通过环境变量 RAG_RERANK_MODEL_PATH 覆盖 */
    private String rerankModelPath;

    /** 粗召回扩展倍数，可通过环境变量 RAG_RERANK_EXPANSION_FACTOR 覆盖 */
    private int rerankExpansionFactor;

    /** 精排后返回给 LLM 的结果数，可通过环境变量 RAG_RERANK_TOP_K 覆盖 */
    private int rerankTopK;

    // ========== 评估参数 ==========

    /** 评估指标（Recall@K / Precision@K / NDCG@K）的 K 值，可通过环境变量 RAG_EVALUATION_TOP_K 覆盖 */
    private int evaluationTopK;

    /** 启用的评估格式列表，可通过环境变量 RAG_EVALUATION_FORMATS（逗号分隔）覆盖 */
    private List<String> evaluationFormats;

    /** 是否启用 LLM 答案质量评估，可通过环境变量 RAG_EVALUATION_ANSWER_QUALITY_ENABLED 覆盖 */
    private boolean answerQualityEnabled;

    /** 退化判定阈值（如 0.05 表示 5%），可通过环境变量 RAG_EVALUATION_DEGRADATION_THRESHOLD 覆盖 */
    private double degradationThreshold;

    // ========== 环境检测参数 ==========

    /** 是否启用环境检测，可通过环境变量 RAG_ENV_CHECK_ENABLED 覆盖 */
    private boolean envCheckEnabled;

    /** 缺失依赖是否自动安装，可通过环境变量 RAG_ENV_AUTO_INSTALL 覆盖 */
    private boolean autoInstallEnabled;

    /** 全部检测的总超时秒数，可通过环境变量 RAG_ENV_CHECK_TIMEOUT 覆盖 */
    private int envCheckTimeoutSeconds;

    /** 单个子进程探测超时秒数，可通过环境变量 RAG_ENV_PROBE_TIMEOUT 覆盖 */
    private int probeTimeoutSeconds;

    // ========== 模型下载参数 ==========

    /** 模型文件缺失时是否自动下载，可通过环境变量 RAG_MODEL_AUTO_DOWNLOAD 覆盖 */
    private boolean modelAutoDownload;

    /** 模型下载镜像地址，可通过环境变量 RAG_MODEL_MIRROR 覆盖 */
    private String modelDownloadMirror;

    /**
     * 使用默认值构造配置实例（不读 config.json、不读环境变量）。
     */
    public AppConfig() {
        this(Map.of(), name -> null);
    }

    /**
     * 按三层数据源装配：已迁节的 record 由 {@link ConfigBinder} 绑定，
     * 未迁键仍走下方手写默认值 + 解析行。
     */
    AppConfig(Map<String, Object> fileConfig, Function<String, String> envLookup) {
        this.llm = ConfigBinder.bind(LlmSettings.class, fileConfig, envLookup);
        this.retrieval = ConfigBinder.bind(RetrievalSettings.class, fileConfig, envLookup);
        this.document = ConfigBinder.bind(DocumentSettings.class, fileConfig, envLookup);
        this.server = ConfigBinder.bind(ServerSettings.class, fileConfig, envLookup);
        this.queryEnhancement = ConfigBinder.bind(QueryEnhancementSettings.class, fileConfig, envLookup);
        this.milvus = ConfigBinder.bind(MilvusSettings.class, fileConfig, envLookup);

        // Set defaults（未迁键）
        this.multiRecallEnabled = false;
        this.recallModes = List.of("dense");
        this.recallTopK = 5;
        this.recallRrfK = 60;
        this.lightRagPythonPath = "python";
        this.lightRagWorkingDir = "data/kg";
        this.lightRagEmbeddingModelPath = "models/bge-small-zh-v1.5";
        this.lightRagQueryMode = "hybrid";
        this.rerankModelPath = "models/bge-reranker-v2-m3";
        this.rerankExpansionFactor = 3;
        this.rerankTopK = 5;
        this.evaluationTopK = 5;
        this.evaluationFormats = Arrays.asList("markdown", "txt", "pdf", "docx", "json");
        this.answerQualityEnabled = true;
        this.degradationThreshold = 0.05;
        this.envCheckEnabled = true;
        this.autoInstallEnabled = false;
        this.envCheckTimeoutSeconds = 15;
        this.probeTimeoutSeconds = 5;
        this.modelAutoDownload = true;
        this.modelDownloadMirror = "https://hf-mirror.com";

        // 未迁键的 file 与 env 覆盖
        applyFileConfig(this, fileConfig);
        applyEnvOverrides(this, envLookup);
    }

    /**
     * 按优先级链加载配置：代码默认 → 工作目录下 config.json → 环境变量。
     *
     * @return 加载完成的配置实例
     */
    public static AppConfig load() {
        return load(new File(System.getProperty("user.dir"), "config.json").toPath());
    }

    /**
     * 按优先级链加载指定路径的配置文件（供测试注入；文件缺失时全部取默认值）。
     *
     * @param configFile config.json 文件路径
     * @return 加载完成的配置实例
     */
    static AppConfig load(Path configFile) {
        return load(configFile, System::getenv);
    }

    /**
     * 按优先级链加载，环境变量查找函数可注入（测试密闭用，不受宿主机真实环境变量干扰）。
     *
     * @param configFile config.json 文件路径
     * @param envLookup  环境变量查找函数
     * @return 加载完成的配置实例
     */
    static AppConfig load(Path configFile, Function<String, String> envLookup) {
        Map<String, Object> fileConfig = Map.of();
        if (configFile.toFile().exists()) {
            try {
                @SuppressWarnings("unchecked")
                Map<String, Object> parsed = MAPPER.readValue(configFile.toFile(), Map.class);
                fileConfig = parsed;
            } catch (IOException e) {
                log.warn("Failed to parse config.json, using defaults.", e);
            }
        }
        return new AppConfig(fileConfig, envLookup);
    }

    /**
     * 从 config.json 解析的 Map 中读取各组配置并应用到实例。
     */
    @SuppressWarnings("unchecked")
    private static void applyFileConfig(AppConfig config, Map<String, Object> fileConfig) {
        Map<String, Object> multiRecall = (Map<String, Object>) fileConfig.get("multiRecall");
        if (multiRecall != null) {
            config.multiRecallEnabled = getBoolean(multiRecall, "enabled", config.multiRecallEnabled);
            Object modesObj = multiRecall.get("modes");
            if (modesObj instanceof List) {
                @SuppressWarnings("unchecked")
                List<String> modesList = (List<String>) modesObj;
                config.recallModes = modesList;
            }
            config.recallTopK = getInt(multiRecall, "topK", config.recallTopK);
            config.recallRrfK = getInt(multiRecall, "rrfK", config.recallRrfK);

            Map<String, Object> lightrag = (Map<String, Object>) multiRecall.get("lightrag");
            if (lightrag != null) {
                config.lightRagPythonPath = getString(lightrag, "pythonPath", config.lightRagPythonPath);
                config.lightRagWorkingDir = getString(lightrag, "workingDir", config.lightRagWorkingDir);
                config.lightRagEmbeddingModelPath = getString(lightrag, "embeddingModelPath", config.lightRagEmbeddingModelPath);
                config.lightRagQueryMode = getString(lightrag, "queryMode", config.lightRagQueryMode);
            }
        }

        Map<String, Object> rerank = (Map<String, Object>) fileConfig.get("rerank");
        if (rerank != null) {
            config.rerankModelPath = getString(rerank, "modelPath", config.rerankModelPath);
            config.rerankExpansionFactor = getInt(rerank, "expansionFactor", config.rerankExpansionFactor);
            config.rerankTopK = getInt(rerank, "topK", config.rerankTopK);
        }

        Map<String, Object> evaluation = (Map<String, Object>) fileConfig.get("evaluation");
        if (evaluation != null) {
            config.evaluationTopK = getInt(evaluation, "topK", config.evaluationTopK);
            Object formatsObj = evaluation.get("formats");
            if (formatsObj instanceof List) {
                @SuppressWarnings("unchecked")
                List<String> formatsList = (List<String>) formatsObj;
                config.evaluationFormats = formatsList;
            } else if (formatsObj instanceof String) {
                config.evaluationFormats = Arrays.asList(((String) formatsObj).split(","));
            }
            config.answerQualityEnabled = getBoolean(evaluation, "answerQualityEnabled", config.answerQualityEnabled);
            config.degradationThreshold = getDouble(evaluation, "degradationThreshold", config.degradationThreshold);
        }

        Map<String, Object> environment = (Map<String, Object>) fileConfig.get("environment");
        if (environment != null) {
            config.envCheckEnabled = getBoolean(environment, "enabled", config.envCheckEnabled);
            config.autoInstallEnabled = getBoolean(environment, "autoInstall", config.autoInstallEnabled);
            config.envCheckTimeoutSeconds = getInt(environment, "checkTimeoutSeconds", config.envCheckTimeoutSeconds);
            config.probeTimeoutSeconds = getInt(environment, "probeTimeoutSeconds", config.probeTimeoutSeconds);
        }

        Map<String, Object> model = (Map<String, Object>) fileConfig.get("model");
        if (model != null) {
            config.modelAutoDownload = getBoolean(model, "autoDownload", config.modelAutoDownload);
            config.modelDownloadMirror = getString(model, "downloadMirror", config.modelDownloadMirror);
        }
    }

    /**
     * 应用环境变量覆盖配置值（未迁键；已迁节由 ConfigBinder 处理）。
     */
    private static void applyEnvOverrides(AppConfig config, Function<String, String> envLookup) {
        config.multiRecallEnabled = envBool("RAG_MULTI_RECALL_ENABLED", config.multiRecallEnabled, envLookup);
        String modesEnv = envLookup.apply("RAG_MULTI_RECALL_MODES");
        if (modesEnv != null && !modesEnv.isEmpty()) {
            config.recallModes = Arrays.asList(modesEnv.split(","));
        }
        config.recallTopK = envInt("RAG_MULTI_RECALL_TOP_K", config.recallTopK, envLookup);
        config.recallRrfK = envInt("RAG_MULTI_RECALL_RRF_K", config.recallRrfK, envLookup);
        config.lightRagPythonPath = env("RAG_LIGHTRAG_PYTHON", config.lightRagPythonPath, envLookup);
        config.lightRagWorkingDir = env("RAG_LIGHTRAG_WORKDIR", config.lightRagWorkingDir, envLookup);
        config.lightRagEmbeddingModelPath = env("RAG_LIGHTRAG_EMBEDDING", config.lightRagEmbeddingModelPath, envLookup);
        config.lightRagQueryMode = env("RAG_LIGHTRAG_QUERY_MODE", config.lightRagQueryMode, envLookup);
        config.rerankModelPath = env("RAG_RERANK_MODEL_PATH", config.rerankModelPath, envLookup);
        config.rerankExpansionFactor = envInt("RAG_RERANK_EXPANSION_FACTOR", config.rerankExpansionFactor, envLookup);
        config.rerankTopK = envInt("RAG_RERANK_TOP_K", config.rerankTopK, envLookup);
        config.evaluationTopK = envInt("RAG_EVALUATION_TOP_K", config.evaluationTopK, envLookup);
        String formatsEnv = envLookup.apply("RAG_EVALUATION_FORMATS");
        if (formatsEnv != null && !formatsEnv.isEmpty()) {
            config.evaluationFormats = Arrays.asList(formatsEnv.split(","));
        }
        config.answerQualityEnabled = envBool("RAG_EVALUATION_ANSWER_QUALITY_ENABLED", config.answerQualityEnabled, envLookup);
        config.degradationThreshold = envDouble("RAG_EVALUATION_DEGRADATION_THRESHOLD", config.degradationThreshold, envLookup);
        config.envCheckEnabled = envBool("RAG_ENV_CHECK_ENABLED", config.envCheckEnabled, envLookup);
        config.autoInstallEnabled = envBool("RAG_ENV_AUTO_INSTALL", config.autoInstallEnabled, envLookup);
        config.envCheckTimeoutSeconds = envInt("RAG_ENV_CHECK_TIMEOUT", config.envCheckTimeoutSeconds, envLookup);
        config.probeTimeoutSeconds = envInt("RAG_ENV_PROBE_TIMEOUT", config.probeTimeoutSeconds, envLookup);
        config.modelAutoDownload = envBool("RAG_MODEL_AUTO_DOWNLOAD", config.modelAutoDownload, envLookup);
        config.modelDownloadMirror = env("RAG_MODEL_MIRROR", config.modelDownloadMirror, envLookup);
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

    private static String env(String name, String defaultVal, Function<String, String> envLookup) {
        String val = envLookup.apply(name);
        return (val != null && !val.isEmpty()) ? val : defaultVal;
    }

    private static int envInt(String name, int defaultVal, Function<String, String> envLookup) {
        String val = envLookup.apply(name);
        if (val != null && !val.isEmpty()) {
            try { return Integer.parseInt(val); } catch (NumberFormatException ignored) {}
        }
        return defaultVal;
    }

    private static double envDouble(String name, double defaultVal, Function<String, String> envLookup) {
        String val = envLookup.apply(name);
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

    private static boolean envBool(String name, boolean defaultVal, Function<String, String> envLookup) {
        String val = envLookup.apply(name);
        if (val != null && !val.isEmpty()) return Boolean.parseBoolean(val);
        return defaultVal;
    }

    // ========== Getters ==========

    /** @return DeepSeek API Key */
    @Override public String getApiKey() { return llm.getApiKey(); }
    /** @return DeepSeek API 基础地址 */
    @Override public String getBaseUrl() { return llm.getBaseUrl(); }
    /** @return 模型名称 */
    @Override public String getModelName() { return llm.getModelName(); }
    /** @return 系统提示词 */
    @Override public String getSystemPrompt() { return llm.getSystemPrompt(); }
    /** @return 模型温度参数（0~1） */
    @Override public double getTemperature() { return llm.getTemperature(); }
    /** @return 最大输出 Token 数 */
    @Override public int getMaxTokens() { return llm.getMaxTokens(); }
    /** @return API 超时秒数 */
    @Override public int getTimeoutSeconds() { return llm.getTimeoutSeconds(); }
    /** @return 检索返回的最大结果数 */
    @Override public int getMaxResults() { return retrieval.getMaxResults(); }
    /** @return 检索最低相似度阈值（0~1） */
    @Override public double getMinScore() { return retrieval.getMinScore(); }
    /** @return 默认文档目录路径 */
    @Override public String getDocumentDir() { return document.getDocumentDir(); }
    /** @return 文档分块大小（字符数） */
    @Override public int getChunkSize() { return document.getChunkSize(); }
    /** @return 文档分块重叠大小（字符数） */
    @Override public int getChunkOverlap() { return document.getChunkOverlap(); }
    /** @return 对话记忆窗口大小（消息数） */
    @Override public int getMemorySize() { return retrieval.getMemorySize(); }
    /** @return HTTP 服务器端口 */
    @Override public int getPort() { return server.getPort(); }
    /** @return 向量存储文件路径 */
    @Override public String getStoreFilePath() { return server.getStoreFilePath(); }
    /** @return 支持的文件扩展名列表 */
    @Override public List<String> getSupportedFileExtensions() { return document.getSupportedFileExtensions(); }
    /** @return 分块模式 */
    @Override public String getChunkingMode() { return document.getChunkingMode(); }
    /** @return 语义断点相似度阈值 */
    @Override public double getSemanticThreshold() { return document.getSemanticThreshold(); }
    /** @return 是否启用 Agent 精炼 */
    @Override public boolean isAgentRefinerEnabled() { return document.isAgentRefinerEnabled(); }
    /** @return 单块最大字符数上限 */
    @Override public int getMaxChunkSize() { return document.getMaxChunkSize(); }
    /** @return 是否启用查询增强 */
    @Override public boolean isQueryEnhancementEnabled() { return queryEnhancement.isQueryEnhancementEnabled(); }
    /** @return 默认增强模式 */
    @Override public String getDefaultEnhancementMode() { return queryEnhancement.getDefaultEnhancementMode(); }
    /** @return RRF 融合参数 k */
    @Override public int getRrfK() { return queryEnhancement.getRrfK(); }
    /** @return HyDE 生成文本最大 token 数 */
    @Override public int getHydeMaxTokens() { return queryEnhancement.getHydeMaxTokens(); }
    /** @return Milvus 服务主机地址 */
    @Override public String getMilvusHost() { return milvus.getMilvusHost(); }
    /** @return Milvus gRPC 端口 */
    @Override public int getMilvusPort() { return milvus.getMilvusPort(); }
    /** @return Milvus collection 名称 */
    @Override public String getMilvusCollectionName() { return milvus.getMilvusCollectionName(); }
    /** @return 向量维度 */
    @Override public int getMilvusDimension() { return milvus.getMilvusDimension(); }
    /** @return 是否启用多路召回 */
    public boolean isMultiRecallEnabled() { return multiRecallEnabled; }
    /** @return 启用的召回模式列表 */
    public List<String> getRecallModes() { return recallModes; }
    /** @return 最终返回给 LLM 的结果数 */
    public int getRecallTopK() { return recallTopK; }
    /** @return RRF 融合参数 k */
    public int getRecallRrfK() { return recallRrfK; }
    /** @return LightRAG Python 可执行文件路径 */
    public String getLightRagPythonPath() { return lightRagPythonPath; }
    /** @return LightRAG 工作目录 */
    public String getLightRagWorkingDir() { return lightRagWorkingDir; }
    /** @return LightRAG 嵌入模型路径 */
    public String getLightRagEmbeddingModelPath() { return lightRagEmbeddingModelPath; }
    /** @return LightRAG 查询模式 */
    public String getLightRagQueryMode() { return lightRagQueryMode; }
    /** @return ONNX 精排模型路径 */
    public String getRerankModelPath() { return rerankModelPath; }
    /** @return 粗召回扩展倍数 */
    public int getRerankExpansionFactor() { return rerankExpansionFactor; }
    /** @return 精排后返回给 LLM 的结果数 */
    public int getRerankTopK() { return rerankTopK; }
    /** @return 评估指标（Recall@K / Precision@K / NDCG@K）的 K 值 */
    @Override public int getEvaluationTopK() { return evaluationTopK; }
    /** @return 启用的评估格式列表 */
    @Override public List<String> getEvaluationFormats() { return evaluationFormats; }
    /** @return 是否启用 LLM 答案质量评估 */
    @Override public boolean isAnswerQualityEnabled() { return answerQualityEnabled; }
    /** @return 退化判定阈值（如 0.05 表示 5%） */
    @Override public double getDegradationThreshold() { return degradationThreshold; }
    @Override public boolean isEnvCheckEnabled() { return envCheckEnabled; }
    @Override public boolean isAutoInstallEnabled() { return autoInstallEnabled; }
    @Override public int getEnvCheckTimeoutSeconds() { return envCheckTimeoutSeconds; }
    @Override public int getProbeTimeoutSeconds() { return probeTimeoutSeconds; }
    /** @return 模型文件缺失时是否自动下载 */
    @Override public boolean isAutoDownload() { return modelAutoDownload; }
    /** @return 模型下载镜像地址 */
    @Override public String getDownloadMirror() { return modelDownloadMirror; }
}
