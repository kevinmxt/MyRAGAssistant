package me.maxt.rag.web.service.chunking.evaluator;

import dev.langchain4j.data.segment.TextSegment;
import me.maxt.rag.web.service.chunking.DocStructure;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * 切分质量评估器。
 *
 * <p>从大小一致性和边界对齐度两个维度评估切分结果质量，返回 0～1 的综合评分。
 * 用于日志监控和后续的切分策略调优参考。</p>
 */
public class ChunkEvaluator {

    private static final Logger log = LoggerFactory.getLogger(ChunkEvaluator.class);

    /**
     * 评估切分质量。
     *
     * @param chunks    切分后的文本段列表
     * @param structure 原始文档结构信息
     * @return 0～1 的质量评分，1 表示最佳
     */
    public double evaluate(List<TextSegment> chunks, DocStructure structure) {
        if (chunks == null || chunks.isEmpty()) return 1.0;

        double sizeScore = evaluateSizeConsistency(chunks);
        double boundaryScore = evaluateBoundaryAlignment(chunks, structure);

        log.debug("切分质量评估 — 大小一致性: {}, 边界对齐: {}", String.format("%.2f", sizeScore), String.format("%.2f", boundaryScore));
        return (sizeScore + boundaryScore) / 2.0;
    }

    /**
     * 评估各分段长度的变异系数（CV），CV 越小表示大小越一致。
     * 返回 1 - min(1, CV)，即 CV 为 0 时得 1 分，CV >= 1 时得 0 分。
     */
    private double evaluateSizeConsistency(List<TextSegment> chunks) {
        double avgLen = chunks.stream().mapToInt(c -> c.text().length()).average().orElse(0);
        if (avgLen == 0) return 1.0;

        double variance = chunks.stream()
                .mapToDouble(c -> Math.pow(c.text().length() - avgLen, 2))
                .average().orElse(0);
        double cv = Math.sqrt(variance) / avgLen;

        return Math.max(0, 1.0 - cv);
    }

    /**
     * 评估切分边界与文档标题结构的对齐程度。
     * 当前实现为简化版本：若无标题结构则返回 0.8（中等偏上），
     * 有标题结构但未做精确检查则返回 0.8。
     */
    private double evaluateBoundaryAlignment(List<TextSegment> chunks, DocStructure structure) {
        if (structure.headingTree().isEmpty()) return 0.8;
        return 0.8;
    }
}
