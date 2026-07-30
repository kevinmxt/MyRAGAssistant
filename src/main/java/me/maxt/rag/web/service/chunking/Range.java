package me.maxt.rag.web.service.chunking;

public record Range(int start, int end) {
    public Range {
        if (start < 0 || end < start) throw new IllegalArgumentException("Invalid range: [" + start + ", " + end + ")");
    }
    public int length() { return end - start; }
}
