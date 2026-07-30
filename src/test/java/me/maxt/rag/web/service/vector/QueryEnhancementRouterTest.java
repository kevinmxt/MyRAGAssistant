package me.maxt.rag.web.service.vector;

import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.ChatResponseMetadata;
import dev.langchain4j.model.output.TokenUsage;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import me.maxt.rag.web.config.QueryEnhancementConfig;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class QueryEnhancementRouterTest {

    private final QueryEnhancer rewriter = mock(QueryRewriter.class);

    @Test
    void shouldRouteToRewriterWhenModeIsRewrite() {
        QueryEnhancementRouter router = new QueryEnhancementRouter(rewriter, null, null, null);
        when(rewriter.enhance("query")).thenReturn(List.of("rewritten query"));

        List<String> result = router.route("query", "rewrite");

        assertThat(result).containsExactly("rewritten query");
    }

    @Test
    void shouldRouteToHydeWhenModeIsHyde() {
        QueryEnhancer hyde = mock(HyDEGenerator.class);
        when(hyde.enhance("query")).thenReturn(List.of("hypothetical doc"));

        QueryEnhancementRouter router = new QueryEnhancementRouter(null, hyde, null, null);
        List<String> result = router.route("query", "hyde");

        assertThat(result).containsExactly("hypothetical doc");
    }

    @Test
    void shouldRouteToBothWhenModeIsBoth() {
        when(rewriter.enhance("query")).thenReturn(List.of("rewritten"));
        QueryEnhancer hyde = mock(HyDEGenerator.class);
        when(hyde.enhance("query")).thenReturn(List.of("hyde"));

        QueryEnhancementRouter router = new QueryEnhancementRouter(rewriter, hyde, null, null);
        List<String> result = router.route("query", "both");

        assertThat(result).containsExactly("rewritten", "hyde");
    }

    @Test
    void shouldSkipEnhancementWhenModeIsNone() {
        QueryEnhancementRouter router = new QueryEnhancementRouter(null, null, null, null);
        List<String> result = router.route("direct query", "none");

        assertThat(result).containsExactly("direct query");
    }

    @Test
    void shouldAutoClassifyViaLLM() {
        ChatModel chatModel = mock(ChatModel.class);
        ChatResponse response = ChatResponse.builder()
                .aiMessage(AiMessage.from("REWRITE"))
                .metadata(ChatResponseMetadata.builder()
                        .tokenUsage(new TokenUsage(5, 1))
                        .build())
                .build();
        when(chatModel.chat(any(ChatRequest.class))).thenReturn(response);
        when(rewriter.enhance("怎么装")).thenReturn(List.of("安装教程"));

        QueryEnhancementConfig config = mock(QueryEnhancementConfig.class);
        QueryEnhancementRouter router = new QueryEnhancementRouter(rewriter, null, chatModel, config);
        List<String> result = router.route("怎么装", "auto");

        assertThat(result).containsExactly("安装教程");
    }

    @Test
    void shouldFallbackToRewriteOnClassificationFailure() {
        ChatModel chatModel = mock(ChatModel.class);
        when(chatModel.chat(any(ChatRequest.class))).thenThrow(new RuntimeException("timeout"));
        when(rewriter.enhance("test")).thenReturn(List.of("rewritten"));

        QueryEnhancementConfig config = mock(QueryEnhancementConfig.class);
        QueryEnhancementRouter router = new QueryEnhancementRouter(rewriter, null, chatModel, config);
        List<String> result = router.route("test", "auto");

        assertThat(result).containsExactly("rewritten");
    }

    @Test
    void shouldFuseTwoResultSetsWithRRF() {
        QueryEnhancementRouter router = new QueryEnhancementRouter(null, null, null, null);

        TextSegment sA = TextSegment.from("doc A");
        TextSegment sB = TextSegment.from("doc B");
        TextSegment sC = TextSegment.from("doc C");

        EmbeddingMatch<TextSegment> matchA1 = new EmbeddingMatch<>(0.9, "a1", Embedding.from(new float[]{0.1f}), sA);
        EmbeddingMatch<TextSegment> matchB1 = new EmbeddingMatch<>(0.8, "b1", Embedding.from(new float[]{0.2f}), sB);

        EmbeddingMatch<TextSegment> matchA2 = new EmbeddingMatch<>(0.7, "a2", Embedding.from(new float[]{0.1f}), sA);
        EmbeddingMatch<TextSegment> matchC1 = new EmbeddingMatch<>(0.6, "c1", Embedding.from(new float[]{0.3f}), sC);

        List<EmbeddingMatch<TextSegment>> result = router.fuse(
                List.of(matchA1, matchB1),
                List.of(matchA2, matchC1),
                3, 60);

        assertThat(result).hasSize(3);
        // doc A appears in both lists — RRF should rank it higher
        assertThat(result.get(0).embedded().text()).isEqualTo("doc A");
    }
}
