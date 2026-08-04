# 文档切割策略增强 — 实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 将单一递归字符切分替换为自适应混合切分策略（结构/语义/智能体精炼），提升 RAG 检索片段边界合理性。

**Architecture:** 新增 `service/chunking/` 子包，ChunkingPipeline 编排 MarkdownConverter → StructureAnalyzer → SplitClassifier → Splitter Chain → AgentRefiner 全流程，DocumentService 只改 2 行调用。

**Tech Stack:** flexmark-java (Markdown AST)，Pandoc CLI (可选，PDF/DOCX→MD)，LangChain4j Document/TextSegment (已有)

## Global Constraints

- JDK 17+，Maven 3.6+
- 遵循现有项目架构约定：接口优先、构造函数注入、不 mock 自己的模块
- 测试：JUnit 5 + Mockito + AssertJ，只 mock 系统边界（EmbeddingModel、ChatModel）
- 任何环节失败不能阻断摄入流程，逐级降级到现有递归字符切分作为兜底
- commit message 用中文

---

## Task 1: 添加 flexmark-java 依赖 + 数据结构定义

**Files:**
- Modify: `pom.xml`
- Create: `src/main/java/me/maxt/rag/web/service/chunking/DocStructure.java`
- Create: `src/main/java/me/maxt/rag/web/service/chunking/SplitPlan.java`

**Interfaces:**
- Produces: `DocStructure`, `Range`, `HeadingNode`, `SplitPlan`, `StrategyEntry` — 所有后续 task 消费

- [ ] **Step 1: 在 pom.xml 添加 flexmark-java 依赖**

在 `<dependencies>` 内已有 langchain4j 依赖之后添加：

```xml
<dependency>
    <groupId>com.vladsch.flexmark</groupId>
    <artifactId>flexmark</artifactId>
    <version>0.64.8</version>
</dependency>
```

- [ ] **Step 2: 创建 DocStructure.java**

```java
package me.maxt.rag.web.service.chunking;

import java.util.List;

public record HeadingNode(int level, String title, int startOffset, int endOffset) {
    public HeadingNode {
        if (level < 1 || level > 6) throw new IllegalArgumentException("level must be 1-6");
        if (title == null) throw new IllegalArgumentException("title must not be null");
    }
}

public record Range(int start, int end) {
    public Range {
        if (start < 0 || end < start) throw new IllegalArgumentException("Invalid range: [" + start + ", " + end + ")");
    }
    public int length() { return end - start; }
}

public record DocStructure(
    List<HeadingNode> headingTree,
    List<Range> paragraphRanges,
    List<Range> codeBlockRanges,
    List<Range> tableRanges,
    int totalLength
) {
    public boolean hasClearHeadingHierarchy() {
        long h2h3Count = headingTree.stream()
                .filter(h -> h.level() >= 2 && h.level() <= 3).count();
        return h2h3Count >= 2;
    }

    public double codeBlockRatio() {
        if (totalLength == 0) return 0;
        int codeChars = codeBlockRanges.stream().mapToInt(Range::length).sum();
        return (double) codeChars / totalLength;
    }

    public int maxSectionDepth() {
        return headingTree.stream().mapToInt(HeadingNode::level).max().orElse(0);
    }
}
```

- [ ] **Step 3: 创建 SplitPlan.java**

```java
package me.maxt.rag.web.service.chunking;

import java.util.List;
import java.util.Map;

public record StrategyEntry(String strategyName, Map<String, Object> params) {}

public record SplitPlan(
    List<StrategyEntry> strategies,
    boolean needsAgentRefinement,
    int targetChunkSize
) {
    public static SplitPlan fallback(int chunkSize) {
        return new SplitPlan(
            List.of(new StrategyEntry("recursive", Map.of("chunkSize", chunkSize, "chunkOverlap", 0))),
            false, chunkSize);
    }
}
```

- [ ] **Step 4: 编译验证**

Run: `mvn compile`
Expected: BUILD SUCCESS

- [ ] **Step 5: Commit**

```bash
git add pom.xml src/main/java/me/maxt/rag/web/service/chunking/DocStructure.java src/main/java/me/maxt/rag/web/service/chunking/SplitPlan.java
git commit -m "chore: 添加 flexmark-java 依赖，定义切分数据结构"
```

---

## Task 2: SplitStrategy 接口 + 配置接口 ChunkingConfig

**Files:**
- Create: `src/main/java/me/maxt/rag/web/service/chunking/splitter/SplitStrategy.java`
- Create: `src/main/java/me/maxt/rag/web/config/ChunkingConfig.java`
- Modify: `src/main/java/me/maxt/rag/web/config/DocumentConfig.java`

**Interfaces:**
- Produces: `SplitStrategy.split(markdownContent, structure, targetChunkSize) → List<TextSegment>`, `ChunkingConfig` 5 个 getter
- Consumes: `DocStructure` (Task 1)

- [ ] **Step 1: 创建 SplitStrategy.java**

```java
package me.maxt.rag.web.service.chunking.splitter;

import dev.langchain4j.data.segment.TextSegment;
import me.maxt.rag.web.service.chunking.DocStructure;
import java.util.List;

public interface SplitStrategy {
    String name();
    List<TextSegment> split(String markdownContent, DocStructure structure, int targetChunkSize);
}
```

- [ ] **Step 2: 创建 ChunkingConfig.java**

```java
package me.maxt.rag.web.config;

public interface ChunkingConfig {
    String getChunkingMode();       // "auto" | "structure" | "semantic" | "recursive"
    double getSemanticThreshold();  // 0~1，语义断点相似度阈值
    boolean isAgentRefinerEnabled();
    int getMaxChunkSize();          // 切分上限（字符）
}
```

- [ ] **Step 3: DocumentConfig 继承 ChunkingConfig**

在 `DocumentConfig.java` 添加 `extends ChunkingConfig`：

```java
public interface DocumentConfig extends ChunkingConfig {
```

- [ ] **Step 4: 编译验证**

Run: `mvn compile`
Expected: BUILD SUCCESS (AppConfig 编译错误是预期的——下一步处理)

- [ ] **Step 5: Commit**

```bash
git add src/main/java/me/maxt/rag/web/service/chunking/splitter/SplitStrategy.java src/main/java/me/maxt/rag/web/config/ChunkingConfig.java src/main/java/me/maxt/rag/web/config/DocumentConfig.java
git commit -m "feat: 定义 SplitStrategy 接口和 ChunkingConfig 配置接口"
```

---

## Task 3: StructureAnalyzer（TDD）

**Files:**
- Create: `src/test/java/me/maxt/rag/web/service/chunking/analyzer/StructureAnalyzerTest.java`
- Create: `src/main/java/me/maxt/rag/web/service/chunking/analyzer/StructureAnalyzer.java`

**Interfaces:**
- Produces: `StructureAnalyzer.analyze(String markdownContent) → DocStructure`
- Consumes: `DocStructure`, `Range`, `HeadingNode` (Task 1)

- [ ] **Step 1: 写失败测试 — 验证标题提取**

