package me.maxt.rag.web.service.environment;

import me.maxt.rag.web.service.vector.MilvusSession;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class MilvusCheckerTest {

    @Test
    void shouldReportOkWhenReachable() {
        MilvusSession session = mock(MilvusSession.class);
        when(session.probe()).thenReturn(new MilvusSession.ProbeResult(true, "2.4.0", "Milvus 可连接"));
        MilvusChecker checker = new MilvusChecker(session);

        CheckResult result = checker.check();

        assertThat(result.status()).isEqualTo(CheckResult.Status.OK);
        assertThat(result.version()).isEqualTo("2.4.0");
    }

    @Test
    void shouldReportMissingWhenUnreachable() {
        MilvusSession session = mock(MilvusSession.class);
        when(session.probe()).thenReturn(new MilvusSession.ProbeResult(false, null, "Milvus 不可达"));
        MilvusChecker checker = new MilvusChecker(session);

        CheckResult result = checker.check();

        assertThat(result.status()).isEqualTo(CheckResult.Status.MISSING);
        assertThat(result.message()).isEqualTo("Milvus 不可达");
    }
}
