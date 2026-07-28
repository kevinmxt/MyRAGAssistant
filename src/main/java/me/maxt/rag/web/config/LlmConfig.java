package me.maxt.rag.web.config;

/**
 * LLM 相关的配置接口。
 */
public interface LlmConfig {
    String getApiKey();
    String getBaseUrl();
    String getModelName();
    String getSystemPrompt();
    double getTemperature();
    int getMaxTokens();
    int getTimeoutSeconds();
}
