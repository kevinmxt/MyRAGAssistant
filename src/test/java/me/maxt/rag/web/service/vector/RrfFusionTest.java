package me.maxt.rag.web.service.vector;

import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RrfFusionTest {

    @Test
    void shouldFuseTwoResultLists() {
        TextSegment segA = TextSegment.from("chunk A");
        TextSegment segB = TextSegment.from("chunk B");
        TextSegment segC = TextSegment.from("chunk C");

        EmbeddingMatch<TextSegment> matchA1 = match(segA, 0.9);
        EmbeddingMatch<TextSegment> matchB1 = match(segB, 0.8);
        EmbeddingMatch<TextSegment> matchA2 = match(segA, 0.7);
        EmbeddingMatch<TextSegment> matchC1 = match(segC, 0.6);

        List<EmbeddingMatch<TextSegment>> resultA = List.of(matchA1, matchB1);
        List<EmbeddingMatch<TextSegment>> resultB = List.of(matchA2, matchC1);

        List<EmbeddingMatch<TextSegment>> fused = RrfFusion.fuse(resultA, resultB, 3, 60);

        // chunk A 在两路都出现，RRF 分数最高
        assertThat(fused).hasSize(3);
        assertThat(fused.get(0).embedded().text()).isEqualTo("chunk A");
    }

    @Test
    void shouldFuseNResultLists() {
        TextSegment segA = TextSegment.from("chunk A");
        TextSegment segB = TextSegment.from("chunk B");

        List<EmbeddingMatch<TextSegment>> list1 = List.of(match(segA, 0.9));
        List<EmbeddingMatch<TextSegment>> list2 = List.of(match(segB, 0.8));
        List<EmbeddingMatch<TextSegment>> list3 = List.of(match(segA, 0.7));

        List<EmbeddingMatch<TextSegment>> fused = RrfFusion.fuseN(
                List.of(list1, list2, list3), 2, 60);

        assertThat(fused).hasSize(2);
        assertThat(fused.get(0).embedded().text()).isEqualTo("chunk A");
    }

    @Test
    void shouldDeduplicateByTextKey() {
        TextSegment seg = TextSegment.from("唯一内容");
        List<EmbeddingMatch<TextSegment>> list1 = List.of(match(seg, 0.9));
        List<EmbeddingMatch<TextSegment>> list2 = List.of(match(seg, 0.8));

        List<EmbeddingMatch<TextSegment>> fused = RrfFusion.fuse(list1, list2, 5, 60);

        assertThat(fused).hasSize(1);
    }

    @Test
    void shouldHandleEmptyInput() {
        TextSegment seg = TextSegment.from("x");
        List<EmbeddingMatch<TextSegment>> resultA = List.of(match(seg, 0.9));
        List<EmbeddingMatch<TextSegment>> resultB = List.of();

        List<EmbeddingMatch<TextSegment>> fused = RrfFusion.fuse(resultA, resultB, 5, 60);
        assertThat(fused).hasSize(1);
    }

    @Test
    void shouldHandleAllEmpty() {
        List<EmbeddingMatch<TextSegment>> fused = RrfFusion.fuseN(
                List.of(List.of(), List.of()), 5, 60);
        assertThat(fused).isEmpty();
    }

    @SuppressWarnings("unchecked")
    private static EmbeddingMatch<TextSegment> match(TextSegment seg, double score) {
        EmbeddingMatch<TextSegment> m = mock(EmbeddingMatch.class);
        when(m.embedded()).thenReturn(seg);
        when(m.score()).thenReturn(score);
        return m;
    }
}
