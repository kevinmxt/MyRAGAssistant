package me.maxt.rag.web.service.environment;

import me.maxt.rag.web.config.EnvCheckConfig;
import me.maxt.rag.web.service.environment.CheckResult.Category;
import me.maxt.rag.web.service.environment.CheckResult.Status;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * 模型文件检测（精排 ONNX 模型 + LightRAG 嵌入模型）。
 * 不重复自动安装——精排模型已有 CrossEncoderReranker 后台线程处理。
 */
public class ModelFileChecker implements DependencyChecker {

    private final String rerankModelPath;
    private final String lightRagEmbeddingModelPath;

    public ModelFileChecker(EnvCheckConfig config, String rerankModelPath, String lightRagEmbeddingModelPath) {
        this.rerankModelPath = rerankModelPath;
        this.lightRagEmbeddingModelPath = lightRagEmbeddingModelPath;
    }

    @Override
    public String name() { return "model-files"; }

    @Override
    public CheckResult check() {
        List<String> missing = new ArrayList<>();
        List<String> present = new ArrayList<>();

        // 精排模型
        File rerankDir = new File(rerankModelPath);
        File onnxFile = new File(rerankDir, "model.onnx");
        if (onnxFile.exists()) {
            present.add("reranker (bge-reranker-v2-m3)");
        } else {
            missing.add("精排模型 (bge-reranker-v2-m3) → 应用已启动后台自动下载");
        }

        // LightRAG 嵌入模型
        File embeddingDir = new File(lightRagEmbeddingModelPath);
        if (embeddingDir.exists() && embeddingDir.isDirectory()) {
            present.add("embedding (" + embeddingDir.getName() + ")");
        } else {
            missing.add("LightRAG 嵌入模型 → 需手动放置或通过 multiRecall.lightrag.embeddingModelPath 配置");
        }

        if (missing.isEmpty()) {
            return new CheckResult(name(), Category.MODEL, Status.OK, null,
                    String.join(", ", present));
        }
        return new CheckResult(name(), Category.MODEL, Status.MISSING, null,
                "缺失: " + String.join("; ", missing));
    }
}