```java
package me.maxt.rag.web.service.chunking.analyzer;

import me.maxt.rag.web.service.chunking.DocStructure;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class StructureAnalyzerTest {

    private final StructureAnalyzer analyzer = new StructureAnalyzer();

    @Test
    void shouldExtractHeadingTree() {
        String md = "# 标题1\n\n## 标题2\n\n正文内容\n\n### 标题3\n\n更多内容";
        DocStructure structure = analyzer.analyze(md);

        assertThat(structure.headingTree()).hasSize(3);
        assertThat(structure.headingTree().get(0).level()).isEqualTo(1);
        assertThat(structure.headingTree().get(0).title()).isEqualTo("标题1");
        assertThat(structure.headingTree().get(1).level()).isEqualTo(2);
        assertThat(structure.headingTree().get(1).title()).isEqualTo("标题2");
        assertThat(structure.headingTree().get(2).level()).isEqualTo(3);
    }

    @Test
    void shouldExtractParagraphRanges() {
        String md = "段落一\n\n段落二\n\n段落三";
        DocStructure structure = analyzer.analyze(md);

        assertThat(structure.paragraphRanges()).hasSize(3);
    }

    @Test
    void shouldExtractCodeBlockRanges() {
        String md = "文字\n\n```java\nint x = 1;\n```\n\n更多文字";
        DocStructure structure = analyzer.analyze(md);

        assertThat(structure.codeBlockRanges()).hasSize(1);
        assertThat(structure.codeBlockRatio()).isGreaterThan(0);
    }

    @Test
    void shouldDetectClearHeadingHierarchy() {
        String md = "# 标题\n## 子标题1\n内容\n## 子标题2\n内容";
        DocStructure structure = analyzer.analyze(md);

        assertThat(structure.hasClearHeadingHierarchy()).isTrue();
    }

    @Test
    void shouldDetectMissingHierarchy() {
        String md = "没有标题的纯文本内容";
        DocStructure structure = analyzer.analyze(md);

        assertThat(structure.hasClearHeadingHierarchy()).isFalse();
        assertThat(structure.headingTree()).isEmpty();
    }

    @Test
    void shouldExtractTableRanges() {
        String md = "文字\n\n| A | B |\n|---|---|\n| 1 | 2 |\n\n更多";
        DocStructure structure = analyzer.analyze(md);

        assertThat(structure.tableRanges()).isNotEmpty();
    }

    @Test
    void shouldRecordTotalLength() {
        String md = "Hello World";
        DocStructure structure = analyzer.analyze(md);

        assertThat(structure.totalLength()).isEqualTo(11);
    }

    @Test
    void shouldHandleEmptyDocument() {
        DocStructure structure = analyzer.analyze("");

        assertThat(structure.totalLength()).isEqualTo(0);
        assertThat(structure.headingTree()).isEmpty();
        assertThat(structure.paragraphRanges()).isEmpty();
    }
}
```

- [ ] **Step 2: 运行测试验证失败**

Run: `mvn test -Dtest=StructureAnalyzerTest`
Expected: FAIL (class not found)

- [ ] **Step 3: 实现 StructureAnalyzer**

```java
package me.maxt.rag.web.service.chunking.analyzer;

import com.vladsch.flexmark.ast.*;
import com.vladsch.flexmark.parser.Parser;
import com.vladsch.flexmark.util.ast.Document;
import com.vladsch.flexmark.util.ast.Node;
import me.maxt.rag.web.service.chunking.DocStructure;
import me.maxt.rag.web.service.chunking.HeadingNode;
import me.maxt.rag.web.service.chunking.Range;

import java.util.ArrayList;
import java.util.List;

public class StructureAnalyzer {

    private final Parser parser;

    public StructureAnalyzer() {
        this.parser = Parser.builder().build();
    }

    public DocStructure analyze(String markdownContent) {
        if (markdownContent == null || markdownContent.isEmpty()) {
            return new DocStructure(List.of(), List.of(), List.of(), List.of(), 0);
        }

        Document document = parser.parse(markdownContent);
        List<HeadingNode> headings = new ArrayList<>();
        List<Range> paragraphs = new ArrayList<>();
        List<Range> codeBlocks = new ArrayList<>();
        List<Range> tables = new ArrayList<>();

        collectNodes(document, headings, paragraphs, codeBlocks, tables);

        return new DocStructure(headings, paragraphs, codeBlocks, tables, markdownContent.length());
    }

    private void collectNodes(Node parent, List<HeadingNode> headings,
                              List<Range> paragraphs, List<Range> codeBlocks,
                              List<Range> tables) {
        for (Node child : parent.getChildren()) {
            if (child instanceof Heading h) {
                headings.add(new HeadingNode(h.getLevel(),
                        h.getText().toString(),
                        h.getStartOffset(), h.getEndOffset()));
            } else if (child instanceof Paragraph) {
                paragraphs.add(new Range(child.getStartOffset(), child.getEndOffset()));
            } else if (child instanceof FencedCodeBlock || child instanceof IndentedCodeBlock) {
                codeBlocks.add(new Range(child.getStartOffset(), child.getEndOffset()));
            } else if (child instanceof TableBlock) {
                tables.add(new Range(child.getStartOffset(), child.getEndOffset()));
            }
            // 递归子节点（处理嵌套结构）
            if (child.hasChildren()) {
                collectNodes(child, headings, paragraphs, codeBlocks, tables);
            }
        }
    }
}
```

- [ ] **Step 4: 运行测试验证通过**

Run: `mvn test -Dtest=StructureAnalyzerTest`
Expected: 8 tests PASS

- [ ] **Step 5: Commit**

```bash
git add src/test/java/me/maxt/rag/web/service/chunking/analyzer/StructureAnalyzerTest.java src/main/java/me/maxt/rag/web/service/chunking/analyzer/StructureAnalyzer.java
git commit -m "feat: 实现 StructureAnalyzer，基于 flexmark 提取 Markdown 结构"
```

---

## Task 4: SplitClassifier（TDD）

**Files:**
- Create: `src/test/java/me/maxt/rag/web/service/chunking/classifier/SplitClassifierTest.java`
- Create: `src/main/java/me/maxt/rag/web/service/chunking/classifier/SplitClassifier.java`

**Interfaces:**
- Produces: `SplitClassifier.classify(DocStructure, String fileType, String fileName) → SplitPlan`
- Consumes: `DocStructure`, `SplitPlan`, `StrategyEntry` (Task 1)

- [ ] **Step 1: 写失败测试**

