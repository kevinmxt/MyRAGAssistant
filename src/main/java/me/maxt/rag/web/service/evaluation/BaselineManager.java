package me.maxt.rag.web.service.evaluation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import me.maxt.rag.web.config.EvaluationConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * 基线管理器：加载/保存基线、对比当前分数与基线、输出报告。
 */
public class BaselineManager {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .enable(SerializationFeature.INDENT_OUTPUT);
    private static final Logger log = LoggerFactory.getLogger(BaselineManager.class);
    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");

    private final double degradationThreshold;

    public BaselineManager(EvaluationConfig config) {
        this.degradationThreshold = config.getDegradationThreshold();
    }

    /**
     * 加载基线或标记为首次运行。
     * <p>src/test/resources 下有 baseline.json → 复制到 target 并返回；
     * 无 → 返回 null（首次运行，结束后需写回 src/test/resources）。
     */
    public EvaluationReport loadOrCreate(String format, Path srcResourcesDir, Path targetDir) {
        Path srcBaseline = srcResourcesDir.resolve(format).resolve("baseline.json");
        Path targetFormatDir = targetDir.resolve(format);
        Path targetBaseline = targetFormatDir.resolve("baseline.json");

        if (Files.exists(srcBaseline)) {
            try {
                Files.createDirectories(targetFormatDir);
                Files.copy(srcBaseline, targetBaseline, StandardCopyOption.REPLACE_EXISTING);
                EvaluationReport baseline = MAPPER.readValue(srcBaseline.toFile(), EvaluationReport.class);
                log.info("加载基线: {} (时间: {})", format, baseline.timestamp);
                return baseline;
            } catch (IOException e) {
                log.error("加载基线失败: {}", srcBaseline, e);
            }
        }
        log.info("{} 基线不存在，标记为首次运行", format);
        return null;
    }

    /**
     * 保存报告到指定路径。
     */
    public void save(EvaluationReport report, Path filePath) {
        try {
            Files.createDirectories(filePath.getParent());
            MAPPER.writeValue(filePath.toFile(), report);
        } catch (IOException e) {
            log.error("保存报告失败: {}", filePath, e);
        }
    }

    /**
     * 将当前分数与基线对比，返回退化列表。
     */
    public List<EvaluationReport.Degradation> compare(EvaluationReport current, EvaluationReport baseline) {
        List<EvaluationReport.Degradation> degradations = new ArrayList<>();
        compareMetrics(current.metrics, baseline.metrics, degradations);
        if (current.answerQuality != null && baseline.answerQuality != null) {
            compareMetrics(current.answerQuality, baseline.answerQuality, degradations);
        }
        return degradations;
    }

    private void compareMetrics(java.util.Map<String, EvaluationReport.MetricScore> current,
                                 java.util.Map<String, EvaluationReport.MetricScore> baseline,
                                 List<EvaluationReport.Degradation> degradations) {
        for (var entry : current.entrySet()) {
            String name = entry.getKey();
            double curScore = entry.getValue().score;
            var baseScore = baseline.get(name);
            if (baseScore == null) continue;
            // 保留 3 位小数，避免浮点精度误差（如 0.70 - 0.80 = -0.10000000000000009）
            double delta = Math.round((curScore - baseScore.score) * 1000.0) / 1000.0;
            if (delta < -degradationThreshold) {
                var d = new EvaluationReport.Degradation();
                d.metric = name;
                d.baseline = baseScore.score;
                d.current = curScore;
                d.delta = delta;
                degradations.add(d);
                log.warn("指标退化: {} {} → {} (Δ={})", name, baseScore.score, curScore, String.format("%.3f", delta));
            }
        }
    }

    /**
     * 生成时间戳字符串。
     */
    public static String timestamp() {
        return LocalDateTime.now().format(FMT);
    }

    /**
     * 判定整体状态。
     */
    public static String overallStatus(List<EvaluationReport.Degradation> degradations) {
        if (degradations.isEmpty()) return "pass";
        boolean hasSignificant = degradations.stream().anyMatch(d -> d.delta < -0.05);
        return hasSignificant ? "degraded" : "warning";
    }
}
