package me.maxt.rag.web.service.chunking.splitter;

import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.chat.ChatModel;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AgentRefinerTest {

    @Test
    void shouldReturnRefinedSegmentsWhenApiSucceeds() {
        ChatModel chatModel = mock(ChatModel.class);
        when(chatModel.chat(any(String.class)))
                .thenReturn("段落1内容\n---SPLIT---\n段落2内容");

        AgentRefiner refiner = new AgentRefiner(chatModel);

        List<TextSegment> versionA = List.of(TextSegment.from("段落1内容段落2内容"));
        List<TextSegment> versionB = List.of(TextSegment.from("段落1"), TextSegment.from("段落2"));

        List<TextSegment> result = refiner.refine(versionA, versionB, "段落1内容\n\n段落2内容", 1000);

        assertThat(result).hasSize(2);
        assertThat(result.get(0).text()).contains("段落1");
        assertThat(result.get(1).text()).contains("段落2");
    }

    @Test
    void shouldFallbackToLongerVersionOnApiFailure() {
        ChatModel chatModel = mock(ChatModel.class);
        when(chatModel.chat(any(String.class)))
                .thenThrow(new RuntimeException("API 超时"));

        AgentRefiner refiner = new AgentRefiner(chatModel);

        List<TextSegment> versionA = List.of(TextSegment.from("短"));
        List<TextSegment> versionB = List.of(
                TextSegment.from("较长的分段一"),
                TextSegment.from("较长的分段二"));

        List<TextSegment> result = refiner.refine(versionA, versionB, "测试", 1000);

        // 应降级返回分段数更多的版本
        assertThat(result).hasSize(2);
    }

    @Test
    void shouldReturnOriginalWhenBothEmpty() {
        ChatModel chatModel = mock(ChatModel.class);
        AgentRefiner refiner = new AgentRefiner(chatModel);

        List<TextSegment> result = refiner.refine(List.of(), List.of(), "", 1000);

        assertThat(result).isEmpty();
    }
}
