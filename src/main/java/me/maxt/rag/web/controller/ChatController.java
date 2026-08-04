package me.maxt.rag.web.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.javalin.http.Context;
import me.maxt.rag.web.service.RAGService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;

/**
 * 对话控制器，处理用户的问答请求。
 *
 * <p>接收用户的自然语言问题，通过 {@link RAGService} 执行检索增强生成，返回答案及参考来源。</p>
 *
 * @author maxt
 * @since 1.0
 */
public class ChatController {

    private static final Logger log = LoggerFactory.getLogger(ChatController.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final RAGService ragService;

    /**
     * @param ragService RAG 核心服务
     */
    public ChatController(RAGService ragService) {
        this.ragService = ragService;
    }

    /**
     * 处理对话请求。
     *
     * <p>请求格式：{@code POST /api/chat}，Body: {@code {"query": "用户问题"}}</p>
     * <p>响应格式：{@code {"answer": "回答文本", "sources": [{"fileName": "...", "text": "...", "score": 0.95}]}}</p>
     *
     * @param ctx Javalin HTTP 上下文
     */
    public void handleChat(Context ctx) {
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> body = ctx.bodyAsClass(Map.class);
            String query = (String) body.get("query");

            if (query == null || query.trim().isEmpty()) {
                ctx.status(400).json(Map.of("error", "Query is required"));
                return;
            }

            // 解析 enhancement 参数（可选，默认 null → RAGService 内部用 config 默认值）
            String enhancement = (String) body.getOrDefault("enhancement", null);

            // 解析 recall 参数（可选，JSON 数组，如 ["dense", "sparse", "graph"]；
            // 默认 null → RAGService 内部用 config 默认召回模式）
            @SuppressWarnings("unchecked")
            List<String> recallModes = null;
            Object recallObj = body.get("recall");
            if (recallObj instanceof List) {
                recallModes = (List<String>) recallObj;
            }

            log.info("Chat query: {} (enhancement: {}, recall: {})", query, enhancement, recallModes);
            RAGService.AnswerWithSources result = ragService.answerWithSources(query.trim(), enhancement, recallModes);

            ctx.json(result);
        } catch (Exception e) {
            log.error("Chat error", e);
            ctx.status(500).json(Map.of("error", "Failed to process query: " + e.getMessage()));
        }
    }
}
