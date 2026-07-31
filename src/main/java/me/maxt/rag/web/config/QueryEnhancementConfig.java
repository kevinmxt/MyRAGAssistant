package me.maxt.rag.web.config;

/**
 * 查询增强相关配置接口。
 */
public interface QueryEnhancementConfig {
    /** 是否启用查询增强 */
    boolean isQueryEnhancementEnabled();
    /** 默认增强模式：auto | rewrite | hyde | both | none */
    String getDefaultEnhancementMode();
    /** RRF 融合参数 k */
    int getRrfK();
    /** HyDE 生成文本最大 token 数 */
    int getHydeMaxTokens();
}
