package me.maxt.rag.web.service.chunking.splitter;

import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import me.maxt.rag.web.service.chunking.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

public class SemanticSplitter implements SplitStrategy {

    private static final Logger log = LoggerFactory.getLogger(SemanticSplitter.class);
    private final EmbeddingModel embeddingModel;
    private final double threshold;

    public SemanticSplitter(EmbeddingModel embeddingModel, double threshold) {
        this.embeddingModel = embeddingModel;
        this.threshold = threshold;
    }

    @Override
    public String name() {
        return "semantic";
    }

    @Override
    public List<TextSegment> split(String markdownContent, DocStructure structure, int targetChunkSize) {
        List<Range> paragraphs = structure.paragraphRanges();
        if (paragraphs.isEmpty()) {
            return List.of();
        }

        // 提取段落文本
        List<String> paraTexts = new ArrayList<>();
        for (Range r : paragraphs) {
            String text = markdownContent.substring(r.start(), r.end()).trim();
            if (!text.isEmpty()) {
                paraTexts.add(text);
            }
        }

        if (paraTexts.isEmpty()) return List.of();
        if (paraTexts.size() == 1) return List.of(TextSegment.from(paraTexts.get(0)));

        // 批量计算 embedding
        List<TextSegment> paraSegments = new ArrayList<>();
        for (String text : paraTexts) {
            paraSegments.add(TextSegment.from(text));
        }
        List<Embedding> embeddings = embeddingModel.embedAll(paraSegments).content();

        // 计算相邻相似度，找断点
        List<Integer> breakpoints = new ArrayList<>();
        breakpoints.add(0);

        for (int i = 0; i < embeddings.size() - 1; i++) {
            double similarity = cosineSimilarity(embeddings.get(i), embeddings.get(i + 1));
            if (similarity < threshold) {
                breakpoints.add(i + 1);
            }
        }
        breakpoints.add(paraTexts.size());

        // 按断点合并段落
        List<TextSegment> result = new ArrayList<>();
        for (int i = 0; i < breakpoints.size() - 1; i++) {
            int start = breakpoints.get(i);
            int end = breakpoints.get(i + 1);
            StringBuilder chunk = new StringBuilder();
            for (int j = start; j < end; j++) {
                chunk.append(paraTexts.get(j)).append("\n\n");
            }
            String chunkText = chunk.toString().trim();
            if (chunkText.length() > targetChunkSize) {
                // 超长，按句子再切
                result.addAll(splitBySentences(chunkText, targetChunkSize));
            } else if (!chunkText.isEmpty()) {
                result.add(TextSegment.from(chunkText));
            }
        }

        if (result.isEmpty()) {
            return List.of(TextSegment.from(markdownContent));
        }

        // 后合并：相邻过短 chunk 合并，避免语义切分产生碎片
        return mergeShortChunks(result, targetChunkSize);
    }

    private double cosineSimilarity(Embedding a, Embedding b) {
        float[] va = a.vector();
        float[] vb = b.vector();
        double dot = 0, norma = 0, normb = 0;
        for (int i = 0; i < va.length; i++) {
            dot += va[i] * vb[i];
            norma += va[i] * va[i];
            normb += vb[i] * vb[i];
        }
        if (norma == 0 || normb == 0) return 0;
        return dot / (Math.sqrt(norma) * Math.sqrt(normb));
    }

    private List<TextSegment> splitBySentences(String text, int maxSize) {
        List<TextSegment> segments = new ArrayList<>();
        String[] sentences = text.split("(?<=[。！？.!?])\\s*");
        StringBuilder current = new StringBuilder();
        for (String sentence : sentences) {
            if (current.length() + sentence.length() > maxSize && current.length() > 0) {
                segments.add(TextSegment.from(current.toString().trim()));
                current = new StringBuilder();
            }
            current.append(sentence);
        }
        if (current.length() > 0) {
            segments.add(TextSegment.from(current.toString().trim()));
        }
        return segments.isEmpty() ? List.of(TextSegment.from(text)) : segments;
    }

    /**
     * 合并相邻过短 chunk，确保每个 chunk 至少达到 minChunkSize。
     * 合并时累加后续 chunk 直到达到最小长度或耗尽。
     */
    static List<TextSegment> mergeShortChunks(List<TextSegment> chunks, int targetChunkSize) {
        int minChunkSize = Math.max(50, targetChunkSize / 10);
        List<TextSegment> merged = new ArrayList<>();
        int i = 0;
        while (i < chunks.size()) {
            StringBuilder acc = new StringBuilder(chunks.get(i).text());
            while (acc.length() < minChunkSize && i + 1 < chunks.size()) {
                i++;
                acc.append("\n\n").append(chunks.get(i).text());
            }
            merged.add(TextSegment.from(acc.toString().trim()));
            i++;
        }
        return merged;
    }
}