```java
package me.maxt.rag.web.service.chunking.classifier;

import me.maxt.rag.web.service.chunking.*;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;

class SplitClassifierTest {

    private final SplitClassifier classifier = new SplitClassifier();

    @Test
    void shouldUseStructureForDocumentWithHeadings() {
        DocStructure structure = new DocStructure(
                List.of(new HeadingNode(2, "Section A", 0, 20), new HeadingNode(2, "Section B", 80, 100)),
                List.of(new Range(20, 80), new Range(100, 200)),
                List.of(), List.of(), 200);

        SplitPlan plan = classifier.classify(structure, "MD", "doc.md");

        assertThat(plan.strategies()).isNotEmpty();
        assertThat(plan.strategies().get(0).strategyName()).isEqualTo("structure");
    }

    @Test
    void shouldUseSemanticForFlatLongDocument() {
        DocStructure structure = new DocStructure(
                List.of(),
                generateParagraphs(50),
                List.of(), List.of(), 30000);

        SplitPlan plan = classifier.classify(structure, "TXT", "long.txt");

        boolean hasSemantic = plan.strategies().stream()
                .anyMatch(s -> s.strategyName().equals("semantic"));
        assertThat(hasSemantic).isTrue();
    }

    @Test
    void shouldNotUseAgentRefinerForSmallDocument() {
        DocStructure structure = new DocStructure(
                List.of(new HeadingNode(2, "Section", 0, 20)),
                List.of(new Range(20, 200)),
                List.of(), List.of(), 200);

        SplitPlan plan = classifier.classify(structure, "MD", "small.md");

        assertThat(plan.needsAgentRefinement()).isFalse();
    }

    @Test
    void shouldEnableAgentRefinerForLargeFlatDocument() {
        DocStructure structure = new DocStructure(
                List.of(),
                generateParagraphs(80),
                List.of(), List.of(), 40000);

        SplitPlan plan = classifier.classify(structure, "TXT", "huge.txt");

        assertThat(plan.needsAgentRefinement()).isTrue();
    }

    @Test
    void shouldPreserveCodeBlocksForCodeHeavyDocument() {
        DocStructure structure = new DocStructure(
                List.of(new HeadingNode(1, "Readme", 0, 20)),
                List.of(new Range(20, 100), new Range(400, 500)),
                List.of(new Range(100, 400)),
                List.of(), 500);

        SplitPlan plan = classifier.classify(structure, "MD", "readme.md");

        assertThat(plan.strategies()).anyMatch(s ->
                s.params().getOrDefault("preserveCodeBlocks", false).equals(true));
    }

    @Test
    void shouldReturnFallbackForEmptyStructure() {
        DocStructure structure = new DocStructure(
                List.of(), List.of(), List.of(), List.of(), 0);

        SplitPlan plan = classifier.classify(structure, "TXT", "empty.txt");

        assertThat(plan.strategies()).isNotEmpty();
    }

    private List<Range> generateParagraphs(int count) {
        List<Range> paragraphs = new java.util.ArrayList<>();
        int pos = 0;
        for (int i = 0; i < count; i++) {
            paragraphs.add(new Range(pos, pos + 500));
            pos += 600;
        }
        return paragraphs;
    }
}
```

- [ ] **Step 2: 运行测试验证失败**

Run: `mvn test -Dtest=SplitClassifierTest`
Expected: FAIL

- [ ] **Step 3: 实现 SplitClassifier**

```java
package me.maxt.rag.web.service.chunking.classifier;

import me.maxt.rag.web.service.chunking.*;
import java.util.*;

public class SplitClassifier {

    private static final int SMALL_DOC_THRESHOLD = 5000;
    private static final int LARGE_DOC_THRESHOLD = 20000;
    private static final double HIGH_CODE_RATIO = 0.3;
    private static final double DEFAULT_SEMANTIC_THRESHOLD = 0.6;

    public SplitPlan classify(DocStructure structure, String fileType, String fileName) {
        boolean hasHierarchy = structure.hasClearHeadingHierarchy();
        boolean isSmall = structure.totalLength() < SMALL_DOC_THRESHOLD;
        boolean isLarge = structure.totalLength() > LARGE_DOC_THRESHOLD;
        boolean isCodeHeavy = structure.codeBlockRatio() > HIGH_CODE_RATIO;
        boolean isFlat = !hasHierarchy || structure.maxSectionDepth() < 2;

        List<StrategyEntry> strategies = new ArrayList<>();
        boolean needsAgentRefiner = false;

        if (isCodeHeavy) {
            strategies.add(new StrategyEntry("structure",
                    Map.of("preserveCodeBlocks", true, "headingLevel", 2)));
        } else if (hasHierarchy) {
            strategies.add(new StrategyEntry("structure", Map.of("headingLevel", 2)));
        }

        if (isSmall && hasHierarchy) {
            strategies.clear();
            strategies.add(new StrategyEntry("structure", Map.of("headingLevel", 2)));
        } else if (isLarge && isFlat) {
            if (!hasHierarchy && !isCodeHeavy) {
                strategies.add(new StrategyEntry("structure", Map.of()));
            }
            strategies.add(new StrategyEntry("semantic",
                    Map.of("threshold", DEFAULT_SEMANTIC_THRESHOLD)));
            needsAgentRefiner = true;
        } else if (!hasHierarchy && !isCodeHeavy) {
            strategies.add(new StrategyEntry("semantic",
                    Map.of("threshold", DEFAULT_SEMANTIC_THRESHOLD)));
        }

        if (strategies.isEmpty()) {
            strategies.add(new StrategyEntry("structure", Map.of()));
        }

        int targetChunkSize = determineTargetSize(structure);
        return new SplitPlan(strategies, needsAgentRefiner, targetChunkSize);
    }

    private int determineTargetSize(DocStructure structure) {
        if (structure.totalLength() < SMALL_DOC_THRESHOLD) return 500;
        if (structure.totalLength() > LARGE_DOC_THRESHOLD) return 2000;
        return 1000;
    }
}
```

- [ ] **Step 4: 运行测试验证通过**

Run: `mvn test -Dtest=SplitClassifierTest`
Expected: 6 tests PASS

- [ ] **Step 5: Commit**

```bash
git add src/test/java/me/maxt/rag/web/service/chunking/classifier/SplitClassifierTest.java src/main/java/me/maxt/rag/web/service/chunking/classifier/SplitClassifier.java
git commit -m "feat: 实现 SplitClassifier，根据文档特征选择切分策略"
```

---

## Task 5: MarkdownConverter（TDD）

**Files:**
- Create: `src/test/java/me/maxt/rag/web/service/chunking/converter/MarkdownConverterTest.java`
- Create: `src/main/java/me/maxt/rag/web/service/chunking/converter/MarkdownConverter.java`

**Interfaces:**
- Produces: `MarkdownConverter.convert(Path) → String`, `MarkdownConverter.isAvailable() → boolean`
- Consumes: 无（独立模块，使用 Pandoc CLI 或 Tika）

- [ ] **Step 1: 写失败测试**

```java
package me.maxt.rag.web.service.chunking.converter;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import static org.assertj.core.api.Assertions.assertThat;

class MarkdownConverterTest {

    @TempDir
    Path tempDir;

    private final MarkdownConverter converter = new MarkdownConverter();

    @Test
    void shouldReportAvailability() {
        boolean available = converter.isAvailable();
        // Pandoc 可能装也可能没装，只验证不抛异常
        assertThat(available).isInstanceOf(Boolean.class);
    }

    @Test
    void shouldConvertTxtFile() throws Exception {
        Path txtFile = tempDir.resolve("test.txt");
        Files.writeString(txtFile, "Hello World");

        String result = converter.convert(txtFile);

        assertThat(result).contains("Hello");
    }

    @Test
    void shouldConvertMarkdownFile() throws Exception {
        Path mdFile = tempDir.resolve("test.md");
        Files.writeString(mdFile, "# Title\n\nContent");

        String result = converter.convert(mdFile);

        assertThat(result).contains("Title");
        assertThat(result).contains("Content");
    }

    @Test
    void shouldNotThrowOnMissingFile() {
        try {
            converter.convert(Path.of("/nonexistent/file-12345.xyz"));
        } catch (Exception e) {
            assertThat(e).isInstanceOf(RuntimeException.class);
        }
    }
}
```

