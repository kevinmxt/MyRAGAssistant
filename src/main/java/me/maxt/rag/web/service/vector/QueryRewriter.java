package me.maxt.rag.web.service.vector;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * 查询改写器，通过 LLM 将口语化查询改写为关键词丰富的检索查询。
 */
public class QueryRewriter implements QueryEnhancer {

    private static final Logger log = LoggerFactory.getLogger(QueryRewriter.class);
    private static final String SYSTEM_PROMPT =
            "将用户口语化问题改写为一个信息检索查询（keywords-rich, 简洁, 不用完整句子）。";

    private final ChatModel chatModel;
    private final int maxTokens;

    public QueryRewriter(ChatModel chatModel, int maxTokens) {
        this.chatModel = chatModel;
        this.maxTokens = maxTokens;
    }

    @Override
    public List<String> enhance(String query) {
        try {
            ChatRequest request = ChatRequest.builder()
                    .messages(List.of(
                            SystemMessage.from(SYSTEM_PROMPT),
                            UserMessage.from("用户问题：" + query + "\n检索查询：")
                    ))
                    .maxOutputTokens(maxTokens)
                    .build();
            String rewritten = chatModel.chat(request).aiMessage().text().trim();
            if (rewritten.isEmpty()) return List.of(query);
            log.debug("Query rewritten: {} -> {}", query, rewritten);
            return List.of(rewritten);
        } catch (Exception e) {
            log.warn("Query rewriting failed, using original query", e);
            return List.of(query);
        }
    }
}
