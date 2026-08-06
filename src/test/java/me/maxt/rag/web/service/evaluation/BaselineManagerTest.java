package me.maxt.rag.web.service.evaluation;

import me.maxt.rag.web.config.EvaluationConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class BaselineManagerTest {

    @Test
    void shouldReturnNullWhenNoBaselineExists(@TempDir Path srcDir, @TempDir Path targetDir) {
        EvaluationConfig config = mock(EvaluationConfig.class);
        when(config.getDegradationThreshold()).thenReturn(0.05);
        BaselineManager bm = new BaselineManager(config);
        EvaluationReport result = bm.loadOrCreate("markdown", srcDir, targetDir);
        assertThat(result).isNull();
    }

    @Test
    void shouldDetectSignificantDegradation() {
        EvaluationConfig config = mock(EvaluationConfig.class);
        when(config.getDegradationThreshold()).thenReturn(0.05);
        BaselineManager bm = new BaselineManager(config);

        EvaluationReport current = new EvaluationReport();
        current.metrics = Map.of("recallAtK", new EvaluationReport.MetricScore(0.70));

        EvaluationReport baseline = new EvaluationReport();
        baseline.metrics = Map.of("recallAtK", new EvaluationReport.MetricScore(0.80));

        var degradations = bm.compare(current, baseline);
        assertThat(degradations).hasSize(1);
        assertThat(degradations.get(0).metric).isEqualTo("recallAtK");
        assertThat(degradations.get(0).delta).isEqualTo(-0.10);
    }

    @Test
    void shouldNotFlagSmallDegradation() {
        EvaluationConfig config = mock(EvaluationConfig.class);
        when(config.getDegradationThreshold()).thenReturn(0.05);
        BaselineManager bm = new BaselineManager(config);

        EvaluationReport current = new EvaluationReport();
        current.metrics = Map.of("recallAtK", new EvaluationReport.MetricScore(0.77));

        EvaluationReport baseline = new EvaluationReport();
        baseline.metrics = Map.of("recallAtK", new EvaluationReport.MetricScore(0.80));

        var degradations = bm.compare(current, baseline);
        assertThat(degradations).isEmpty();
    }
}
