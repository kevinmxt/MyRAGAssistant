package me.maxt.rag.web.service.vector;

import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collections;
import java.util.List;

/**
 * 假设文档生成器（HyDE: Hypothetical Document Embeddings），
 * 通过 LLM 生成一段假设的文档内容来回答用户问题，
 * 利用假设文档与真实文档的嵌入向量更接近的特性提升检索召回率。
 */
public class HyDEGenerator implements QueryEnhancer {

    private static final Logger log = LoggerFactory.getLogger(HyDEGenerator.class);
    private static final String SYSTEM_PROMPT =
            "根据用户问题，生成一段假设的文档内容来回答这个问题（不超过%d字）。";

    private final ChatModel chatModel;
    private final int maxTokens;

    public HyDEGenerator(ChatModel chatModel, int maxTokens) {
        this.chatModel = chatModel;
        this.maxTokens = maxTokens;
    }

    @Override
    public List<String> enhance(String query) {
        try {
            String prompt = String.format(SYSTEM_PROMPT, maxTokens);
            ChatRequest request = ChatRequest.builder()
                    .messages(List.of(
                            SystemMessage.from(prompt),
                            UserMessage.from("用户问题：" + query + "\n假设文档内容：")
                    ))
                    .build();
            String hypothetical = chatModel.chat(request).aiMessage().text().trim();
            if (hypothetical.isEmpty()) return Collections.emptyList();
            log.debug("HyDE generated: {}...", hypothetical.substring(0, Math.min(50, hypothetical.length())));
            return List.of(hypothetical);
        } catch (Exception e) {
            log.warn("HyDE generation failed", e);
            return Collections.emptyList();
        }
    }
}
