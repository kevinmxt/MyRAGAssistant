package me.maxt.rag.web.config;

/**
 * 文档分块策略的配置接口。
 *
 * <p>定义分块引擎所需的参数，由 {@link AppConfig} 统一实现并提供取值。</p>
 */
public interface ChunkingConfig {

    /**
     * @return 分块模式："auto" | "structure" | "semantic" | "recursive"
     */
    String getChunkingMode();

    /**
     * @return 语义断点相似度阈值，范围 0~1
     */
    double getSemanticThreshold();

    /**
     * @return 是否启用 Agent 精炼（AI 辅助合并/拆分分块结果）
     */
    boolean isAgentRefinerEnabled();

    /**
     * @return 单块最大字符数上限
     */
    int getMaxChunkSize();
}
