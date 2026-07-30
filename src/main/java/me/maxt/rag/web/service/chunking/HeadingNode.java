package me.maxt.rag.web.service.chunking;

public record HeadingNode(int level, String title, int startOffset, int endOffset) {
    public HeadingNode {
        if (level < 1 || level > 6) throw new IllegalArgumentException("level must be 1-6");
        if (title == null) throw new IllegalArgumentException("title must not be null");
    }
}
