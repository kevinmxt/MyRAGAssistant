package me.maxt.rag.web.service.environment;

import me.maxt.rag.web.config.EnvCheckConfig;
import me.maxt.rag.web.service.environment.CheckResult.Category;
import me.maxt.rag.web.service.environment.CheckResult.Status;

/**
 * Tesseract OCR 检测（可选依赖——缺失时图片文字提取不可用）。
 */
public class TesseractChecker implements DependencyChecker {

    private final int probeTimeout;

    public TesseractChecker(EnvCheckConfig config) {
        this.probeTimeout = config.getProbeTimeoutSeconds();
    }

    @Override
    public String name() { return "tesseract"; }

    @Override
    public CheckResult check() {
        ProcessRunner.ProcessOutput out = ProcessRunner.run(
                new String[]{"tesseract", "--version"}, probeTimeout);
        if (out == null) {
            return CheckResult.skipped(name(), "Tesseract OCR 未安装 → 图片文字提取不可用");
        }
        String version = parseTesseractVersion(out.output());
        return new CheckResult(name(), Category.BINARY, Status.OK, version,
                "Tesseract " + (version != null ? version : "已安装"));
    }

    private static String parseTesseractVersion(String output) {
        if (output == null) return null;
        // "tesseract v5.3.3" or "tesseract 5.3.3" -> "5.3.3"
        String[] lines = output.split("\n");
        if (lines.length == 0) return null;
        String[] parts = lines[0].replace("v", "").split("\\s+");
        if (parts.length >= 2) return parts[1];
        return null;
    }
}
