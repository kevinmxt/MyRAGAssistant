package me.maxt.rag.web.service.vector.recall;

import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.store.embedding.EmbeddingMatch;

import java.util.List;

/** 召回策略接口，每种策略实现一种检索方式 */
public interface RecallStrategy {
    /** 策略名称，用于日志和路由 */
    String name();
    /** 执行检索召回 */
    List<EmbeddingMatch<TextSegment>> recall(String query, int topK);
}