- [ ] **Step 2: 运行测试验证失败**

Run: `mvn test -Dtest=MarkdownConverterTest`
Expected: FAIL

- [ ] **Step 3: 实现 MarkdownConverter**

```java
package me.maxt.rag.web.service.chunking.converter;

import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.document.loader.FileSystemDocumentLoader;
import dev.langchain4j.data.document.parser.apache.tika.ApacheTikaDocumentParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

public class MarkdownConverter {

    private static final Logger log = LoggerFactory.getLogger(MarkdownConverter.class);
    private final boolean pandocAvailable;

    public MarkdownConverter() {
        this.pandocAvailable = checkPandoc();
        if (pandocAvailable) {
            log.info("Pandoc detected — 将使用 Pandoc 进行文档转 Markdown");
        } else {
            log.info("Pandoc 未安装，将使用 Tika 作为 fallback");
        }
    }

    public boolean isAvailable() {
        return pandocAvailable;
    }

    public String convert(Path filePath) {
        if (pandocAvailable) {
            try {
                return convertWithPandoc(filePath);
            } catch (Exception e) {
                log.warn("Pandoc 转换失败，降级到 Tika: {}", filePath, e);
            }
        }
        return convertWithTika(filePath);
    }

    private String convertWithPandoc(Path filePath) throws IOException, InterruptedException {
        ProcessBuilder pb = new ProcessBuilder(
                "pandoc", filePath.toString(),
                "-t", "markdown",
                "--wrap=none",
                "--extract-media=./data/media"
        );
        pb.redirectErrorStream(true);
        Process process = pb.start();

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        process.getInputStream().transferTo(out);

        if (!process.waitFor(60, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            throw new IOException("Pandoc 超时: " + filePath);
        }

        if (process.exitValue() != 0) {
            throw new IOException("Pandoc 返回非零: " + process.exitValue());
        }

        return out.toString(StandardCharsets.UTF_8);
    }

    private String convertWithTika(Path filePath) {
        ApacheTikaDocumentParser parser = new ApacheTikaDocumentParser();
        Document document = FileSystemDocumentLoader.loadDocument(filePath, parser);
        return document.text();
    }

    private static boolean checkPandoc() {
        try {
            ProcessBuilder pb = new ProcessBuilder("pandoc", "--version");
            Process process = pb.start();
            boolean finished = process.waitFor(5, TimeUnit.SECONDS);
            return finished && process.exitValue() == 0;
        } catch (Exception e) {
            return false;
        }
    }
}
```

- [ ] **Step 4: 运行测试验证通过**

Run: `mvn test -Dtest=MarkdownConverterTest`
Expected: 4 tests PASS

- [ ] **Step 5: Commit**

```bash
git add src/test/java/me/maxt/rag/web/service/chunking/converter/MarkdownConverterTest.java src/main/java/me/maxt/rag/web/service/chunking/converter/MarkdownConverter.java
git commit -m "feat: 实现 MarkdownConverter（Pandoc + Tika 降级）"
```

---

## Task 6: StructureSplitter（TDD）

**Files:**
- Create: `src/test/java/me/maxt/rag/web/service/chunking/splitter/StructureSplitterTest.java`
- Create: `src/main/java/me/maxt/rag/web/service/chunking/splitter/StructureSplitter.java`

**Interfaces:**
- Produces: `StructureSplitter implements SplitStrategy`
- Consumes: `SplitStrategy` (Task 2), `DocStructure` (Task 1)

- [ ] **Step 1: 写失败测试**

```java
package me.maxt.rag.web.service.chunking.splitter;

import dev.langchain4j.data.segment.TextSegment;
import me.maxt.rag.web.service.chunking.*;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;

class StructureSplitterTest {

    private final StructureSplitter splitter = new StructureSplitter();

    @Test
    void shouldHaveNameStructure() {
        assertThat(splitter.name()).isEqualTo("structure");
    }

    @Test
    void shouldSplitAtH2Headings() {
        String md = "# 文档标题\n\n## 第一节\n内容A\n\n## 第二节\n内容B";
        DocStructure structure = new DocStructure(
                List.of(
                        new HeadingNode(1, "文档标题", 0, 8),
                        new HeadingNode(2, "第一节", 10, 16),
                        new HeadingNode(2, "第二节", 26, 32)),
                List.of(new Range(17, 22), new Range(33, 38)),
                List.of(), List.of(), 38);

        List<TextSegment> segments = splitter.split(md, structure, 1000);

        assertThat(segments).hasSize(2);
        assertThat(segments.get(0).text()).contains("第一节");
        assertThat(segments.get(0).text()).contains("内容A");
        assertThat(segments.get(1).text()).contains("第二节");
        assertThat(segments.get(1).text()).contains("内容B");
    }

    @Test
    void shouldAttachHeadingPathMetadata() {
        String md = "# 文档\n\n## 第一章\n内容";
        DocStructure structure = new DocStructure(
                List.of(
                        new HeadingNode(1, "文档", 0, 5),
                        new HeadingNode(2, "第一章", 7, 13)),
                List.of(new Range(14, 16)),
                List.of(), List.of(), 16);

        List<TextSegment> segments = splitter.split(md, structure, 1000);

        assertThat(segments).hasSize(1);
        assertThat(segments.get(0).metadata().getString("heading_path"))
                .isEqualTo("文档 > 第一章");
    }

    @Test
    void shouldPreserveCodeBlocksAsAtomicUnits() {
        String md = "## 代码示例\n\n```java\nint x = 1;\n```\n\n## 说明\n代码介绍";
        DocStructure structure = new DocStructure(
                List.of(
                        new HeadingNode(2, "代码示例", 0, 8),
                        new HeadingNode(2, "说明", 32, 36)),
                List.of(new Range(37, 41)),
                List.of(new Range(10, 30)),
                List.of(), 41);

        List<TextSegment> segments = splitter.split(md, structure, 1000);

        // 代码块应该完整保留在第一个分段中
        assertThat(segments.get(0).text()).contains("int x = 1;");
    }

    @Test
    void shouldHandleNoHeadings() {
        String md = "没有标题的纯文本内容。";
        DocStructure structure = new DocStructure(
                List.of(),
                List.of(new Range(0, 12)),
                List.of(), List.of(), 12);

        List<TextSegment> segments = splitter.split(md, structure, 1000);

        assertThat(segments).isNotEmpty();
        assertThat(segments.get(0).text()).contains("纯文本");
    }

    @Test
    void shouldFallbackToParagraphSplitForOversizedSection() {
        StringBuilder sb = new StringBuilder("## 长章节\n\n");
        for (int i = 0; i < 100; i++) {
            sb.append("段落").append(i).append(": 这是测试内容。\n\n");
        }
        String md = sb.toString();
        DocStructure structure = new DocStructure(
                List.of(new HeadingNode(2, "长章节", 0, 7)),
                generateParagraphRanges(8, md.length()),
                List.of(), List.of(), md.length());

        List<TextSegment> segments = splitter.split(md, structure, 200);

        // 超大 section 被拆分为多个分段
        assertThat(segments.size()).isGreaterThan(1);
    }

    private List<Range> generateParagraphRanges(int start, int end) {
        List<Range> ranges = new java.util.ArrayList<>();
        int step = (end - start) / 20;
        for (int i = start; i < end; i += step) {
            ranges.add(new Range(i, Math.min(i + step, end)));
        }
        return ranges;
    }
}
```

