package me.maxt.rag.web.service.vector;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.ChatResponseMetadata;
import dev.langchain4j.model.output.TokenUsage;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class HyDEGeneratorTest {

    @Test
    void shouldGenerateHypotheticalDocument() {
        ChatModel chatModel = mock(ChatModel.class);
        ChatResponse response = ChatResponse.builder()
                .aiMessage(AiMessage.from("向量数据库通过将数据表示为高维向量进行相似度检索，而传统数据库使用精确匹配。"))
                .metadata(ChatResponseMetadata.builder()
                        .tokenUsage(new TokenUsage(30, 20))
                        .build())
                .build();
        when(chatModel.chat(any(ChatRequest.class))).thenReturn(response);

        HyDEGenerator generator = new HyDEGenerator(chatModel, 200);
        List<String> result = generator.enhance("向量数据库和传统数据库有什么区别？");

        assertThat(result).hasSize(1);
        assertThat(result.get(0)).contains("向量数据库");
    }

    @Test
    void shouldReturnEmptyListOnLLMFailure() {
        ChatModel chatModel = mock(ChatModel.class);
        when(chatModel.chat(any(ChatRequest.class))).thenThrow(new RuntimeException("LLM error"));

        HyDEGenerator generator = new HyDEGenerator(chatModel, 200);
        List<String> result = generator.enhance("什么是向量检索？");

        assertThat(result).isEmpty();
    }
}
