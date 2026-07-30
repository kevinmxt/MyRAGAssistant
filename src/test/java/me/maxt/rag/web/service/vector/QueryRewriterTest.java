package me.maxt.rag.web.service.vector;

import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.chat.response.ChatResponseMetadata;
import dev.langchain4j.model.output.TokenUsage;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class QueryRewriterTest {

    @Test
    void shouldRewriteQuery() {
        ChatModel chatModel = mock(ChatModel.class);
        ChatResponse response = ChatResponse.builder()
                .aiMessage(AiMessage.from("向量数据库 传统数据库 区别 对比"))
                .metadata(ChatResponseMetadata.builder()
                        .tokenUsage(new TokenUsage(10, 5))
                        .build())
                .build();
        when(chatModel.chat(any(ChatRequest.class))).thenReturn(response);

        QueryRewriter rewriter = new QueryRewriter(chatModel, 100);
        List<String> result = rewriter.enhance("向量数据库和传统数据库有什么区别？");

        assertThat(result).hasSize(1);
        assertThat(result.get(0)).isEqualTo("向量数据库 传统数据库 区别 对比");
    }

    @Test
    void shouldReturnOriginalQueryOnLLMFailure() {
        ChatModel chatModel = mock(ChatModel.class);
        when(chatModel.chat(any(ChatRequest.class))).thenThrow(new RuntimeException("LLM timeout"));

        QueryRewriter rewriter = new QueryRewriter(chatModel, 100);
        List<String> result = rewriter.enhance("怎么装这个软件？");

        assertThat(result).hasSize(1);
        assertThat(result.get(0)).isEqualTo("怎么装这个软件？");
    }
}
