package me.maxt.rag.web.service.evaluation;

import dev.langchain4j.model.chat.ChatModel;
import java.util.List;

/**
 * 答案质量评估指标接口。
 */
public interface AnswerQualityMetric {
    String name();
    QualityScore evaluate(String query, String answer, List<String> contexts, ChatModel judge);
}
