package me.maxt.rag.web.service.chunking.splitter;

import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.chat.ChatModel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

public class AgentRefiner {

    private static final Logger log = LoggerFactory.getLogger(AgentRefiner.class);
    private static final String SPLIT_MARKER = "---SPLIT---";
    private final ChatModel chatModel;

    public AgentRefiner(ChatModel chatModel) {
        this.chatModel = chatModel;
    }

    public List<TextSegment> refine(List<TextSegment> versionA, List<TextSegment> versionB,
                                     String originalMarkdown, int targetChunkSize) {
        if (versionA.isEmpty() && versionB.isEmpty()) {
            return List.of();
        }

        String prompt = buildRefinementPrompt(versionA, versionB, originalMarkdown);

        try {
            String response = chatModel.chat(prompt);
            return parseResponse(response);
        } catch (Exception e) {
            log.warn("AgentRefiner 调用失败，降级到较优版本: {}", e.getMessage());
            return fallback(versionA, versionB);
        }
    }

    private String buildRefinementPrompt(List<TextSegment> a, List<TextSegment> b, String original) {
        StringBuilder sb = new StringBuilder();
        sb.append("你是一个文档切分专家。请评估以下两种切分方案，选择更合理的方案或合并两者优点。\n\n");
        sb.append("原始文档:\n```\n").append(truncate(original, 2000)).append("\n```\n\n");
        sb.append("方案A (共").append(a.size()).append("段):\n");
        for (int i = 0; i < a.size(); i++) {
            sb.append("[").append(i + 1).append("] ").append(truncate(a.get(i).text(), 300)).append("\n");
        }
        sb.append("\n方案B (共").append(b.size()).append("段):\n");
        for (int i = 0; i < b.size(); i++) {
            sb.append("[").append(i + 1).append("] ").append(truncate(b.get(i).text(), 300)).append("\n");
        }
        sb.append("\n请输出你认为最优的切分方案，每个分段之间用 '").append(SPLIT_MARKER).append("' 分隔。只输出内容，不要额外解释。");
        return sb.toString();
    }

    private List<TextSegment> parseResponse(String response) {
        List<TextSegment> segments = new ArrayList<>();
        String[] parts = response.split(SPLIT_MARKER);
        for (String part : parts) {
            String trimmed = part.trim();
            if (!trimmed.isEmpty()) {
                segments.add(TextSegment.from(trimmed));
            }
        }
        return segments.isEmpty() ? List.of(TextSegment.from(response)) : segments;
    }

    private List<TextSegment> fallback(List<TextSegment> a, List<TextSegment> b) {
        // 优先返回分段数更多的版本（更细粒度）
        return a.size() >= b.size() ? a : b;
    }

    private String truncate(String text, int maxLen) {
        return text.length() <= maxLen ? text : text.substring(0, maxLen) + "...";
    }
}
