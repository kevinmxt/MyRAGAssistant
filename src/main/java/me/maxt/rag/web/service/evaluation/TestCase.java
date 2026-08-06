package me.maxt.rag.web.service.evaluation;

import java.util.List;

/**
 * 评估测试用例，映射 testcases.json 中的单条记录。
 */
public record TestCase(
    String id,
    String query,
    List<String> relevantDocs,
    List<String> relevantContent,
    String expectedAnswer
) {
    public boolean hasRelevantContent() {
        return relevantContent != null && !relevantContent.isEmpty();
    }

    public boolean hasExpectedAnswer() {
        return expectedAnswer != null && !expectedAnswer.isEmpty();
    }
}
