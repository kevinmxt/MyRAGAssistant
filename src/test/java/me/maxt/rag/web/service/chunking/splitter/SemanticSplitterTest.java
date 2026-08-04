package me.maxt.rag.web.service.chunking.splitter;

import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.output.Response;
import me.maxt.rag.web.service.chunking.*;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SemanticSplitterTest {

    @Test
    void shouldHaveNameSemantic() {
        EmbeddingModel model = mock(EmbeddingModel.class);
        SemanticSplitter splitter = new SemanticSplitter(model, 0.6);
        assertThat(splitter.name()).isEqualTo("semantic");
    }

    @Test
    void shouldMergeSimilarParagraphs() {
        EmbeddingModel model = mock(EmbeddingModel.class);
        // 返回相同 embedding → 高相似度 → 合并
        Embedding sameEmbedding = Embedding.from(new float[]{0.5f, 0.5f});

        @SuppressWarnings("unchecked")
        Response<List<Embedding>> response = mock(Response.class);
        when(response.content()).thenReturn(List.of(sameEmbedding, sameEmbedding, sameEmbedding));
        when(model.embedAll(any())).thenReturn(response);

        SemanticSplitter splitter = new SemanticSplitter(model, 0.6);

        String md = "段落一的第一句话。还有更多内容。\n\n段落二的文本。一些额外的句子。\n\n段落三的句子。继续写。";
        DocStructure structure = new DocStructure(
                List.of(),
                List.of(new Range(0, 16), new Range(18, 33), new Range(35, 46)),
                List.of(), List.of(), 46);

        List<TextSegment> segments = splitter.split(md, structure, 2000);

        // 相似度高，应合并为1个分段
        assertThat(segments).hasSize(1);
    }

    @Test
    void shouldSplitAtDissimilarityGaps() {
        EmbeddingModel model = mock(EmbeddingModel.class);
        // 第1和第2相似(0.9)，第2和第3不相似(0.1)
        Embedding embA = Embedding.from(new float[]{0.5f, 0.5f});
        Embedding embB = Embedding.from(new float[]{0.6f, 0.6f});  // 与A相似
        Embedding embC = Embedding.from(new float[]{-0.8f, -0.8f}); // 与B不相似

        @SuppressWarnings("unchecked")
        Response<List<Embedding>> response = mock(Response.class);
        when(response.content()).thenReturn(List.of(embA, embB, embC));
        when(model.embedAll(any())).thenReturn(response);

        SemanticSplitter splitter = new SemanticSplitter(model, 0.6);

        String p1 = "这是第一个主题段落，包含足够多的内容文字，用于测试语义相似度计算和断点检测。";
        String p2 = "第一个主题的延续部分，语义与上一段相近，所以余弦相似度应该很高。";
        String p3 = "这是另一个完全不同主题的内容，涉及不同领域，语义与前面段落差异较大，应触发断点切分。";
        String md = p1 + "\n\n" + p2 + "\n\n" + p3;
        int len1 = p1.length();
        int len2 = len1 + 2 + p2.length();
        int len3 = len2 + 2 + p3.length();
        DocStructure structure = new DocStructure(
                List.of(),
                List.of(new Range(0, len1), new Range(len1 + 2, len2), new Range(len2 + 2, len3)),
                List.of(), List.of(), len3);

        List<TextSegment> segments = splitter.split(md, structure, 500);

        // B和C不相似，应切分为至少2个分段（targetChunkSize=500 足够小，不会触发合并）
        assertThat(segments.size()).isGreaterThanOrEqualTo(2);
    }

    @Test
    void shouldHandleEmptyDocument() {
        EmbeddingModel model = mock(EmbeddingModel.class);
        SemanticSplitter splitter = new SemanticSplitter(model, 0.6);

        DocStructure structure = new DocStructure(
                List.of(), List.of(), List.of(), List.of(), 0);

        List<TextSegment> segments = splitter.split("", structure, 1000);

        assertThat(segments).isEmpty();
    }

    @Test
    void shouldHandleSingleParagraph() {
        EmbeddingModel model = mock(EmbeddingModel.class);
        Embedding emb = Embedding.from(new float[]{0.5f});

        @SuppressWarnings("unchecked")
        Response<List<Embedding>> response = mock(Response.class);
        when(response.content()).thenReturn(List.of(emb));
        when(model.embedAll(any())).thenReturn(response);

        SemanticSplitter splitter = new SemanticSplitter(model, 0.5);

        String md = "唯一段落";
        DocStructure structure = new DocStructure(
                List.of(),
                List.of(new Range(0, 4)),
                List.of(), List.of(), 4);

        List<TextSegment> segments = splitter.split(md, structure, 1000);

        assertThat(segments).hasSize(1);
    }

    @Test
    void shouldMergeSmallAdjacentChunks() {
        EmbeddingModel model = mock(EmbeddingModel.class);
        // 两两之间都不相似 → 每个段落独立成 chunk → 后合并介入
        Embedding embA = Embedding.from(new float[]{0.5f, 0.5f});
        Embedding embB = Embedding.from(new float[]{-0.5f, -0.5f});
        Embedding embC = Embedding.from(new float[]{0.8f, 0.8f});

        @SuppressWarnings("unchecked")
        Response<List<Embedding>> response = mock(Response.class);
        when(response.content()).thenReturn(List.of(embA, embB, embC));
        when(model.embedAll(any())).thenReturn(response);

        SemanticSplitter splitter = new SemanticSplitter(model, 0.6);

        String p1 = "第一段短文本。";
        String p2 = "第二段不同内容。";
        String p3 = "第三段其他内容。";
        String md = p1 + "\n\n" + p2 + "\n\n" + p3;

        int len1 = p1.length();
        int len2 = len1 + 2 + p2.length();
        int len3 = len2 + 2 + p3.length();
        DocStructure structure = new DocStructure(
                List.of(),
                List.of(new Range(0, len1), new Range(len1 + 2, len2), new Range(len2 + 2, len3)),
                List.of(), List.of(), len3);

        // targetChunkSize=2000 → minChunkSize=200，三段都不足 200 → 合并
        List<TextSegment> segments = splitter.split(md, structure, 2000);

        // 三段短文本应被后合并合并为更少的分段
        assertThat(segments.size()).isLessThan(3);
    }

    @Test
    void shouldKeepLargeChunksSeparate() {
        // 验证长 chunk 不会被合并
        EmbeddingModel model = mock(EmbeddingModel.class);
        Embedding embA = Embedding.from(new float[]{0.5f, 0.5f});
        Embedding embB = Embedding.from(new float[]{-0.8f, -0.8f});

        @SuppressWarnings("unchecked")
        Response<List<Embedding>> response = mock(Response.class);
        when(response.content()).thenReturn(List.of(embA, embB));
        when(model.embedAll(any())).thenReturn(response);

        SemanticSplitter splitter = new SemanticSplitter(model, 0.6);

        // 足够长的段落（>200 字符），不被后合并合并
        String p1 = "A".repeat(250);
        String p2 = "B".repeat(250);
        String md = p1 + "\n\n" + p2;

        DocStructure structure = new DocStructure(
                List.of(),
                List.of(new Range(0, 250), new Range(252, 502)),
                List.of(), List.of(), 502);

        List<TextSegment> segments = splitter.split(md, structure, 2000);

        // 两个长段落各自达标，不应被合并
        assertThat(segments.size()).isEqualTo(2);
    }
}
