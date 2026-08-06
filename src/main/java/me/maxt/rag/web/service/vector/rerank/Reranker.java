package me.maxt.rag.web.service.vector.rerank;

import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.store.embedding.EmbeddingMatch;

import java.util.List;

/** 重排序接口，对召回候选结果按相关性重新排序 */
public interface Reranker {
    /** 重排序器名称，用于日志和路由 */
    String name();
    /** 当前实现是否可用（如依赖的模型/服务是否就绪） */
    boolean isAvailable();
    /** 对候选结果重排序，返回相关性最高的前 topK 条 */
    List<EmbeddingMatch<TextSegment>> rerank(String query, List<EmbeddingMatch<TextSegment>> candidates, int topK);
}
