package me.maxt.rag.web.service.chunking;

import dev.langchain4j.data.segment.TextSegment;
import me.maxt.rag.web.service.chunking.analyzer.StructureAnalyzer;
import me.maxt.rag.web.service.chunking.classifier.SplitClassifier;
import me.maxt.rag.web.service.chunking.converter.MarkdownConverter;
import me.maxt.rag.web.service.chunking.evaluator.ChunkEvaluator;
import me.maxt.rag.web.service.chunking.splitter.StructureSplitter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ChunkingPipelineTest {

    @TempDir
    Path tempDir;

    @Test
    void shouldExecuteFullPipelineForTxtFile() throws Exception {
        Path txtFile = tempDir.resolve("test.txt");
        Files.writeString(txtFile, "# 标题\n\n## 第一节\n这是第一节的内容。\n\n## 第二节\n这是第二节的内容。");

        // 创建不依赖外部服务的 pipeline（无 Pandoc，使用 Tika）
        ChunkingPipeline pipeline = new ChunkingPipeline(
                new MarkdownConverter(),
                new StructureAnalyzer(),
                new SplitClassifier(),
                new StructureSplitter(),
                null,  // semanticSplitter — 不需要
                null,  // agentRefiner — 不需要
                new ChunkEvaluator()
        );

        List<TextSegment> segments = pipeline.execute(txtFile, "TXT");

        assertThat(segments).isNotEmpty();
        // 应至少按 h2 切分为 2 段
        assertThat(segments.size()).isGreaterThanOrEqualTo(2);
    }

    @Test
    void shouldDegradeGracefullyOnMarkdownConversionFailure() {
        ChunkingPipeline pipeline = new ChunkingPipeline(
                new MarkdownConverter(),
                new StructureAnalyzer(),
                new SplitClassifier(),
                new StructureSplitter(),
                null,
                null,
                new ChunkEvaluator()
        );

        List<TextSegment> segments = pipeline.execute(
                Path.of("/nonexistent/file-12345.xyz"), "TXT");

        // 不抛异常，降级返回空列表
        assertThat(segments).isNotNull();
    }

    @Test
    void shouldExecuteInAutoMode() {
        MarkdownConverter converter = new MarkdownConverter();
        StructureAnalyzer analyzer = new StructureAnalyzer();
        SplitClassifier classifier = new SplitClassifier();

        ChunkingPipeline pipeline = new ChunkingPipeline(
                converter, analyzer, classifier,
                new StructureSplitter(), null, null, new ChunkEvaluator());

        // auto 模式 — 即使 semantic 为 null 也应该降级到 structure
        String md = "## Section 1\nContent 1\n\n## Section 2\nContent 2";
        DocStructure structure = analyzer.analyze(md);
        SplitPlan plan = classifier.classify(structure, "MD", "test.md");

        // 无 semantic，策略链应降级
        assertThat(pipeline.executeStrategies(md, structure, plan)).isNotEmpty();
    }
}
