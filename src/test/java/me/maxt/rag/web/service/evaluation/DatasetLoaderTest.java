package me.maxt.rag.web.service.evaluation;

import org.junit.jupiter.api.Test;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;

class DatasetLoaderTest {

    @Test
    void shouldLoadTestCasesFromJson() {
        DatasetLoader loader = new DatasetLoader();
        DatasetFile ds = loader.load("markdown");
        assertThat(ds).isNotNull();
        assertThat(ds.format()).isEqualTo("markdown");
        assertThat(ds.testCases()).hasSize(2);
        assertThat(ds.testCases().get(0).id()).isEqualTo("md-001");
        assertThat(ds.testCases().get(1).id()).isEqualTo("md-002");
    }

    @Test
    void shouldReturnNullForMissingFormat() {
        DatasetLoader loader = new DatasetLoader();
        DatasetFile ds = loader.load("nonexistent");
        assertThat(ds).isNull();
    }

    @Test
    void shouldValidateMissingFields() {
        DatasetLoader loader = new DatasetLoader();
        TestCase bad = new TestCase("", null, List.of(), null, null);
        DatasetFile ds = new DatasetFile("test", "1.0", "", List.of(bad));
        List<String> errors = loader.validate(ds);
        assertThat(errors).isNotEmpty();
    }
}