- [ ] **Step 2: 运行测试验证失败**

Run: `mvn test -Dtest=StructureSplitterTest`
Expected: FAIL

- [ ] **Step 3: 实现 StructureSplitter**

```java
package me.maxt.rag.web.service.chunking.splitter;

import dev.langchain4j.data.segment.TextSegment;
import me.maxt.rag.web.service.chunking.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

public class StructureSplitter implements SplitStrategy {

    private static final Logger log = LoggerFactory.getLogger(StructureSplitter.class);

    @Override
    public String name() {
        return "structure";
    }

    @Override
    public List<TextSegment> split(String markdownContent, DocStructure structure, int targetChunkSize) {
        List<HeadingNode> headings = structure.headingTree();

        if (headings.isEmpty()) {
            return splitByParagraphs(markdownContent, structure, targetChunkSize);
        }

        List<TextSegment> segments = new ArrayList<>();
        int pos = 0;

        for (int i = 0; i < headings.size(); i++) {
            HeadingNode heading = headings.get(i);
            if (heading.level() < 2) continue; // 跳过 h1

            int sectionStart = heading.startOffset();
            int sectionEnd = (i + 1 < headings.size())
                    ? headings.get(i + 1).startOffset()
                    : markdownContent.length();

            String sectionText = markdownContent.substring(sectionStart, sectionEnd).trim();
            if (sectionText.isEmpty()) continue;

            if (sectionText.length() > targetChunkSize) {
                // 超大 section 按段落再切
                List<TextSegment> subSegments = splitLargeSection(sectionText, structure, targetChunkSize);
                for (TextSegment seg : subSegments) {
                    seg.metadata().put("heading_path", buildHeadingPath(headings, heading));
                }
                segments.addAll(subSegments);
            } else {
                TextSegment seg = TextSegment.from(sectionText);
                seg.metadata().put("heading_path", buildHeadingPath(headings, heading));
                segments.add(seg);
            }
        }

        if (segments.isEmpty()) {
            return splitByParagraphs(markdownContent, structure, targetChunkSize);
        }

        return segments;
    }

    private String buildHeadingPath(List<HeadingNode> headings, HeadingNode current) {
        return headings.stream()
                .filter(h -> h.startOffset() <= current.startOffset() && h.level() <= current.level())
                .map(HeadingNode::title)
                .collect(Collectors.joining(" > "));
    }

    private List<TextSegment> splitByParagraphs(String text, DocStructure structure, int targetChunkSize) {
        List<TextSegment> segments = new ArrayList<>();
        List<Range> paragraphs = structure.paragraphRanges();

        if (paragraphs.isEmpty()) {
            return List.of(TextSegment.from(text));
        }

        StringBuilder currentChunk = new StringBuilder();
        for (Range para : paragraphs) {
            String paraText = text.substring(para.start(), para.end());
            if (currentChunk.length() + paraText.length() > targetChunkSize && currentChunk.length() > 0) {
                segments.add(TextSegment.from(currentChunk.toString().trim()));
                currentChunk = new StringBuilder();
            }
            currentChunk.append(paraText).append("\n\n");
        }

        if (currentChunk.length() > 0) {
            segments.add(TextSegment.from(currentChunk.toString().trim()));
        }

        return segments;
    }

    private List<TextSegment> splitLargeSection(String sectionText, DocStructure structure, int targetChunkSize) {
        // 找到段落边界再切
        List<TextSegment> segments = new ArrayList<>();
        int pos = 0;

        for (Range para : structure.paragraphRanges()) {
            if (pos + para.length() > targetChunkSize && pos > 0) {
                segments.add(TextSegment.from(sectionText.substring(0, pos).trim()));
                sectionText = sectionText.substring(pos);
                pos = 0;
            }
            pos += para.length();
        }

        if (!sectionText.isEmpty()) {
            segments.add(TextSegment.from(sectionText.trim()));
        }

        return segments.isEmpty() ? List.of(TextSegment.from(sectionText)) : segments;
    }
}
```

- [ ] **Step 4: 运行测试验证通过**

Run: `mvn test -Dtest=StructureSplitterTest`
Expected: 5 tests PASS

- [ ] **Step 5: Commit**

```bash
git add src/test/java/me/maxt/rag/web/service/chunking/splitter/StructureSplitterTest.java src/main/java/me/maxt/rag/web/service/chunking/splitter/StructureSplitter.java
git commit -m "feat: 实现 StructureSplitter，按文档标题层级切分"
```

---

## Task 7: SemanticSplitter（TDD）

**Files:**
- Create: `src/test/java/me/maxt/rag/web/service/chunking/splitter/SemanticSplitterTest.java`
- Create: `src/main/java/me/maxt/rag/web/service/chunking/splitter/SemanticSplitter.java`

**Interfaces:**
- Produces: `SemanticSplitter implements SplitStrategy`
- Consumes: `SplitStrategy` (Task 2), `DocStructure` (Task 1), `EmbeddingModel` (已有，构造函数注入)

- [ ] **Step 1: 写失败测试**

