package me.maxt.rag.web.service.chunking.splitter;

import dev.langchain4j.data.segment.TextSegment;
import me.maxt.rag.web.service.chunking.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 按文档标题层级切分的分块策略。
 *
 * <p>将文档以 H2（##）标题为界切分为多个块，每个块附带标题路径（heading_path）元数据。
 * 对于超大块（超过 targetChunkSize），回退到按段落切分。</p>
 */
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
            log.debug("No headings found, falling back to paragraph split");
            return splitByParagraphs(markdownContent, structure, targetChunkSize);
        }

        List<TextSegment> segments = new ArrayList<>();

        for (int i = 0; i < headings.size(); i++) {
            HeadingNode heading = headings.get(i);
            if (heading.level() < 2) {
                continue; // 跳过 H1（文档标题），从 H2 开始切
            }

            int sectionStart = heading.startOffset();
            int sectionEnd = (i + 1 < headings.size())
                    ? headings.get(i + 1).startOffset()
                    : markdownContent.length();

            String sectionText = markdownContent.substring(sectionStart, sectionEnd).trim();
            if (sectionText.isEmpty()) {
                continue;
            }

            String headingPath = buildHeadingPath(headings, heading);

            if (sectionText.length() > targetChunkSize) {
                List<TextSegment> subSegments = splitLargeSectionByParagraphs(
                        markdownContent, sectionStart, sectionEnd,
                        structure.paragraphRanges(), targetChunkSize);
                for (TextSegment seg : subSegments) {
                    seg.metadata().put("heading_path", headingPath);
                }
                segments.addAll(subSegments);
            } else {
                TextSegment seg = TextSegment.from(sectionText);
                seg.metadata().put("heading_path", headingPath);
                segments.add(seg);
            }
        }

        if (segments.isEmpty()) {
            log.debug("No H2+ sections found, falling back to paragraph split");
            return splitByParagraphs(markdownContent, structure, targetChunkSize);
        }

        log.debug("Split into {} segments using structure strategy", segments.size());
        return segments;
    }

    /**
     * 构建标题路径，例如 "文档 > 第一章 > 第一节"。
     */
    static String buildHeadingPath(List<HeadingNode> headings, HeadingNode current) {
        return headings.stream()
                .filter(h -> h.startOffset() <= current.startOffset()
                        && h.level() <= current.level())
                .map(HeadingNode::title)
                .collect(Collectors.joining(" > "));
    }

    /**
     * 按段落范围切分（回退策略：无标题时使用）。
     */
    private static List<TextSegment> splitByParagraphs(
            String text, DocStructure structure, int targetChunkSize) {

        List<Range> paragraphs = structure.paragraphRanges();

        if (paragraphs.isEmpty()) {
            return List.of(TextSegment.from(text));
        }

        List<TextSegment> segments = new ArrayList<>();
        StringBuilder currentChunk = new StringBuilder();

        for (Range para : paragraphs) {
            if (para.start() >= text.length()) {
                break;
            }
            int end = Math.min(para.end(), text.length());
            String paraText = text.substring(para.start(), end);
            if (currentChunk.length() + paraText.length() > targetChunkSize
                    && currentChunk.length() > 0) {
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

    /**
     * 将超大 section 按段落边界切分为多个块。
     * <p>
     * 段落偏移是文档级别的，而 section 有起始偏移 {@code sectionStart}，
     * 因此需要将段落偏移映射到 section 内部。</p>
     */
    private static List<TextSegment> splitLargeSectionByParagraphs(
            String markdownContent, int sectionStart, int sectionEnd,
            List<Range> paragraphs, int targetChunkSize) {

        List<TextSegment> segments = new ArrayList<>();
        StringBuilder currentChunk = new StringBuilder();

        for (Range para : paragraphs) {
            if (para.start() >= sectionEnd) {
                break;
            }
            if (para.end() <= sectionStart) {
                continue;
            }

            int localStart = Math.max(para.start(), sectionStart);
            int localEnd = Math.min(para.end(), sectionEnd);
            String paraText = markdownContent.substring(localStart, localEnd);

            if (currentChunk.length() + paraText.length() > targetChunkSize
                    && currentChunk.length() > 0) {
                segments.add(TextSegment.from(currentChunk.toString().trim()));
                currentChunk = new StringBuilder();
            }
            currentChunk.append(paraText).append("\n\n");
        }

        if (currentChunk.length() > 0) {
            segments.add(TextSegment.from(currentChunk.toString().trim()));
        }

        if (segments.isEmpty()) {
            String sectionText = markdownContent.substring(sectionStart, sectionEnd).trim();
            return List.of(TextSegment.from(sectionText));
        }
        return segments;
    }
}
