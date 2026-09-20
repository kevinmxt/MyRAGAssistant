package me.maxt.rag.web.config;

/**
 * LLM 配置节：七个键一处声明，由 {@link ConfigBinder} 按优先级链装配。
 *
 * <p>组件命名沿用接口 getter 风格（如 {@code getApiKey}），record 访问器即
 * {@link LlmConfig} 接口方法，无需桥接。</p>
 *
 * @author maxt
 * @since 1.0
 */
public record LlmSettings(
        @Key(json = "llm.apiKey", env = "RAG_LLM_API_KEY", def = "demo") String getApiKey,
        @Key(json = "llm.baseUrl", env = "RAG_LLM_BASE_URL", def = "https://api.deepseek.com") String getBaseUrl,
        @Key(json = "llm.modelName", env = "RAG_LLM_MODEL_NAME", def = "deepseek-v4-flash") String getModelName,
        @Key(json = "llm.systemPrompt", env = "RAG_LLM_SYSTEM_PROMPT",
                def = "你是一个基于本地知识库的智能助手，请根据提供的文档内容回答用户问题。如果文档中没有相关信息，请如实告知。") String getSystemPrompt,
        @Key(json = "llm.temperature", env = "RAG_LLM_TEMPERATURE", def = "0.7") double getTemperature,
        @Key(json = "llm.maxTokens", env = "RAG_LLM_MAX_TOKENS", def = "4096") int getMaxTokens,
        @Key(json = "llm.timeoutSeconds", env = "RAG_LLM_TIMEOUT", def = "120") int getTimeoutSeconds) implements LlmConfig {

    /** 覆盖 record 自动生成的 toString：避免 apiKey 泄漏进日志 */
    @Override
    public String toString() {
        return "LlmSettings[modelName=" + getModelName() + ", baseUrl=" + getBaseUrl() + "]";
    }
}
