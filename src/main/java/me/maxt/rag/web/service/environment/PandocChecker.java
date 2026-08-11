package me.maxt.rag.web.service.environment;

import me.maxt.rag.web.config.EnvCheckConfig;
import me.maxt.rag.web.service.environment.CheckResult.Category;
import me.maxt.rag.web.service.environment.CheckResult.Status;

/**
 * Pandoc 检测（可选依赖——缺失时文档转换降级使用 Tika）。
 */
public class PandocChecker implements DependencyChecker {

    private final int probeTimeout;

    public PandocChecker(EnvCheckConfig config) {
        this.probeTimeout = config.getProbeTimeoutSeconds();
    }

    @Override
    public String name() { return "pandoc"; }

    @Override
    public CheckResult check() {
        ProcessRunner.ProcessOutput out = ProcessRunner.run(
                new String[]{"pandoc", "--version"}, probeTimeout);
        if (out == null) {
            return CheckResult.skipped(name(), "Pandoc 未安装 → 文档转换降级使用 Tika 解析");
        }
        String version = parsePandocVersion(out.output());
        return new CheckResult(name(), Category.BINARY, Status.OK, version,
                "Pandoc " + (version != null ? version : "已安装"));
    }

    private static String parsePandocVersion(String output) {
        if (output == null) return null;
        // "pandoc 3.1.8" -> "3.1.8"
        String[] parts = output.split("\\s+");
        if (parts.length >= 2) return parts[1];
        return null;
    }
}
