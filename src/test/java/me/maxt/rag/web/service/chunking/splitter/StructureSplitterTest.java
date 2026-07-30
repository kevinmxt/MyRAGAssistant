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
        // 字符偏移表：
        //  0:#  1:   2:文 3:档 4:标 5:题  6:\n  7:\n
        //  8:#  9:# 10:  11:第12:一13:节 14:\n 15:内16:容17:A 18:\n 19:\n
        // 20:# 21:# 22:  23:第24:二25:节 26:\n 27:内28:容29:B
        String md = "# 文档标题\n\n## 第一节\n内容A\n\n## 第二节\n内容B";
        DocStructure structure = new DocStructure(
                List.of(
                        new HeadingNode(1, "文档标题", 0, 6),
                        new HeadingNode(2, "第一节", 8, 14),
                        new HeadingNode(2, "第二节", 20, 26)),
                List.of(new Range(15, 18), new Range(27, 30)),
                List.of(), List.of(), 30);

        List<TextSegment> segments = splitter.split(md, structure, 1000);

        assertThat(segments).hasSize(2);
        assertThat(segments.get(0).text()).contains("第一节");
        assertThat(segments.get(0).text()).contains("内容A");
        assertThat(segments.get(1).text()).contains("第二节");
        assertThat(segments.get(1).text()).contains("内容B");
    }

    @Test
    void shouldAttachHeadingPathMetadata() {
        //  0:#  1:   2:文 3:档  4:\n  5:\n
        //  6:#  7:#  8:   9:第10:一11:章 12:\n
        // 13:内14:容
        String md = "# 文档\n\n## 第一章\n内容";
        DocStructure structure = new DocStructure(
                List.of(
                        new HeadingNode(1, "文档", 0, 4),
                        new HeadingNode(2, "第一章", 6, 12)),
                List.of(new Range(13, 15)),
                List.of(), List.of(), 15);

        List<TextSegment> segments = splitter.split(md, structure, 1000);

        assertThat(segments).hasSize(1);
        assertThat(segments.get(0).metadata().getString("heading_path"))
                .isEqualTo("文档 > 第一章");
    }

    @Test
    void shouldPreserveCodeBlocksAsAtomicUnits() {
        //  0:#  1:#  2:   3:代 4:码 5:示 6:例  7:\n  8:\n
        //  9:` 10:` 11:` 12:j 13:a 14:v 15:a 16:\n
        // 17:i 18:n 19:t 20:  21:x 22:  23:= 24:  25:1 26:; 27:\n
        // 28:` 29:` 30:` 31:\n 32:\n
        // 33:# 34:# 35:   36:说 37:明 38:\n
        // 39:代 40:码 41:介 42:绍
        String md = "## 代码示例\n\n```java\nint x = 1;\n```\n\n## 说明\n代码介绍";
        DocStructure structure = new DocStructure(
                List.of(
                        new HeadingNode(2, "代码示例", 0, 7),
                        new HeadingNode(2, "说明", 33, 38)),
                List.of(new Range(39, 43)),
                List.of(new Range(9, 31)),
                List.of(), 43);

        List<TextSegment> segments = splitter.split(md, structure, 1000);

        // 代码块应该完整保留在第一个分段中
        assertThat(segments.get(0).text()).contains("int x = 1;");
    }

    @Test
    void shouldHandleNoHeadings() {
        //  0:没  1:有  2:标  3:题  4:的  5:纯  6:文  7:本  8:内  9:容 10:。
        String md = "没有标题的纯文本内容。";
        DocStructure structure = new DocStructure(
                List.of(),
                List.of(new Range(0, 11)),
                List.of(), List.of(), 11);

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
