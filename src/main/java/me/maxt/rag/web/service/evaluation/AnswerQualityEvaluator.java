package me.maxt.rag.web.service.evaluation;

import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 答案质量评估编排器，内建 Faithfulness 和 AnswerRelevancy 两个指标。
 */
public class AnswerQualityEvaluator {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private final List<AnswerQualityMetric> metrics;
    private final ChatModel judge;

    public AnswerQualityEvaluator(ChatModel judge) {
        this.judge = judge;
        this.metrics = new ArrayList<>();
        this.metrics.add(new FaithfulnessMetric());
        this.metrics.add(new AnswerRelevancyMetric());
    }

    public void registerMetric(AnswerQualityMetric metric) {
        metrics.add(metric);
    }

    public Map<String, QualityScore> evaluate(String query, String answer, List<String> contexts) {
        Map<String, QualityScore> results = new LinkedHashMap<>();
        for (AnswerQualityMetric metric : metrics) {
            results.put(metric.name(), metric.evaluate(query, answer, contexts, judge));
        }
        return results;
    }

    public boolean isAvailable() {
        return judge != null;
    }

    // --- 内建指标 ---

    static class FaithfulnessMetric implements AnswerQualityMetric {
        @Override
        public String name() { return "faithfulness"; }

        @Override
        public QualityScore evaluate(String query, String answer, List<String> contexts, ChatModel judge) {
            String ctx = String.join("\n---\n", contexts);
            String prompt = String.format(
                "根据以下上下文，评估\"答案\"中的每句话是否都能从上下文中找到依据。\n" +
                "上下文:\n%s\n\n答案: %s\n\n" +
                "请给出 1-5 分（5=完全忠实，无任何编造），并简要说明扣分原因。\n" +
                "输出格式: {\"score\": <int>, \"reason\": \"<str>\"}",
                ctx, answer
            );
            return callJudge(judge, prompt);
        }
    }

    static class AnswerRelevancyMetric implements AnswerQualityMetric {
        @Override
        public String name() { return "answerRelevancy"; }

        @Override
        public QualityScore evaluate(String query, String answer, List<String> contexts, ChatModel judge) {
            String prompt = String.format(
                "评估以下\"答案\"是否直接、完整地回答了\"问题\"。\n" +
                "问题: %s\n答案: %s\n\n" +
                "请给出 1-5 分（5=完全切题，直接完整回答了问题），并简要说明理由。\n" +
                "输出格式: {\"score\": <int>, \"reason\": \"<str>\"}",
                query, answer
            );
            return callJudge(judge, prompt);
        }
    }

    @SuppressWarnings("unchecked")
    private static QualityScore callJudge(ChatModel judge, String prompt) {
        try {
            ChatResponse resp = judge.chat(ChatRequest.builder()
                    .messages(dev.langchain4j.data.message.UserMessage.from(prompt))
                    .temperature(0.0)
                    .build());
            String text = resp.aiMessage().text();
            // 提取 JSON 对象
            int start = text.indexOf('{');
            int end = text.lastIndexOf('}');
            if (start >= 0 && end > start) {
                Map<String, Object> map = MAPPER.readValue(text.substring(start, end + 1), Map.class);
                int score = ((Number) map.get("score")).intValue();
                String reason = (String) map.get("reason");
                return new QualityScore(score, reason != null ? reason : "");
            }
        } catch (Exception e) {
            // 评估失败时返回默认值
        }
        return new QualityScore(3, "evaluation failed, default score");
    }
}
