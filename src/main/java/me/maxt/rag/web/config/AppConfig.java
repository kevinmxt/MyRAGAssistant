package me.maxt.rag.web.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * 应用配置容器：{@link #load()} 把十一个配置节 record 装配为一棵不可变配置树。
 *
 * <p>各节由 {@link ConfigBinder} 按优先级链（{@code @Key} 默认值 → config.json → 环境变量）
 * 绑定，本类只负责装配与委托取值；节定义见各 XxxSettings record。</p>
 *
 * @author maxt
 * @since 1.0
 */
public class AppConfig implements LlmConfig, RetrievalConfig, DocumentConfig, ServerConfig, QueryEnhancementConfig, MilvusConfig, RecallConfig, RerankConfig, EvaluationConfig, EnvCheckConfig, ModelConfig {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Logger log = LoggerFactory.getLogger(AppConfig.class);

    private final LlmSettings llm;
    private final RetrievalSettings retrieval;
    private final DocumentSettings document;
    private final ServerSettings server;
    private final QueryEnhancementSettings queryEnhancement;
    private final MilvusSettings milvus;
    private final RecallSettings recall;
    private final RerankSettings rerank;
    private final EvaluationSettings evaluation;
    private final EnvCheckSettings envCheck;
    private final ModelSettings model;

    /**
     * 使用默认值构造配置实例（不读 config.json、不读环境变量）。
     */
    public AppConfig() {
        this(Map.of(), name -> null);
    }

    /**
     * 按三层数据源装配全部配置节。
     */
    AppConfig(Map<String, Object> fileConfig, Function<String, String> envLookup) {
        this.llm = ConfigBinder.bind(LlmSettings.class, fileConfig, envLookup);
        this.retrieval = ConfigBinder.bind(RetrievalSettings.class, fileConfig, envLookup);
        this.document = ConfigBinder.bind(DocumentSettings.class, fileConfig, envLookup);
        this.server = ConfigBinder.bind(ServerSettings.class, fileConfig, envLookup);
        this.queryEnhancement = ConfigBinder.bind(QueryEnhancementSettings.class, fileConfig, envLookup);
        this.milvus = ConfigBinder.bind(MilvusSettings.class, fileConfig, envLookup);
        this.recall = ConfigBinder.bind(RecallSettings.class, fileConfig, envLookup);
        this.rerank = ConfigBinder.bind(RerankSettings.class, fileConfig, envLookup);
        this.evaluation = ConfigBinder.bind(EvaluationSettings.class, fileConfig, envLookup);
        this.envCheck = ConfigBinder.bind(EnvCheckSettings.class, fileConfig, envLookup);
        this.model = ConfigBinder.bind(ModelSettings.class, fileConfig, envLookup);
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

    // ========== Getters（委托到各配置节） ==========

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
    /** @return 对话记忆窗口大小（消息数） */
    @Override public int getMemorySize() { return retrieval.getMemorySize(); }

    /** @return 默认文档目录路径 */
    @Override public String getDocumentDir() { return document.getDocumentDir(); }
    /** @return 文档分块大小（字符数） */
    @Override public int getChunkSize() { return document.getChunkSize(); }
    /** @return 文档分块重叠大小（字符数） */
    @Override public int getChunkOverlap() { return document.getChunkOverlap(); }
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

    /** @return HTTP 服务器端口 */
    @Override public int getPort() { return server.getPort(); }
    /** @return 向量存储文件路径 */
    @Override public String getStoreFilePath() { return server.getStoreFilePath(); }

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
    @Override public boolean isMultiRecallEnabled() { return recall.isMultiRecallEnabled(); }
    /** @return 启用的召回模式列表 */
    @Override public List<String> getRecallModes() { return recall.getRecallModes(); }
    /** @return 最终返回给 LLM 的结果数 */
    @Override public int getRecallTopK() { return recall.getRecallTopK(); }
    /** @return RRF 融合参数 k */
    @Override public int getRecallRrfK() { return recall.getRecallRrfK(); }
    /** @return LightRAG Python 可执行文件路径 */
    @Override public String getLightRagPythonPath() { return recall.getLightRagPythonPath(); }
    /** @return LightRAG 工作目录 */
    @Override public String getLightRagWorkingDir() { return recall.getLightRagWorkingDir(); }
    /** @return LightRAG 嵌入模型路径 */
    @Override public String getLightRagEmbeddingModelPath() { return recall.getLightRagEmbeddingModelPath(); }
    /** @return LightRAG 查询模式 */
    @Override public String getLightRagQueryMode() { return recall.getLightRagQueryMode(); }

    /** @return ONNX 精排模型路径 */
    @Override public String getRerankModelPath() { return rerank.getRerankModelPath(); }
    /** @return 粗召回扩展倍数 */
    @Override public int getRerankExpansionFactor() { return rerank.getRerankExpansionFactor(); }
    /** @return 精排后返回给 LLM 的结果数 */
    @Override public int getRerankTopK() { return rerank.getRerankTopK(); }

    /** @return 评估指标（Recall@K / Precision@K / NDCG@K）的 K 值 */
    @Override public int getEvaluationTopK() { return evaluation.getEvaluationTopK(); }
    /** @return 启用的评估格式列表 */
    @Override public List<String> getEvaluationFormats() { return evaluation.getEvaluationFormats(); }
    /** @return 是否启用 LLM 答案质量评估 */
    @Override public boolean isAnswerQualityEnabled() { return evaluation.isAnswerQualityEnabled(); }
    /** @return 退化判定阈值（如 0.05 表示 5%） */
    @Override public double getDegradationThreshold() { return evaluation.getDegradationThreshold(); }

    /** @return 是否启用环境检测 */
    @Override public boolean isEnvCheckEnabled() { return envCheck.isEnvCheckEnabled(); }
    /** @return 缺失依赖是否自动安装 */
    @Override public boolean isAutoInstallEnabled() { return envCheck.isAutoInstallEnabled(); }
    /** @return 全部检测的总超时秒数 */
    @Override public int getEnvCheckTimeoutSeconds() { return envCheck.getEnvCheckTimeoutSeconds(); }
    /** @return 单个子进程探测超时秒数 */
    @Override public int getProbeTimeoutSeconds() { return envCheck.getProbeTimeoutSeconds(); }

    /** @return 模型文件缺失时是否自动下载 */
    @Override public boolean isAutoDownload() { return model.isAutoDownload(); }
    /** @return 模型下载镜像地址 */
    @Override public String getDownloadMirror() { return model.getDownloadMirror(); }
}
