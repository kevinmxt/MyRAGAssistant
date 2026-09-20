package me.maxt.rag.web.config;

import java.util.List;

/**
 * 评估配置节。
 *
 * @author maxt
 * @since 1.0
 */
public record EvaluationSettings(
        @Key(json = "evaluation.topK", env = "RAG_EVALUATION_TOP_K", def = "5") int getEvaluationTopK,
        @Key(json = "evaluation.formats", env = "RAG_EVALUATION_FORMATS",
                def = "markdown,txt,pdf,docx,json") List<String> getEvaluationFormats,
        @Key(json = "evaluation.answerQualityEnabled", env = "RAG_EVALUATION_ANSWER_QUALITY_ENABLED",
                def = "true") boolean isAnswerQualityEnabled,
        @Key(json = "evaluation.degradationThreshold", env = "RAG_EVALUATION_DEGRADATION_THRESHOLD",
                def = "0.05") double getDegradationThreshold) implements EvaluationConfig {
}
