package me.maxt.rag.web.service.chunking;

import java.util.List;

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
