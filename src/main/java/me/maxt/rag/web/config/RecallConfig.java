package me.maxt.rag.web.config;

import java.util.List;

/** 多路召回配置 */
public interface RecallConfig {
    boolean isMultiRecallEnabled();
    /** 启用的召回模式列表，如 ["dense", "sparse", "graph"] */
    List<String> getRecallModes();
    /** 最终返回给 LLM 的结果数 */
    int getRecallTopK();
    /** RRF 融合参数 k */
    int getRecallRrfK();

    // ===== LightRAG =====
    String getLightRagPythonPath();
    String getLightRagWorkingDir();
    String getLightRagEmbeddingModelPath();
    String getLightRagQueryMode();

    /** Milvus collection 名称，KnowledgeGraphService 构建图谱时按 file_name 回查文档文本 */
    String getMilvusCollectionName();
}
