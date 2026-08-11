package me.maxt.rag.web.service.environment;

import me.maxt.rag.web.config.EnvCheckConfig;
import me.maxt.rag.web.service.environment.CheckResult.Category;
import me.maxt.rag.web.service.environment.CheckResult.Status;

/**
 * Python 运行时版本检测。
 */
public class PythonChecker implements DependencyChecker {

    private final String pythonPath;
    private final int probeTimeout;

    public PythonChecker(EnvCheckConfig config, String pythonPath) {
        this.pythonPath = pythonPath == null || pythonPath.isBlank() ? "python" : pythonPath;
        this.probeTimeout = config.getProbeTimeoutSeconds();
    }

    @Override
    public String name() { return "python"; }

    @Override
    public CheckResult check() {
        ProcessRunner.ProcessOutput out = ProcessRunner.run(
                new String[]{pythonPath, "--version"}, probeTimeout);
        if (out == null) {
            return new CheckResult(name(), Category.RUNTIME, Status.MISSING, null,
                    "未检测到 Python → 请安装 Python 3.10+ 并加入 PATH");
        }

        String version = parsePythonVersion(out.output());
        if (version == null || !isPython3_10Plus(version)) {
            return new CheckResult(name(), Category.RUNTIME, Status.MISSING, version,
                    "Python 版本过低或无法解析 (" + out.output() + ")，需要 3.10+");
        }

        return new CheckResult(name(), Category.RUNTIME, Status.OK, version,
                "Python 运行时就绪");
    }

    static String parsePythonVersion(String output) {
        if (output == null) return null;
        String[] parts = output.split("\\s+");
        for (String part : parts) {
            if (part.matches("\\d+\\.\\d+(\\.\\d+)?")) return part;
        }
        return null;
    }

    static boolean isPython3_10Plus(String version) {
        String[] parts = version.split("\\.");
        if (parts.length < 2) return false;
        int major = Integer.parseInt(parts[0]);
        int minor = Integer.parseInt(parts[1]);
        return major > 3 || (major == 3 && minor >= 10);
    }
}
