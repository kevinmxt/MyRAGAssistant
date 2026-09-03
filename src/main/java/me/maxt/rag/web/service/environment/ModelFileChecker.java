package me.maxt.rag.web.service.environment;

import me.maxt.rag.web.service.environment.CheckResult.Category;
import me.maxt.rag.web.service.environment.CheckResult.Status;
import me.maxt.rag.web.service.model.DownloadState;
import me.maxt.rag.web.service.model.ModelArtifact;
import me.maxt.rag.web.service.model.ModelDownloadException;
import me.maxt.rag.web.service.model.ModelRepository;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * 模型文件检测：逐制品查询模型仓库的真实状态（含下载记账），
 * 并支持一键安装——对非 PRESENT 制品委托仓库 ensurePresent 下载，日志直通安装 SSE。
 */
public class ModelFileChecker implements DependencyChecker {

    private final ModelRepository repository;
    private final List<ModelArtifact> artifacts;

    public ModelFileChecker(ModelRepository repository, ModelArtifact... artifacts) {
        this.repository = repository;
        this.artifacts = List.of(artifacts);
    }

    @Override
    public String name() { return "model-files"; }

    @Override
    public CheckResult check() {
        List<String> ready = new ArrayList<>();
        List<String> problems = new ArrayList<>();
        boolean failed = false;
        for (ModelArtifact artifact : artifacts) {
            DownloadState state = repository.state(artifact.key());
            switch (state.status()) {
                case PRESENT -> ready.add(artifact.key() + " (" + state.detail() + ")");
                case DOWNLOADING -> problems.add(artifact.key() + " 下载中: " + state.detail());
                case MISSING -> problems.add(artifact.key() + " 缺失 → 可一键安装");
                case FAILED -> {
                    problems.add(artifact.key() + " 下载失败: " + state.detail());
                    failed = true;
                }
            }
        }
        if (problems.isEmpty()) {
            return new CheckResult(name(), Category.MODEL, Status.OK, null,
                    String.join(", ", ready));
        }
        return new CheckResult(name(), Category.MODEL, failed ? Status.ERROR : Status.MISSING, null,
                String.join("; ", problems));
    }

    @Override
    public boolean canAutoInstall() { return true; }

    @Override
    public boolean autoInstall(Consumer<String> log) {
        try {
            for (ModelArtifact artifact : artifacts) {
                if (repository.state(artifact.key()).status() != DownloadState.Status.PRESENT) {
                    repository.ensurePresent(artifact, log);
                }
            }
            return true;
        } catch (ModelDownloadException e) {
            // 失败原因回流 install-log：EnvironmentChecker 广播"请检查日志"，此流不能没有失败信息
            log.accept("下载失败: " + e.getMessage());
            return false;
        }
    }
}
