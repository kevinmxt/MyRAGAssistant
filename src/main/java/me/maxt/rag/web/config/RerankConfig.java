package me.maxt.rag.web.config;

public interface RerankConfig {
    /** ONNX 精排模型文件路径，默认 models/bge-reranker-v2-m3 */
    String getRerankModelPath();

    /** 粗召回扩展倍数，recallTopK × 此值 = 进入精排的候选数量，默认 3 */
    int getRerankExpansionFactor();

    /** 精排后返回给 LLM 的结果数，默认 5 */
    int getRerankTopK();
}