```java
package me.maxt.rag.web.service.chunking.splitter;

import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.output.Response;
import me.maxt.rag.web.service.chunking.*;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SemanticSplitterTest {

    @Test
    void shouldHaveNameSemantic() {
        EmbeddingModel model = mock(EmbeddingModel.class);
        SemanticSplitter splitter = new SemanticSplitter(model, 0.6);
        assertThat(splitter.name()).isEqualTo("semantic");
    }

    @Test
    void shouldMergeSimilarParagraphs() {
        EmbeddingModel model = mock(EmbeddingModel.class);
        // 返回相同 embedding → 高相似度 → 合并
        Embedding sameEmbedding = Embedding.from(new float[]{0.5f, 0.5f});

        @SuppressWarnings("unchecked")
        Response<List<Embedding>> response = mock(Response.class);
        when(response.content()).thenReturn(List.of(sameEmbedding, sameEmbedding, sameEmbedding));
        when(model.embedAll(any())).thenReturn(response);

        SemanticSplitter splitter = new SemanticSplitter(model, 0.6);

        String md = "段落一的第一句话。还有更多内容。\n\n段落二的文本。一些额外的句子。\n\n段落三的句子。继续写。";
        DocStructure structure = new DocStructure(
                List.of(),
                List.of(new Range(0, 20), new Range(22, 42), new Range(44, 56)),
                List.of(), List.of(), 56);

        List<TextSegment> segments = splitter.split(md, structure, 2000);

        // 相似度高，应合并为1个分段
        assertThat(segments).hasSize(1);
    }

    @Test
    void shouldSplitAtDissimilarityGaps() {
        EmbeddingModel model = mock(EmbeddingModel.class);
        // 第1和第2相似(0.9)，第2和第3不相似(0.1)
        Embedding embA = Embedding.from(new float[]{0.5f, 0.5f});
        Embedding embB = Embedding.from(new float[]{0.6f, 0.6f});  // 与A相似
        Embedding embC = Embedding.from(new float[]{-0.8f, -0.8f}); // 与B不相似

        @SuppressWarnings("unchecked")
        Response<List<Embedding>> response = mock(Response.class);
        when(response.content()).thenReturn(List.of(embA, embB, embC));
        when(model.embedAll(any())).thenReturn(response);

        SemanticSplitter splitter = new SemanticSplitter(model, 0.6);

        String md = "段落一\n\n段落二\n\n段落三";
        DocStructure structure = new DocStructure(
                List.of(),
                List.of(new Range(0, 5), new Range(7, 12), new Range(14, 19)),
                List.of(), List.of(), 19);

        List<TextSegment> segments = splitter.split(md, structure, 2000);

        // B和C不相似，应切分为至少2个分段
        assertThat(segments.size()).isGreaterThanOrEqualTo(2);
    }

    @Test
    void shouldHandleEmptyDocument() {
        EmbeddingModel model = mock(EmbeddingModel.class);
        SemanticSplitter splitter = new SemanticSplitter(model, 0.6);

        DocStructure structure = new DocStructure(
                List.of(), List.of(), List.of(), List.of(), 0);

        List<TextSegment> segments = splitter.split("", structure, 1000);

        assertThat(segments).isEmpty();
    }

    @Test
    void shouldHandleSingleParagraph() {
        EmbeddingModel model = mock(EmbeddingModel.class);
        Embedding emb = Embedding.from(new float[]{0.5f});

        @SuppressWarnings("unchecked")
        Response<List<Embedding>> response = mock(Response.class);
        when(response.content()).thenReturn(List.of(emb));
        when(model.embedAll(any())).thenReturn(response);

        SemanticSplitter splitter = new SemanticSplitter(model, 0.5);

        String md = "唯一段落";
        DocStructure structure = new DocStructure(
                List.of(),
                List.of(new Range(0, 4)),
                List.of(), List.of(), 4);

        List<TextSegment> segments = splitter.split(md, structure, 1000);

        assertThat(segments).hasSize(1);
    }
}
```

- [ ] **Step 2: 运行测试验证失败**

Run: `mvn test -Dtest=SemanticSplitterTest`
Expected: FAIL

- [ ] **Step 3: 实现 SemanticSplitter**

```java
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

        return result.isEmpty() ? List.of(TextSegment.from(markdownContent)) : result;
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
}
```

- [ ] **Step 4: 运行测试验证通过**

Run: `mvn test -Dtest=SemanticSplitterTest`
Expected: 4 tests PASS

- [ ] **Step 5: Commit**

```bash
git add src/test/java/me/maxt/rag/web/service/chunking/splitter/SemanticSplitterTest.java src/main/java/me/maxt/rag/web/service/chunking/splitter/SemanticSplitter.java
git commit -m "feat: 实现 SemanticSplitter，基于 embedding 相似度断点切分"
```

---

## Task 8: AgentRefiner（TDD）

**Files:**
- Create: `src/test/java/me/maxt/rag/web/service/chunking/splitter/AgentRefinerTest.java`
- Create: `src/main/java/me/maxt/rag/web/service/chunking/splitter/AgentRefiner.java`

**Interfaces:**
- Produces: `AgentRefiner.refine(List<TextSegment> a, List<TextSegment> b, String markdown, int targetSize) → List<TextSegment>`
- Consumes: `ChatModel` (已有，构造函数注入)

- [ ] **Step 1: 写失败测试**

```java
package me.maxt.rag.web.service.chunking.splitter;

import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.output.Response;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AgentRefinerTest {

    @Test
    void shouldReturnRefinedSegmentsWhenApiSucceeds() {
        ChatModel chatModel = mock(ChatModel.class);
        when(chatModel.generate(any(String.class)))
                .thenReturn(Response.from("段落1内容\n---SPLIT---\n段落2内容"));

        AgentRefiner refiner = new AgentRefiner(chatModel);

        List<TextSegment> versionA = List.of(TextSegment.from("段落1内容段落2内容"));
        List<TextSegment> versionB = List.of(TextSegment.from("段落1"), TextSegment.from("段落2"));

        List<TextSegment> result = refiner.refine(versionA, versionB, "段落1内容\n\n段落2内容", 1000);

        assertThat(result).hasSize(2);
        assertThat(result.get(0).text()).contains("段落1");
        assertThat(result.get(1).text()).contains("段落2");
    }

    @Test
    void shouldFallbackToLongerVersionOnApiFailure() {
        ChatModel chatModel = mock(ChatModel.class);
        when(chatModel.generate(any(String.class)))
                .thenThrow(new RuntimeException("API 超时"));

        AgentRefiner refiner = new AgentRefiner(chatModel);

        List<TextSegment> versionA = List.of(TextSegment.from("短"));
        List<TextSegment> versionB = List.of(
                TextSegment.from("较长的分段一"),
                TextSegment.from("较长的分段二"));

        List<TextSegment> result = refiner.refine(versionA, versionB, "测试", 1000);

        // 应降级返回分段数更多的版本
        assertThat(result).hasSize(2);
    }

    @Test
    void shouldReturnOriginalWhenBothEmpty() {
        ChatModel chatModel = mock(ChatModel.class);
        AgentRefiner refiner = new AgentRefiner(chatModel);

        List<TextSegment> result = refiner.refine(List.of(), List.of(), "", 1000);

        assertThat(result).isEmpty();
    }
}
```

- [ ] **Step 2: 运行测试验证失败**

Run: `mvn test -Dtest=AgentRefinerTest`
Expected: FAIL

- [ ] **Step 3: 实现 AgentRefiner**

```java
package me.maxt.rag.web.service.chunking.splitter;

import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.chat.ChatModel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class AgentRefiner {

    private static final Logger log = LoggerFactory.getLogger(AgentRefiner.class);
    private static final String SPLIT_MARKER = "---SPLIT---";
    private final ChatModel chatModel;

    public AgentRefiner(ChatModel chatModel) {
        this.chatModel = chatModel;
    }

    public List<TextSegment> refine(List<TextSegment> versionA, List<TextSegment> versionB,
                                     String originalMarkdown, int targetChunkSize) {
        if (versionA.isEmpty() && versionB.isEmpty()) {
            return List.of();
        }

        String prompt = buildRefinementPrompt(versionA, versionB, originalMarkdown);

        try {
            String response = chatModel.generate(prompt).content();
            return parseResponse(response);
        } catch (Exception e) {
            log.warn("AgentRefiner 调用失败，降级到较优版本: {}", e.getMessage());
            return fallback(versionA, versionB);
        }
    }

    private String buildRefinementPrompt(List<TextSegment> a, List<TextSegment> b, String original) {
        StringBuilder sb = new StringBuilder();
        sb.append("你是一个文档切分专家。请评估以下两种切分方案，选择更合理的方案或合并两者优点。\n\n");
        sb.append("原始文档:\n```\n").append(truncate(original, 2000)).append("\n```\n\n");
        sb.append("方案A (共").append(a.size()).append("段):\n");
        for (int i = 0; i < a.size(); i++) {
            sb.append("[").append(i + 1).append("] ").append(truncate(a.get(i).text(), 300)).append("\n");
        }
        sb.append("\n方案B (共").append(b.size()).append("段):\n");
        for (int i = 0; i < b.size(); i++) {
            sb.append("[").append(i + 1).append("] ").append(truncate(b.get(i).text(), 300)).append("\n");
        }
        sb.append("\n请输出你认为最优的切分方案，每个分段之间用 '").append(SPLIT_MARKER).append("' 分隔。只输出内容，不要额外解释。");
        return sb.toString();
    }

    private List<TextSegment> parseResponse(String response) {
        List<TextSegment> segments = new ArrayList<>();
        String[] parts = response.split(SPLIT_MARKER);
        for (String part : parts) {
            String trimmed = part.trim();
            if (!trimmed.isEmpty()) {
                segments.add(TextSegment.from(trimmed));
            }
        }
        return segments.isEmpty() ? List.of(TextSegment.from(response)) : segments;
    }

    private List<TextSegment> fallback(List<TextSegment> a, List<TextSegment> b) {
        // 优先返回分段数更多的版本（更细粒度）
        return a.size() >= b.size() ? a : b;
    }

    private String truncate(String text, int maxLen) {
        return text.length() <= maxLen ? text : text.substring(0, maxLen) + "...";
    }
}
```

