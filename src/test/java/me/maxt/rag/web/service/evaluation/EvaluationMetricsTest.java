package me.maxt.rag.web.service.evaluation;

import org.junit.jupiter.api.Test;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class EvaluationMetricsTest {

    @Test
    void recallAtKShouldReturnFullRecallWhenAllRelevantFound() {
        RecallAtK metric = new RecallAtK(5);
        TestCase tc = new TestCase("t1", "q", List.of("a.txt", "b.txt"), null, null);
        double score = metric.calculate("q", List.of("a.txt", "b.txt", "c.txt"), tc);
        assertThat(score).isEqualTo(1.0);
    }

    @Test
    void recallAtKShouldReturnPartialRecall() {
        RecallAtK metric = new RecallAtK(5);
        TestCase tc = new TestCase("t1", "q", List.of("a.txt", "b.txt", "c.txt"), null, null);
        double score = metric.calculate("q", List.of("a.txt", "x.txt", "y.txt"), tc);
        assertThat(score).isCloseTo(1.0 / 3.0, within(0.01));
    }

    @Test
    void precisionAtKShouldReturnRatio() {
        PrecisionAtK metric = new PrecisionAtK(3);
        TestCase tc = new TestCase("t1", "q", List.of("a.txt", "b.txt"), null, null);
        double score = metric.calculate("q", List.of("a.txt", "x.txt", "y.txt"), tc);
        assertThat(score).isCloseTo(1.0 / 3.0, within(0.01));
    }

    @Test
    void mrrShouldReturnReciprocalOfFirstHit() {
        MRR metric = new MRR();
        TestCase tc = new TestCase("t1", "q", List.of("a.txt"), null, null);
        double score = metric.calculate("q", List.of("x.txt", "a.txt", "y.txt"), tc);
        assertThat(score).isEqualTo(0.5);
    }

    @Test
    void mrrShouldReturnZeroWhenNoHit() {
        MRR metric = new MRR();
        TestCase tc = new TestCase("t1", "q", List.of("a.txt"), null, null);
        double score = metric.calculate("q", List.of("x.txt", "y.txt"), tc);
        assertThat(score).isEqualTo(0.0);
    }

    @Test
    void ndcgAtKShouldBeOneWhenPerfectOrder() {
        NDCGAtK metric = new NDCGAtK(5);
        TestCase tc = new TestCase("t1", "q", List.of("a.txt", "b.txt"), null, null);
        double score = metric.calculate("q", List.of("a.txt", "b.txt", "c.txt"), tc);
        assertThat(score).isCloseTo(1.0, within(0.01));
    }

    @Test
    void ndcgAtKShouldBeLowerWhenRelevantRankedLast() {
        NDCGAtK metric = new NDCGAtK(3);
        TestCase tc = new TestCase("t1", "q", List.of("a.txt", "b.txt"), null, null);
        double good = metric.calculate("q", List.of("a.txt", "b.txt"), tc);
        double bad = metric.calculate("q", List.of("x.txt", "y.txt", "a.txt"), tc);
        assertThat(good).isGreaterThan(bad);
    }

    @Test
    void shouldMatchFilenameIgnoringPathPrefix() {
        RecallAtK metric = new RecallAtK(5);
        TestCase tc = new TestCase("t1", "q", List.of("README.md"), null, null);
        double score = metric.calculate("q", List.of("/docs/subdir/README.md"), tc);
        assertThat(score).isEqualTo(1.0);
    }

    @Test
    void shouldMatchWindowsPathPrefix() {
        RecallAtK metric = new RecallAtK(5);
        TestCase tc = new TestCase("t1", "q", List.of("guide.txt"), null, null);
        double score = metric.calculate("q", List.of("C:\\data\\guide.txt"), tc);
        assertThat(score).isEqualTo(1.0);
    }
}
