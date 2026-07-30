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

        String md = "段落一\n\n段落二\n\n段落三";
        DocStructure structure = new DocStructure(
                List.of(),
                List.of(new Range(0, 3), new Range(5, 8), new Range(10, 13)),
                List.of(), List.of(), 13);

        List<TextSegment> segments = splitter.split(md, structure, 2000);

        // B和C不相似，应切分为至少2个分段
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
}