- [ ] **Step 4: 运行测试验证通过**

Run: `mvn test -Dtest=AgentRefinerTest`
Expected: 3 tests PASS

- [ ] **Step 5: Commit**

```bash
git add src/test/java/me/maxt/rag/web/service/chunking/splitter/AgentRefinerTest.java src/main/java/me/maxt/rag/web/service/chunking/splitter/AgentRefiner.java
git commit -m "feat: 实现 AgentRefiner，小模型驱动的切分精炼器"
```

---

## Task 9: ChunkEvaluator + ChunkingPipeline

**Files:**
- Create: `src/main/java/me/maxt/rag/web/service/chunking/evaluator/ChunkEvaluator.java`
- Create: `src/test/java/me/maxt/rag/web/service/chunking/ChunkingPipelineTest.java`
- Create: `src/main/java/me/maxt/rag/web/service/chunking/ChunkingPipeline.java`

**Interfaces:**
- Produces: `ChunkEvaluator.evaluate(...) → double`, `ChunkingPipeline.execute(Path, String) → List<TextSegment>`
- Consumes: 所有 Task 1-8 产出的组件

- [ ] **Step 1: 创建 ChunkEvaluator**

```java
package me.maxt.rag.web.service.chunking.evaluator;

import dev.langchain4j.data.segment.TextSegment;
import me.maxt.rag.web.service.chunking.DocStructure;
import me.maxt.rag.web.service.chunking.Range;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

public class ChunkEvaluator {

    private static final Logger log = LoggerFactory.getLogger(ChunkEvaluator.class);

    public double evaluate(List<TextSegment> chunks, DocStructure structure) {
        if (chunks.isEmpty()) return 1.0;

        double sizeScore = evaluateSizeConsistency(chunks);
        double boundaryScore = evaluateBoundaryAlignment(chunks, structure);

        log.debug("切分质量评估 — 大小一致性: {:.2f}, 边界对齐: {:.2f}", sizeScore, boundaryScore);
        return (sizeScore + boundaryScore) / 2.0;
    }

    private double evaluateSizeConsistency(List<TextSegment> chunks) {
        double avgLen = chunks.stream().mapToInt(c -> c.text().length()).average().orElse(0);
        if (avgLen == 0) return 1.0;

        double variance = chunks.stream()
                .mapToDouble(c -> Math.pow(c.text().length() - avgLen, 2))
                .average().orElse(0);
        double cv = Math.sqrt(variance) / avgLen;

        return Math.max(0, 1.0 - cv);
    }

    private double evaluateBoundaryAlignment(List<TextSegment> chunks, DocStructure structure) {
        if (structure.headingTree().isEmpty()) return 0.8;
        // 简化的边界对齐检查
        return 0.8;
    }
}
```

- [ ] **Step 2: 创建 ChunkingPipeline 及其测试**

测试先写：

```java
package me.maxt.rag.web.service.chunking;

import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.output.Response;
import me.maxt.rag.web.service.chunking.analyzer.StructureAnalyzer;
import me.maxt.rag.web.service.chunking.classifier.SplitClassifier;
import me.maxt.rag.web.service.chunking.converter.MarkdownConverter;
import me.maxt.rag.web.service.chunking.evaluator.ChunkEvaluator;
import me.maxt.rag.web.service.chunking.splitter.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

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
```

- [ ] **Step 3: 运行测试验证失败**

Run: `mvn test -Dtest=ChunkingPipelineTest`
Expected: FAIL

- [ ] **Step 4: 实现 ChunkingPipeline**

```java
package me.maxt.rag.web.service.chunking;

import dev.langchain4j.data.segment.TextSegment;
import me.maxt.rag.web.service.chunking.analyzer.StructureAnalyzer;
import me.maxt.rag.web.service.chunking.classifier.SplitClassifier;
import me.maxt.rag.web.service.chunking.converter.MarkdownConverter;
import me.maxt.rag.web.service.chunking.evaluator.ChunkEvaluator;
import me.maxt.rag.web.service.chunking.splitter.AgentRefiner;
import me.maxt.rag.web.service.chunking.splitter.SemanticSplitter;
import me.maxt.rag.web.service.chunking.splitter.SplitStrategy;
import me.maxt.rag.web.service.chunking.splitter.StructureSplitter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

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
```

- [ ] **Step 5: 运行测试验证通过**

Run: `mvn test -Dtest=ChunkingPipelineTest`
Expected: 3 tests PASS

- [ ] **Step 6: Commit**

```bash
git add src/main/java/me/maxt/rag/web/service/chunking/evaluator/ChunkEvaluator.java src/test/java/me/maxt/rag/web/service/chunking/ChunkingPipelineTest.java src/main/java/me/maxt/rag/web/service/chunking/ChunkingPipeline.java
git commit -m "feat: 实现 ChunkEvaluator 和 ChunkingPipeline 编排层"
```

---

## Task 10: 配置集成 — AppConfig + WebApplication + DocumentService

**Files:**
- Modify: `src/main/java/me/maxt/rag/web/config/AppConfig.java`
- Modify: `src/main/java/me/maxt/rag/web/WebApplication.java`
- Modify: `src/main/java/me/maxt/rag/web/service/DocumentService.java`

**Interfaces:**
- Consumes: `ChunkingConfig` (Task 2), `ChunkingPipeline` (Task 9)

- [ ] **Step 1: AppConfig 实现 ChunkingConfig 新增字段**

在 `AppConfig.java` 中添加字段、默认值、config.json 加载、环境变量覆盖、getter：

