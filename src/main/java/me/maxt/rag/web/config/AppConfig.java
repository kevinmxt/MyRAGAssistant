package me.maxt.rag.web.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;
import java.util.function.Function;

/**
 * 应用配置容器：{@link #load()} 把十一个配置节 record 装配为一棵不可变配置树。
 *
 * <p>各节由 {@link ConfigBinder} 按优先级链（{@code @Key} 默认值 → config.json → 环境变量）
 * 绑定，节 record 直接实现对应的 Config 接口；本类只负责装配与暴露节访问器，
 * 组装根负责把节传递给按窄接口取值的消费者。</p>
 *
 * @author maxt
 * @since 1.0
 */
public class AppConfig {

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

    // ========== 节访问器 ==========

    /** @return LLM 配置节 */
    public LlmSettings llm() { return llm; }
    /** @return 检索配置节（含对话记忆窗口） */
    public RetrievalSettings retrieval() { return retrieval; }
    /** @return 文档配置节（含 chunking 参数） */
    public DocumentSettings document() { return document; }
    /** @return 服务器与存储配置节 */
    public ServerSettings server() { return server; }
    /** @return 查询增强配置节 */
    public QueryEnhancementSettings queryEnhancement() { return queryEnhancement; }
    /** @return Milvus 向量库配置节 */
    public MilvusSettings milvus() { return milvus; }
    /** @return 多路召回配置节（含 LightRAG） */
    public RecallSettings recall() { return recall; }
    /** @return 重排序配置节 */
    public RerankSettings rerank() { return rerank; }
    /** @return 评估配置节 */
    public EvaluationSettings evaluation() { return evaluation; }
    /** @return 环境检测配置节 */
    public EnvCheckSettings envCheck() { return envCheck; }
    /** @return 模型下载配置节 */
    public ModelSettings model() { return model; }
}
