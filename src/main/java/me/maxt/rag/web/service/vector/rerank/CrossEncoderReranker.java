package me.maxt.rag.web.service.vector.rerank;

import ai.djl.huggingface.tokenizers.Encoding;
import ai.djl.huggingface.tokenizers.HuggingFaceTokenizer;
import ai.onnxruntime.*;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import me.maxt.rag.web.config.RerankConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.nio.file.Path;
import java.util.*;

public class CrossEncoderReranker implements Reranker {

    private static final Logger log = LoggerFactory.getLogger(CrossEncoderReranker.class);
    private static final int MAX_SEQ_LENGTH = 512;

    private final RerankConfig config;
    private final String modelPath;
    private final File modelDir;
    private final File onnxFile;

    private volatile OrtEnvironment env;
    private volatile OrtSession session;
    private volatile HuggingFaceTokenizer tokenizer;
    private volatile boolean available;

    public CrossEncoderReranker(RerankConfig config) {
        this.config = config;
        this.modelPath = config.getRerankModelPath();
        this.modelDir = new File(modelPath);
        this.onnxFile = new File(modelDir, "model.onnx");

        if (onnxFile.exists()) {
            loadModel(modelDir, onnxFile, modelPath, config);
        } else {
            log.warn("精排模型未找到 ({}), 重排序已降级跳过", onnxFile.getAbsolutePath());
        }
    }

    /**
     * 幂等加载入口：模型已可用直接返回 true；模型文件已就位则加载并置可用；
     * 文件仍缺失时安静返回 false（不下载、不抛异常）。供组装根在模型就绪后触发。
     */
    public synchronized boolean loadIfPresent() {
        if (available) {
            return true;
        }
        if (!onnxFile.exists()) {
            return false;
        }
        loadModel(modelDir, onnxFile, modelPath, config);
        return available;
    }

    private void loadModel(File modelDir, File onnxFile, String modelPath, RerankConfig config) {
        File tokenizerFile = new File(modelDir, "tokenizer.json");
        try {
            this.env = OrtEnvironment.getEnvironment();
            var sessionOptions = new OrtSession.SessionOptions();
            this.session = env.createSession(onnxFile.getAbsolutePath(), sessionOptions);
            this.tokenizer = tokenizerFile.exists()
                    ? HuggingFaceTokenizer.newInstance(tokenizerFile.toPath())
                    : HuggingFaceTokenizer.newInstance(Path.of(modelPath));
            this.available = true;
            log.info("精排模型已加载: {} (候选扩倍数={}, 精排TopK={})",
                    onnxFile.getAbsolutePath(), config.getRerankExpansionFactor(), config.getRerankTopK());
        } catch (Exception e) {
            log.error("加载精排模型失败: {}", e.getMessage());
        }
    }

    @Override
    public String name() {
        return "cross-encoder";
    }

    @Override
    public boolean isAvailable() {
        return available;
    }

    @Override
    public List<EmbeddingMatch<TextSegment>> rerank(String query, List<EmbeddingMatch<TextSegment>> candidates, int topK) {
        if (candidates.isEmpty()) {
            return List.of();
        }
        // 读取 volatile 快照，避免并发修改 NPE
        OrtSession s = this.session;
        HuggingFaceTokenizer t = this.tokenizer;
        OrtEnvironment e = this.env;
        if (!available || s == null || t == null || e == null) {
            return candidates.stream().limit(topK).toList();
        }

        int n = candidates.size();
        long[][] inputIds = new long[n][MAX_SEQ_LENGTH];
        long[][] attentionMask = new long[n][MAX_SEQ_LENGTH];
        long[][] tokenTypeIds = new long[n][MAX_SEQ_LENGTH];

        for (int i = 0; i < n; i++) {
            String passage = candidates.get(i).embedded().text();
            Encoding encoding = t.encode(query, passage);
            long[] ids = encoding.getIds();
            long[] attention = encoding.getAttentionMask();
            long[] typeIds = encoding.getTypeIds();

            int len = Math.min(ids.length, MAX_SEQ_LENGTH);
            for (int j = 0; j < len; j++) {
                inputIds[i][j] = ids[j];
                attentionMask[i][j] = attention[j];
                tokenTypeIds[i][j] = typeIds[j];
            }
        }

        try (var inputIdsTensor = OnnxTensor.createTensor(e, inputIds);
             var attentionMaskTensor = OnnxTensor.createTensor(e, attentionMask);
             var tokenTypeIdsTensor = OnnxTensor.createTensor(e, tokenTypeIds);
             var results = s.run(Map.of(
                     "input_ids", inputIdsTensor,
                     "attention_mask", attentionMaskTensor,
                     "token_type_ids", tokenTypeIdsTensor))) {
            var logits = (float[][]) results.get(0).getValue();

            List<ScoredMatch> scored = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                float sigmoidScore = 1.0f / (1.0f + (float) Math.exp(-logits[i][0]));
                scored.add(new ScoredMatch(sigmoidScore, candidates.get(i)));
            }

            scored.sort((a, b) -> Float.compare(b.score, a.score));

            return scored.stream()
                    .limit(topK)
                    .map(sm -> sm.match)
                    .toList();
        } catch (OrtException ex) {
            log.error("精排推理失败: {}", ex.getMessage());
            return candidates.stream().limit(topK).toList();
        }
    }

    private record ScoredMatch(float score, EmbeddingMatch<TextSegment> match) {}
}
