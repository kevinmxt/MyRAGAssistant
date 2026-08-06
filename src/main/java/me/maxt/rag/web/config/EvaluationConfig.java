package me.maxt.rag.web.config;

import java.util.List;

/**
 * 评估配置接口。
 */
public interface EvaluationConfig {
    /** Recall@K / Precision@K / NDCG@K 的 K 值 */
    int getEvaluationTopK();

    /** 启用的评估格式列表 */
    List<String> getEvaluationFormats();

    /** 是否启用 LLM 答案质量评估 */
    boolean isAnswerQualityEnabled();

    /** 退化判定阈值（如 0.05 表示 5%） */
    double getDegradationThreshold();
}
