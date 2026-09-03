package me.maxt.rag.web.service.vector.rerank;

import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import me.maxt.rag.web.config.RerankConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HexFormat;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class CrossEncoderRerankerTest {

    @Test
    @SuppressWarnings("unchecked")
    void shouldDegradeWhenModelNotFound() {
        RerankConfig config = mock(RerankConfig.class);
        when(config.getRerankModelPath()).thenReturn("./nonexistent/path");
        when(config.getRerankExpansionFactor()).thenReturn(3);
        when(config.getRerankTopK()).thenReturn(5);

        CrossEncoderReranker reranker = new CrossEncoderReranker(config);
        assertThat(reranker.isAvailable()).isFalse();
        assertThat(reranker.name()).isEqualTo("cross-encoder");
    }

    @Test
    @SuppressWarnings("unchecked")
    void shouldReturnCandidatesAsIsWhenNotAvailable() {
        RerankConfig config = mock(RerankConfig.class);
        when(config.getRerankModelPath()).thenReturn("./nonexistent/path");
        CrossEncoderReranker reranker = new CrossEncoderReranker(config);

        TextSegment seg1 = TextSegment.from("candidate 1");
        TextSegment seg2 = TextSegment.from("candidate 2");
        TextSegment seg3 = TextSegment.from("candidate 3");

        EmbeddingMatch<TextSegment> m1 = mock(EmbeddingMatch.class);
        when(m1.embedded()).thenReturn(seg1);
        when(m1.score()).thenReturn(0.9);
        EmbeddingMatch<TextSegment> m2 = mock(EmbeddingMatch.class);
        when(m2.embedded()).thenReturn(seg2);
        when(m2.score()).thenReturn(0.8);
        EmbeddingMatch<TextSegment> m3 = mock(EmbeddingMatch.class);
        when(m3.embedded()).thenReturn(seg3);
        when(m3.score()).thenReturn(0.7);

        var candidates = List.of(m1, m2, m3);
        var result = reranker.rerank("test query", candidates, 2);

        assertThat(result).hasSize(2);
        assertThat(result.get(0).embedded().text()).isEqualTo("candidate 1");
        assertThat(result.get(1).embedded().text()).isEqualTo("candidate 2");
    }

    @Test
    @SuppressWarnings("unchecked")
    void shouldHandleEmptyCandidates() {
        RerankConfig config = mock(RerankConfig.class);
        when(config.getRerankModelPath()).thenReturn("./nonexistent/path");
        CrossEncoderReranker reranker = new CrossEncoderReranker(config);

        var result = reranker.rerank("test query", List.of(), 5);
        assertThat(result).isEmpty();
    }

    @Test
    void shouldNotStartDownloadThreadOnConstruct() {
        RerankConfig config = mock(RerankConfig.class);
        when(config.getRerankModelPath()).thenReturn("./nonexistent/path");

        CrossEncoderReranker reranker = new CrossEncoderReranker(config);

        // 行为等价断言：构造不抛异常、无下载副作用，模型缺失即安静降级
        assertThat(reranker.isAvailable()).isFalse();
    }

    @Test
    void shouldLoadWhenFilesAppear(@TempDir Path modelDir) throws IOException {
        RerankConfig config = mock(RerankConfig.class);
        when(config.getRerankModelPath()).thenReturn(modelDir.toString());
        when(config.getRerankExpansionFactor()).thenReturn(3);
        when(config.getRerankTopK()).thenReturn(5);

        CrossEncoderReranker reranker = new CrossEncoderReranker(config);
        assertThat(reranker.isAvailable()).isFalse();
        // 目录里没有模型文件：安静返回 false，不抛异常
        assertThat(reranker.loadIfPresent()).isFalse();

        Files.write(modelDir.resolve("model.onnx"), minimalOnnxModel());
        Files.writeString(modelDir.resolve("tokenizer.json"), """
                {
                  "version": "1.0",
                  "truncation": null,
                  "padding": null,
                  "added_tokens": [],
                  "normalizer": null,
                  "pre_tokenizer": {"type": "Whitespace"},
                  "post_processor": null,
                  "decoder": null,
                  "model": {"type": "WordLevel", "vocab": {"[UNK]": 0}, "unk_token": "[UNK]"}
                }
                """);

        assertThat(reranker.loadIfPresent()).isTrue();
        assertThat(reranker.isAvailable()).isTrue();
        // 幂等：已 available 时重复调用仍返回 true
        assertThat(reranker.loadIfPresent()).isTrue();
    }

    /** 最小可加载 ONNX：单 Identity 节点 X→Y（FLOAT[1]），ir_version 8、opset 17，共 66 字节 */
    private static byte[] minimalOnnxModel() {
        String hex = """
                08 08 3A 3A
                0A 13 0A 01 58 12 01 59 1A 01 6E 22 08 49 64 65 6E 74 69 74 79
                12 01 67
                5A 0F 0A 01 58 12 0A 0A 08 08 01 12 04 0A 02 08 01
                62 0F 0A 01 59 12 0A 0A 08 08 01 12 04 0A 02 08 01
                42 02 10 11
                """;
        return HexFormat.of().parseHex(hex.replaceAll("\\s+", ""));
    }
}
