package me.maxt.rag.web.service.chunking.splitter;

import dev.langchain4j.data.segment.TextSegment;
import me.maxt.rag.web.service.chunking.DocStructure;

import java.util.List;

/**
 * 文档分块策略接口。
 *
 * <p>每种分块策略实现此接口，接收 Markdown 原文、文档结构信息和目标块大小，
 * 返回分块后的 {@link TextSegment} 列表。</p>
 */
public interface SplitStrategy {

    /**
     * @return 策略名称，用于日志和配置标识
     */
    String name();

    /**
     * 对 Markdown 内容执行分块。
     *
     * @param markdownContent 完整的 Markdown 原文
     * @param structure       文档结构信息（标题层级、段落范围等）
     * @param targetChunkSize 目标分块大小（字符数）
     * @return 分块后的文本段列表
     */
    List<TextSegment> split(String markdownContent, DocStructure structure, int targetChunkSize);
}
