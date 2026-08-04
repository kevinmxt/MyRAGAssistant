package me.maxt.rag.web.service.vector;

import dev.langchain4j.data.segment.TextSegment;

import java.util.ArrayList;
import java.util.List;

/**
 * 上下文增强器，在嵌入前为每个 chunk 文本附加上下文前缀，
 * 让嵌入向量感知文档来源和章节位置。
 */
public class ContextualEnricher {

    /** 短于此值的 chunk 跳过前缀，避免前缀支配嵌入向量 */
    private static final int MIN_CHUNK_SIZE_FOR_PREFIX = 50;

    public List<TextSegment> enrich(List<TextSegment> segments, String fileName) {
        List<TextSegment> enriched = new ArrayList<>();
        for (TextSegment segment : segments) {
            String text = segment.text();
            if (text.length() < MIN_CHUNK_SIZE_FOR_PREFIX) {
                enriched.add(segment);
                continue;
            }

            String headingPath = segment.metadata().getString("heading_path");

            StringBuilder prefix = new StringBuilder();
            prefix.append("[").append(fileName).append("]");
            if (headingPath != null && !headingPath.isEmpty()) {
                prefix.append(" ").append(headingPath);
            }
            String enrichedText = prefix.append("\n").append(text).toString();

            enriched.add(TextSegment.from(enrichedText, segment.metadata()));
        }
        return enriched;
    }
}
