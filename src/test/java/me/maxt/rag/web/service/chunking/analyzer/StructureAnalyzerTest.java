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
