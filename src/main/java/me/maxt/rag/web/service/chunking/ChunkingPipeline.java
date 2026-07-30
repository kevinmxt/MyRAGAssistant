package me.maxt.rag.web.service.chunking;

import dev.langchain4j.data.segment.TextSegment;
import me.maxt.rag.web.service.chunking.analyzer.StructureAnalyzer;
import me.maxt.rag.web.service.chunking.classifier.SplitClassifier;
import me.maxt.rag.web.service.chunking.converter.MarkdownConverter;
import me.maxt.rag.web.service.chunking.evaluator.ChunkEvaluator;
import me.maxt.rag.web.service.chunking.splitter.AgentRefiner;
import me.maxt.rag.web.service.chunking.splitter.SemanticSplitter;
import me.maxt.rag.web.service.chunking.splitter.StructureSplitter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.List;

/**
 * 文档分块编排器。
 *
 * <p>编排完整的文档分块流程：Markdown 转换 → 结构分析 → 策略选择 → 策略执行 →
 * 可选智能体精炼 → 质量评估。每个步骤都有 try-catch 异常降级，确保不抛出异常。</p>
 */
public class ChunkingPipeline {

    private static final Logger log = LoggerFactory.getLogger(ChunkingPipeline.class);

    private final MarkdownConverter converter;
    private final StructureAnalyzer analyzer;
    private final SplitClassifier classifier;
    private final StructureSplitter structureSplitter;
    private final SemanticSplitter semanticSplitter;
    private final AgentRefiner agentRefiner;
    private final ChunkEvaluator evaluator;

    public ChunkingPipeline(MarkdownConverter converter, StructureAnalyzer analyzer,
                            SplitClassifier classifier, StructureSplitter structureSplitter,
                            SemanticSplitter semanticSplitter, AgentRefiner agentRefiner,
                            ChunkEvaluator evaluator) {
        this.converter = converter;
        this.analyzer = analyzer;
        this.classifier = classifier;
        this.structureSplitter = structureSplitter;
        this.semanticSplitter = semanticSplitter;
        this.agentRefiner = agentRefiner;
        this.evaluator = evaluator;
    }

    /**
     * 执行完整的文档分块流程。
     *
     * @param filePath 文档文件路径
     * @param fileType 文件类型（如 "TXT", "MD", "PDF" 等）
     * @return 分块后的文本段列表；失败时返回空列表或包含完整文本的片段
     */
    public List<TextSegment> execute(Path filePath, String fileType) {
        // Step 1: 转换为 Markdown
        String markdown;
        try {
            markdown = converter.convert(filePath);
        } catch (Exception e) {
            log.error("Markdown 转换失败，无法处理: {}", filePath, e);
            return List.of();
        }

        // Step 2: 结构分析
        DocStructure structure;
        try {
            structure = analyzer.analyze(markdown);
        } catch (Exception e) {
            log.warn("结构分析失败，使用空结构降级: {}", filePath, e);
            structure = new DocStructure(List.of(), List.of(), List.of(), List.of(), markdown.length());
        }

        // Step 3: 策略选择
        SplitPlan plan;
        try {
            plan = classifier.classify(structure, fileType, filePath.getFileName().toString());
        } catch (Exception e) {
            log.warn("策略分类失败，使用降级方案: {}", filePath, e);
            plan = SplitPlan.fallback(500);
        }

        // Step 4: 执行切分
        List<TextSegment> segments;
        try {
            segments = executeStrategies(markdown, structure, plan);
        } catch (Exception e) {
            log.error("切分执行失败: {}", filePath, e);
            return List.of(TextSegment.from(markdown));
        }

        // Step 5: 可选智能体精炼
        if (plan.needsAgentRefinement() && agentRefiner != null && segments.size() > 1) {
            try {
                // 获取另一版切分结果用于对比
                List<TextSegment> altSegments = structureSplitter.split(markdown, structure, plan.targetChunkSize());
                segments = agentRefiner.refine(segments, altSegments, markdown, plan.targetChunkSize());
            } catch (Exception e) {
                log.warn("AgentRefiner 失败，使用原始切分结果: {}", e.getMessage());
            }
        }

        // Step 6: 质量评估
        try {
            double score = evaluator.evaluate(segments, structure);
            log.debug("切分质量评分: {:.2f} (文件: {}, 分段数: {})", score, filePath.getFileName(), segments.size());
        } catch (Exception e) {
            log.debug("质量评估跳过: {}", e.getMessage());
        }

        return segments;
    }

    /**
     * 根据 {@link SplitPlan} 中的策略列表依次执行切分。
     * 每个策略独立 try-catch，失败时自动切换到下一个策略。
     * 若所有策略均失败，降级为将全文作为单个片段返回。
     *
     * @param markdown  Markdown 原文
     * @param structure 文档结构信息
     * @param plan      切分计划
     * @return 切分后的文本段列表
     */
    List<TextSegment> executeStrategies(String markdown, DocStructure structure, SplitPlan plan) {
        List<TextSegment> result = List.of();

        for (StrategyEntry entry : plan.strategies()) {
            try {
                switch (entry.strategyName()) {
                    case "structure":
                        result = structureSplitter.split(markdown, structure, plan.targetChunkSize());
                        break;
                    case "semantic":
                        if (semanticSplitter != null) {
                            result = semanticSplitter.split(markdown, structure, plan.targetChunkSize());
                        }
                        break;
                    default:
                        log.warn("未知策略: {}, 跳过", entry.strategyName());
                }
            } catch (Exception e) {
                log.warn("策略 {} 执行失败: {}", entry.strategyName(), e.getMessage());
            }
        }

        if (result.isEmpty()) {
            log.info("所有策略均失败，降级到原始文本");
            result = List.of(TextSegment.from(markdown));
        }

        return result;
    }
}
