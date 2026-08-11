package me.maxt.rag.web.service.environment;

import me.maxt.rag.web.config.EnvCheckConfig;
import me.maxt.rag.web.service.environment.CheckResult.Category;
import me.maxt.rag.web.service.environment.CheckResult.Status;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.function.Consumer;

/**
 * pip 包检测（lightrag、requests）。
 * 可自动安装缺失的包。
 */
public class PipPackageChecker implements DependencyChecker {

    private static final String[] PACKAGES = {"lightrag", "requests"};

    private final String pythonPath;
    private final int probeTimeout;

    public PipPackageChecker(EnvCheckConfig config, String pythonPath) {
        this.pythonPath = pythonPath == null || pythonPath.isBlank() ? "python" : pythonPath;
        this.probeTimeout = config.getProbeTimeoutSeconds();
    }

    @Override
    public String name() { return "lightrag"; }

    @Override
    public CheckResult check() {
        ProcessRunner.ProcessOutput out = ProcessRunner.run(
                new String[]{pythonPath, "-m", "pip", "show", "lightrag", "requests"}, probeTimeout);

        if (out == null) {
            return new CheckResult(name(), Category.PIP, Status.MISSING, null,
                    "缺少 lightrag + requests → python -m pip install lightrag requests");
        }

        String lightragVer = parsePipVersion(out.output(), "lightrag");
        String requestsVer = parsePipVersion(out.output(), "requests");

        boolean lightragOk = lightragVer != null;
        boolean requestsOk = requestsVer != null;

        if (lightragOk && requestsOk) {
            return new CheckResult(name(), Category.PIP, Status.OK, lightragVer,
                    "lightrag " + lightragVer + ", requests " + requestsVer);
        }

        StringBuilder msg = new StringBuilder();
        if (!lightragOk) msg.append("缺少 lightrag");
        if (!requestsOk) {
            if (!msg.isEmpty()) msg.append(" + ");
            msg.append("缺少 requests");
        }
        msg.append(" → python -m pip install lightrag requests");
        return new CheckResult(name(), Category.PIP, Status.MISSING, null, msg.toString());
    }

    @Override
    public boolean canAutoInstall() { return true; }

    @Override
    public boolean autoInstall(Consumer<String> logConsumer) {
        int exitCode = ProcessRunner.runStreaming(
                new String[]{pythonPath, "-m", "pip", "install",
                        "--disable-pip-version-check", "lightrag", "requests"},
                600, logConsumer);
        return exitCode == 0;
    }

    static String parsePipVersion(String output, String packageName) {
        String[] lines = output.split("\n");
        boolean inBlock = false;
        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.startsWith("Name: " + packageName)) {
                inBlock = true;
            }
            if (inBlock && trimmed.startsWith("Version:")) {
                return trimmed.substring("Version:".length()).trim();
            }
            if (inBlock && trimmed.isEmpty()) break;
        }
        return null;
    }
}
