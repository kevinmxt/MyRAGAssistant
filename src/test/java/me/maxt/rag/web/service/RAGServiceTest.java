package me.maxt.rag.web.service;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.ChatResponseMetadata;
import dev.langchain4j.model.output.TokenUsage;
import me.maxt.rag.web.config.RetrievalConfig;
import me.maxt.rag.web.service.vector.RetrievalPipeline;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RAGServiceTest {

    private ChatModel chatModel;
    private RetrievalPipeline pipeline;
    private RetrievalConfig config;

    @BeforeEach
    void setUp() {
        config = mock(RetrievalConfig.class);
        when(config.getMemorySize()).thenReturn(10);

        chatModel = mock(ChatModel.class);
        ChatResponseMetadata metadata = ChatResponseMetadata.builder()
                .tokenUsage(new TokenUsage(10, 10)).build();
        ChatResponse chatResponse = ChatResponse.builder()
                .aiMessage(AiMessage.from("stub answer"))
                .metadata(metadata).build();
        when(chatModel.chat(any(ChatRequest.class))).thenReturn(chatResponse);

        pipeline = mock(RetrievalPipeline.class);
    }

    @Test
    void shouldComposePromptWithReferenceMaterials() {
        when(pipeline.retrieve(eq("问题"), any())).thenReturn(List.of(
                new RetrievalPipeline.Source("a.txt", "资料甲", 0.9),
                new RetrievalPipeline.Source("b.txt", "资料乙", 0.8)));

        RAGService service = new RAGService(pipeline, chatModel, config);
        RAGService.AnswerWithSources result = service.answerWithSources("问题");

        ArgumentCaptor<ChatRequest> captor = ArgumentCaptor.forClass(ChatRequest.class);
        verify(chatModel).chat(captor.capture());
        String prompt = lastMessageText(captor.getValue());
        assertThat(prompt).contains("参考资料");
        assertThat(prompt).contains("[1] 资料甲");
        assertThat(prompt).contains("[2] 资料乙");
        assertThat(prompt).contains("问题：问题");
        assertThat(result.answer).isEqualTo("stub answer");
        assertThat(result.sources).hasSize(2);
        assertThat(result.sources.get(0).fileName()).isEqualTo("a.txt");
    }

    @Test
    void shouldFallbackWhenNoSources() {
        when(pipeline.retrieve(anyString(), any())).thenReturn(List.of());

        RAGService service = new RAGService(pipeline, chatModel, config);
        RAGService.AnswerWithSources result = service.answerWithSources("问题");

        assertThat(result.answer).isEqualTo("stub answer");
        assertThat(result.sources).isEmpty();
        ArgumentCaptor<ChatRequest> captor = ArgumentCaptor.forClass(ChatRequest.class);
        verify(chatModel).chat(captor.capture());
        String prompt = lastMessageText(captor.getValue());
        assertThat(prompt).contains("（无参考资料）");
    }

    @Test
    void shouldPassOverridesThrough() {
        when(pipeline.retrieve(anyString(), any())).thenReturn(List.of());

        RAGService service = new RAGService(pipeline, chatModel, config);
        service.answerWithSources("q", "hyde", List.of("dense"));

        verify(pipeline).retrieve(eq("q"), eq(new RetrievalPipeline.RetrievalOverrides("hyde", List.of("dense"))));
    }

    /** 取请求中最后一条消息的文本（langchain4j 1.12.1：ChatMessage 接口无 text()，UserMessage 有 singleText()）。 */
    private static String lastMessageText(ChatRequest request) {
        return ((UserMessage) request.messages().get(request.messages().size() - 1)).singleText();
    }
}
