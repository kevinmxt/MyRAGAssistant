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

    private final OrtEnvironment env;
    private final OrtSession session;
    private final HuggingFaceTokenizer tokenizer;
    private final boolean available;

    public CrossEncoderReranker(RerankConfig config) {
        String modelPath = config.getRerankModelPath();
        File modelDir = new File(modelPath);
        File onnxFile = new File(modelDir, "model.onnx");
        File tokenizerFile = new File(modelDir, "tokenizer.json");

        if (!onnxFile.exists()) {
            log.warn("精排模型未找到 ({}), 重排序已降级跳过", onnxFile.getAbsolutePath());
            this.env = null;
            this.session = null;
            this.tokenizer = null;
            this.available = false;
            return;
        }

        // 用局部变量承载加载结果，避免 final 字段在 try/catch 双路径上重复赋值
        OrtEnvironment loadedEnv = null;
        OrtSession loadedSession = null;
        HuggingFaceTokenizer loadedTokenizer = null;
        boolean loaded = false;
        try {
            loadedEnv = OrtEnvironment.getEnvironment();
            var sessionOptions = new OrtSession.SessionOptions();
            loadedSession = loadedEnv.createSession(onnxFile.getAbsolutePath(), sessionOptions);
            loadedTokenizer = tokenizerFile.exists()
                    ? HuggingFaceTokenizer.newInstance(tokenizerFile.toPath())
                    : HuggingFaceTokenizer.newInstance(Path.of(modelPath));
            loaded = true;
            log.info("精排模型已加载: {} ({} 候选扩倍数, topK={})",
                    onnxFile.getAbsolutePath(), config.getRerankExpansionFactor(), config.getRerankTopK());
        } catch (Exception e) {
            // 任何加载失败（模型损坏、tokenizer 异常等）都降级，不抛出异常中断应用启动
            log.error("加载精排模型失败: {}", e.getMessage());
        }
        this.env = loadedEnv;
        this.session = loadedSession;
        this.tokenizer = loadedTokenizer;
        this.available = loaded;
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
        if (!available) {
            return candidates.stream().limit(topK).toList();
        }

        int n = candidates.size();
        float[][] inputIds = new float[n][MAX_SEQ_LENGTH];
        float[][] attentionMask = new float[n][MAX_SEQ_LENGTH];
        float[][] tokenTypeIds = new float[n][MAX_SEQ_LENGTH];

        for (int i = 0; i < n; i++) {
            String passage = candidates.get(i).embedded().text();
            Encoding encoding = tokenizer.encode(query, passage);
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

        try {
            var inputIdsTensor = OnnxTensor.createTensor(env, inputIds);
            var attentionMaskTensor = OnnxTensor.createTensor(env, attentionMask);
            var tokenTypeIdsTensor = OnnxTensor.createTensor(env, tokenTypeIds);

            var inputs = Map.<String, OnnxTensor>of(
                    "input_ids", inputIdsTensor,
                    "attention_mask", attentionMaskTensor,
                    "token_type_ids", tokenTypeIdsTensor
            );

            var results = session.run(inputs);
            var logits = (float[][]) results.get(0).getValue();

            // sigmoid + 按分数降序取 topK
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
        } catch (OrtException e) {
            log.error("精排推理失败: {}", e.getMessage());
            return candidates.stream().limit(topK).toList();
        }
    }

    private record ScoredMatch(float score, EmbeddingMatch<TextSegment> match) {}
}
