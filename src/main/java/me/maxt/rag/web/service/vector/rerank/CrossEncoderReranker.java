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
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.*;

public class CrossEncoderReranker implements Reranker {

    private static final Logger log = LoggerFactory.getLogger(CrossEncoderReranker.class);
    private static final int MAX_SEQ_LENGTH = 512;

    private volatile OrtEnvironment env;
    private volatile OrtSession session;
    private volatile HuggingFaceTokenizer tokenizer;
    private volatile boolean available;

    public CrossEncoderReranker(RerankConfig config) {
        String modelPath = config.getRerankModelPath();
        File modelDir = new File(modelPath);
        File onnxFile = new File(modelDir, "model.onnx");

        if (onnxFile.exists()) {
            loadModel(modelDir, onnxFile, modelPath, config);
        } else if (config.isRerankAutoDownload()) {
            log.info("精排模型未找到，启动后台下载 ({}), 应用正常启动，下载完成后自动启用精排", modelPath);
            Thread downloadThread = new Thread(() -> {
                downloadModel(modelDir, config);
                if (onnxFile.exists()) {
                    loadModel(modelDir, onnxFile, modelPath, config);
                } else {
                    log.warn("精排模型下载失败，重排序不可用");
                }
            }, "rerank-model-download");
            downloadThread.setDaemon(true);
            downloadThread.start();
        } else {
            log.warn("精排模型未找到 ({}), 重排序已降级跳过", onnxFile.getAbsolutePath());
        }
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

    private static void downloadModel(File modelDir, RerankConfig config) {
        String mirror = config.getRerankDownloadMirror();
        if (!mirror.endsWith("/")) mirror += "/";
        String repo = "BAAI/bge-reranker-v2-m3/resolve/main/onnx/";
        String[] files = {"model.onnx", "tokenizer.json"};

        modelDir.mkdirs();

        for (String file : files) {
            File dest = new File(modelDir, file);
            String[] urls = {
                    mirror + repo + file,
                    "https://huggingface.co/" + repo + file
            };
            boolean downloaded = false;
            for (String url : urls) {
                try {
                    HttpURLConnection conn = (HttpURLConnection) URI.create(url).toURL().openConnection();
                    conn.setConnectTimeout(10000);
                    conn.setReadTimeout(300000);
                    conn.setRequestProperty("User-Agent", "MyAIDemo2/1.0");
                    if (conn.getResponseCode() == 302) {
                        String redirect = conn.getHeaderField("Location");
                        conn.disconnect();
                        conn = (HttpURLConnection) URI.create(redirect).toURL().openConnection();
                        conn.setConnectTimeout(10000);
                        conn.setReadTimeout(300000);
                        conn.setRequestProperty("User-Agent", "MyAIDemo2/1.0");
                    }
                    long total = conn.getContentLengthLong();
                    log.info("正在下载精排模型文件: {} ({} MB), 请耐心等待...",
                            file, total > 0 ? String.format("%.1f", total / 1048576.0) : "未知大小");
                    try (InputStream in = conn.getInputStream()) {
                        Files.copy(in, dest.toPath(), StandardCopyOption.REPLACE_EXISTING);
                    }
                    log.info("精排模型文件下载完成: {}", file);
                    downloaded = true;
                    break;
                } catch (IOException e) {
                    log.debug("从 {} 下载失败: {}", url, e.getMessage());
                }
            }
            if (!downloaded) {
                log.warn("精排模型文件 {} 下载失败，已尝试镜像和 HuggingFace", file);
            }
        }
    }

    private record ScoredMatch(float score, EmbeddingMatch<TextSegment> match) {}
}
