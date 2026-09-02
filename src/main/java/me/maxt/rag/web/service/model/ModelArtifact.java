package me.maxt.rag.web.service.model;

import java.nio.file.Path;
import java.util.Map;

/**
 * 模型制品清单：仓库 + 文件集 + 本地目标目录。
 *
 * @param key       制品键，状态记账与同 key 互斥的标识（如 "bge-reranker"）
 * @param repo      模型仓库名，可含斜杠（如 "onnx-community/bge-reranker-v2-m3-ONNX"）
 * @param files     本地文件名 → 仓库内路径（如 "model.onnx" → "onnx/model.onnx"）
 * @param targetDir 本地目标目录，全部文件落于其下
 */
public record ModelArtifact(String key, String repo, Map<String, String> files, Path targetDir) {

    public ModelArtifact {
        files = Map.copyOf(files);
    }
}
