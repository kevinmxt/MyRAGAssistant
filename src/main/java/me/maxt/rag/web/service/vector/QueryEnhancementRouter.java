package me.maxt.rag.web.service.vector;

import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import me.maxt.rag.web.config.QueryEnhancementConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

/**
 * 查询增强路由器，根据模式（auto/rewrite/hyde/both/none）分发到对应的增强策略。
 * auto 模式通过 LLM 分类选择策略。
 */
public class QueryEnhancementRouter {

    private static final Logger log = LoggerFactory.getLogger(QueryEnhancementRouter.class);
    private static final String CLASSIFY_PROMPT =
            "分析用户问题，只返回以下一个标签（不要解释）：\n"
                    + "- REWRITE: 问题简短口语化、模糊不清\n"
                    + "- HYDE: 事实性、定义性问题\n"
                    + "- BOTH: 复杂、开放性问题\n"
                    + "- NONE: 问题已经足够具体清晰\n"
                    + "\n问题：";

    private final QueryEnhancer rewriter;
    private final QueryEnhancer hydeGenerator;
    private final ChatModel chatModel;
    private final QueryEnhancementConfig config;

    public QueryEnhancementRouter(QueryEnhancer rewriter, QueryEnhancer hydeGenerator,
                                   ChatModel chatModel, QueryEnhancementConfig config) {
        this.rewriter = rewriter;
        this.hydeGenerator = hydeGenerator;
        this.chatModel = chatModel;
        this.config = config;
    }

    /**
     * 根据模式和查询内容路由到对应增强策略。
     *
     * @param query 用户原始问题
     * @param mode  auto | rewrite | hyde | both | none
     * @return 增强后的查询变体列表（可能包含 1-N 个）
     */
    public List<String> route(String query, String mode) {
        String effectiveMode = resolveMode(query, mode);
        log.debug("Query enhancement mode: {} (requested: {})", effectiveMode, mode);

        return switch (effectiveMode) {
            case "rewrite" -> safeEnhance(rewriter, query);
            case "hyde" -> safeEnhance(hydeGenerator, query);
            case "both" -> both(query);
            default -> List.of(query);
        };
    }

    private String resolveMode(String query, String mode) {
        if (!"auto".equals(mode)) return mode;

        try {
            ChatRequest request = ChatRequest.builder()
                    .messages(List.of(
                            SystemMessage.from(CLASSIFY_PROMPT),
                            UserMessage.from(query + "\n标签：")
                    ))
                    .build();
            String label = chatModel.chat(request).aiMessage().text().trim().toUpperCase();
            if (label.contains("REWRITE")) return "rewrite";
            if (label.contains("HYDE")) return "hyde";
            if (label.contains("BOTH")) return "both";
            if (label.contains("NONE")) return "none";
            return "rewrite";
        } catch (Exception e) {
            log.warn("LLM classification failed, falling back to rewrite", e);
            return "rewrite";
        }
    }

    private List<String> both(String query) {
        List<String> results = new ArrayList<>();
        results.addAll(safeEnhance(rewriter, query));
        results.addAll(safeEnhance(hydeGenerator, query));
        return results;
    }

    private List<String> safeEnhance(QueryEnhancer enhancer, String query) {
        try {
            if (enhancer == null) return List.of(query);
            List<String> result = enhancer.enhance(query);
            return result.isEmpty() ? List.of(query) : result;
        } catch (Exception e) {
            log.warn("Enhancer failed for query", e);
            return List.of(query);
        }
    }

    /**
     * RRF (Reciprocal Rank Fusion) 融合两组检索结果，委托给 {@link RrfFusion}。
     */
    public List<EmbeddingMatch<TextSegment>> fuse(
            List<EmbeddingMatch<TextSegment>> resultA,
            List<EmbeddingMatch<TextSegment>> resultB,
            int topK, int k) {
        return RrfFusion.fuse(resultA, resultB, topK, k);
    }
}
