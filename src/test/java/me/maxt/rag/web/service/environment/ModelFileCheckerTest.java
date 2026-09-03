package me.maxt.rag.web.service.environment;

import me.maxt.rag.web.service.model.DownloadState;
import me.maxt.rag.web.service.model.DownloadState.Status;
import me.maxt.rag.web.service.model.ModelArtifact;
import me.maxt.rag.web.service.model.ModelDownloadException;
import me.maxt.rag.web.service.model.ModelRepository;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ModelFileChecker 单元测试：fake 仓库可编程状态，无网络。
 */
class ModelFileCheckerTest {

    @Test
    void shouldReportOkWhenAllPresent() {
        FakeRepository repo = new FakeRepository();
        repo.put("reranker", new DownloadState(Status.PRESENT, "3 个文件就绪"));
        repo.put("embedding", new DownloadState(Status.PRESENT, "文件齐全"));
        ModelFileChecker checker = new ModelFileChecker(repo, artifact("reranker"), artifact("embedding"));

        CheckResult result = checker.check();

        assertThat(result.status()).isEqualTo(CheckResult.Status.OK);
        assertThat(result.ok()).isTrue();
        assertThat(result.category()).isEqualTo(CheckResult.Category.MODEL);
        assertThat(result.message()).contains("reranker", "embedding");
    }

    @Test
    void shouldReportDownloadingWithDetail() {
        FakeRepository repo = new FakeRepository();
        repo.put("reranker", new DownloadState(Status.DOWNLOADING, "model.onnx"));
        ModelFileChecker checker = new ModelFileChecker(repo, artifact("reranker"));

        CheckResult result = checker.check();

        assertThat(result.status()).isEqualTo(CheckResult.Status.MISSING);
        assertThat(result.message()).contains("下载中").contains("model.onnx");
    }

    @Test
    void shouldReportFailedWithReason() {
        FakeRepository repo = new FakeRepository();
        repo.put("reranker", new DownloadState(Status.FAILED, "全部镜像下载失败"));
        ModelFileChecker checker = new ModelFileChecker(repo, artifact("reranker"));

        CheckResult result = checker.check();

        assertThat(result.status()).isEqualTo(CheckResult.Status.ERROR);
        assertThat(result.message()).contains("全部镜像下载失败");
    }

    @Test
    void shouldReportMissingAndInstallable() {
        FakeRepository repo = new FakeRepository();
        repo.put("reranker", new DownloadState(Status.MISSING, "文件缺失"));
        ModelFileChecker checker = new ModelFileChecker(repo, artifact("reranker"));

        CheckResult result = checker.check();

        assertThat(result.status()).isEqualTo(CheckResult.Status.MISSING);
        assertThat(result.message()).contains("一键安装");
        assertThat(checker.canAutoInstall()).isTrue();
    }

    @Test
    void shouldAutoInstallOnlyMissingArtifacts() {
        FakeRepository repo = new FakeRepository();
        repo.put("reranker", new DownloadState(Status.PRESENT, "文件齐全"));
        repo.put("embedding", new DownloadState(Status.MISSING, "文件缺失"));
        ModelFileChecker checker = new ModelFileChecker(repo, artifact("reranker"), artifact("embedding"));
        List<String> lines = new ArrayList<>();

        boolean ok = checker.autoInstall(lines::add);

        assertThat(ok).isTrue();
        assertThat(repo.ensuredKeys).containsExactly("embedding");
        assertThat(lines).anyMatch(line -> line.contains("embedding"));
    }

    @Test
    void shouldReturnFalseWhenDownloadFails() {
        FakeRepository repo = new FakeRepository();
        repo.put("reranker", new DownloadState(Status.MISSING, "文件缺失"));
        repo.failOnEnsure = new ModelDownloadException("全部镜像不可用");
        ModelFileChecker checker = new ModelFileChecker(repo, artifact("reranker"));

        boolean ok = checker.autoInstall(line -> { });

        assertThat(ok).isFalse();
    }

    // ---- 工具 ----

    private static ModelArtifact artifact(String key) {
        return new ModelArtifact(key, "test-repo/" + key,
                Map.of("model.onnx", "onnx/model.onnx"), Path.of("models/" + key));
    }

    /** 可编程状态 + 记录 ensurePresent 调用的假仓库 */
    private static final class FakeRepository implements ModelRepository {

        private final Map<String, DownloadState> states = new HashMap<>();
        private final List<String> ensuredKeys = new ArrayList<>();
        private RuntimeException failOnEnsure;

        void put(String key, DownloadState state) {
            states.put(key, state);
        }

        @Override
        public void ensurePresent(ModelArtifact artifact) {
            ensurePresent(artifact, line -> { });
        }

        @Override
        public void ensurePresent(ModelArtifact artifact, Consumer<String> log) {
            ensuredKeys.add(artifact.key());
            if (failOnEnsure != null) {
                throw failOnEnsure;
            }
            log.accept("下载 " + artifact.key());
            states.put(artifact.key(), new DownloadState(Status.PRESENT, "文件齐全"));
        }

        @Override
        public DownloadState state(String key) {
            return states.getOrDefault(key, new DownloadState(Status.MISSING, "未注册"));
        }
    }
}