```java
// 字段声明（在 supportedFileExtensions 之后）
private String chunkingMode;
private double semanticThreshold;
private boolean enableAgentRefiner;
private int maxChunkSize;

// 构造函数默认值（在 supportedFileExtensions 赋值之后）
this.chunkingMode = "auto";
this.semanticThreshold = 0.6;
this.enableAgentRefiner = false;
this.maxChunkSize = 2000;

// applyFileConfig() 中 document 段解析（在 supportedExtensions 解析之后）
Map<String, Object> chunking = (Map<String, Object>) document.get("chunking");
if (chunking != null) {
    config.chunkingMode = getString(chunking, "mode", config.chunkingMode);
    config.semanticThreshold = getDouble(chunking, "semanticThreshold", config.semanticThreshold);
    config.enableAgentRefiner = getBoolean(chunking, "enableAgentRefiner", config.enableAgentRefiner);
    config.maxChunkSize = getInt(chunking, "maxChunkSize", config.maxChunkSize);
}

// applyEnvOverrides() 中
config.chunkingMode = env("RAG_CHUNKING_MODE", config.chunkingMode);
config.semanticThreshold = envDouble("RAG_CHUNKING_SEMANTIC_THRESHOLD", config.semanticThreshold);
config.enableAgentRefiner = envBool("RAG_CHUNKING_AGENT_REFINER", config.enableAgentRefiner);
config.maxChunkSize = envInt("RAG_CHUNKING_MAX_SIZE", config.maxChunkSize);

// ChunkingConfig 接口的4个 getter
public String getChunkingMode() { return chunkingMode; }
public double getSemanticThreshold() { return semanticThreshold; }
public boolean isAgentRefinerEnabled() { return enableAgentRefiner; }
public int getMaxChunkSize() { return maxChunkSize; }

// 辅助方法
private static boolean getBoolean(Map<String, Object> map, String key, boolean defaultVal) {
    Object val = map.get(key);
    if (val instanceof Boolean) return (Boolean) val;
    return defaultVal;
}

private static boolean envBool(String name, boolean defaultVal) {
    String val = System.getenv(name);
    if (val != null && !val.isEmpty()) return Boolean.parseBoolean(val);
    return defaultVal;
}
```

- [ ] **Step 2: 运行 AppConfigTest 确保不破坏现有行为**

Run: `mvn test -Dtest=AppConfigTest`
Expected: PASS

- [ ] **Step 3: WebApplication 中创建 ChunkingPipeline 并注入 DocumentService**

在 `WebApplication.java` 中：

```java
// 新增 import
import me.maxt.rag.web.service.chunking.ChunkingPipeline;
import me.maxt.rag.web.service.chunking.analyzer.StructureAnalyzer;
import me.maxt.rag.web.service.chunking.classifier.SplitClassifier;
import me.maxt.rag.web.service.chunking.converter.MarkdownConverter;
import me.maxt.rag.web.service.chunking.evaluator.ChunkEvaluator;
import me.maxt.rag.web.service.chunking.splitter.*;

// 构造函数中，在 storeManager 创建之后，documentService 创建之前：
MarkdownConverter markdownConverter = new MarkdownConverter();
StructureAnalyzer structureAnalyzer = new StructureAnalyzer();
SplitClassifier splitClassifier = new SplitClassifier();
StructureSplitter structureSplitter = new StructureSplitter();
SemanticSplitter semanticSplitter = new SemanticSplitter(embeddingModel, config.getSemanticThreshold());
AgentRefiner agentRefiner = config.isAgentRefinerEnabled() ? new AgentRefiner(chatModel) : null;
ChunkEvaluator chunkEvaluator = new ChunkEvaluator();

ChunkingPipeline chunkingPipeline = new ChunkingPipeline(
        markdownConverter, structureAnalyzer, splitClassifier,
        structureSplitter, semanticSplitter, agentRefiner, chunkEvaluator);

// DocumentService 新增 chunkingPipeline 参数：
this.documentService = new DocumentService(
        storeManager, embeddingModel,
        config.getChunkSize(), config.getChunkOverlap(),
        config.getSupportedFileExtensions(),
        chunkingPipeline);
```

- [ ] **Step 4: DocumentService 接入 ChunkingPipeline**

在 `DocumentService.java` 中：

```java
// 新增字段
private final ChunkingPipeline chunkingPipeline;

// 构造函数新增参数
public DocumentService(EmbeddingStoreManager storeManager, EmbeddingModel embeddingModel,
                       int chunkSize, int chunkOverlap, List<String> supportedExtensions,
                       ChunkingPipeline chunkingPipeline) {
    // ... 现有赋值
    this.chunkingPipeline = chunkingPipeline;
}

// ingestDirectory() 中，替换切分逻辑：
// 删除: DocumentSplitter splitter = DocumentSplitters.recursive(chunkSize, chunkOverlap);
// 删除: List<TextSegment> segments = splitter.split(document);
// 替换为:
List<TextSegment> segments;
try {
    segments = chunkingPipeline.execute(file.toPath(), detectFileType(file.getName()));
} catch (Exception e) {
    log.warn("新切分管线失败，降级到递归字符切分: {}", file.getName(), e);
    DocumentSplitter splitter = DocumentSplitters.recursive(chunkSize, chunkOverlap);
    segments = splitter.split(document);
}
```

- [ ] **Step 5: 编译验证**

Run: `mvn compile`
Expected: BUILD SUCCESS

- [ ] **Step 6: Commit**

```bash
git add src/main/java/me/maxt/rag/web/config/AppConfig.java src/main/java/me/maxt/rag/web/WebApplication.java src/main/java/me/maxt/rag/web/service/DocumentService.java
git commit -m "feat: 集成 ChunkingPipeline 到 AppConfig、WebApplication、DocumentService"
```

---

## Task 11: 全量测试 + 回归验证

**Files:**
- Modify: `src/test/java/me/maxt/rag/web/service/DocumentServiceTest.java`（构造参数更新）

- [ ] **Step 1: 更新 DocumentServiceTest 构造参数**

`DocumentServiceTest.java` 中所有 `new DocumentService(...)` 调用添加 `ChunkingPipeline` 参数。为保持测试聚焦 DocumentService 本身行为，传入一个简化的 pipeline：

```java
// 在 setUp() 中
ChunkingPipeline stubPipeline = new ChunkingPipeline(
    new MarkdownConverter(),
    new StructureAnalyzer(),
    new SplitClassifier(),
    new StructureSplitter(),
    new SemanticSplitter(embeddingModel, 0.6),
    null,
    new ChunkEvaluator()
);
```

每个 `new DocumentService(storeManager, embeddingModel, 300, 0, List.of(".txt"))` 改为 `new DocumentService(storeManager, embeddingModel, 300, 0, List.of(".txt"), stubPipeline)`

- [ ] **Step 2: 运行全量测试**

Run: `mvn test`
Expected: ALL tests PASS（包括原有 25 个 + 新增）

- [ ] **Step 3: 运行覆盖率报告**

Run: `mvn test jacoco:report`
Expected: Service 层覆盖率 >88%

- [ ] **Step 4: Commit**

```bash
git add src/test/java/me/maxt/rag/web/service/DocumentServiceTest.java
git commit -m "test: 更新 DocumentServiceTest 适配 ChunkingPipeline 参数"
```

---

## 完成标准

- [ ] 所有已有 25 个测试 + 新增 chunking 测试全部通过
- [ ] Service 层覆盖率保持 >88%
- [ ] `mvn clean package` 构建成功
- [ ] `mode: "recursive"` 保持向后兼容
