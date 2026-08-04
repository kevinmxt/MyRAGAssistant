package me.maxt.rag.web.service.vector;

import dev.langchain4j.data.segment.TextSegment;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ContextualEnricherTest {

    @Test
    void shouldPrependFileNamePrefix() {
        ContextualEnricher enricher = new ContextualEnricher();
        TextSegment seg = TextSegment.from("系统启动时需要加载配置文件，该文件包含数据库连接参数、缓存策略、日志级别以及网络端口等关键配置信息，必须正确设置。");
        List<TextSegment> result = enricher.enrich(List.of(seg), "产品手册");

        assertThat(result).hasSize(1);
        assertThat(result.get(0).text())
                .startsWith("[产品手册]")
                .contains("系统启动时需要加载配置文件");
    }

    @Test
    void shouldPrependHeadingPathWhenAvailable() {
        ContextualEnricher enricher = new ContextualEnricher();
        TextSegment seg = TextSegment.from("配置项说明部分，涵盖了所有系统参数的详细定义和默认值设置，以及各参数间的依赖关系和约束条件，是运维手册的核心内容。");
        seg.metadata().put("heading_path", "第三章 > 配置说明");

        List<TextSegment> result = enricher.enrich(List.of(seg), "产品手册");

        assertThat(result.get(0).text())
                .startsWith("[产品手册] 第三章 > 配置说明\n配置项说明");
    }

    @Test
    void shouldSkipHeadingPathWhenNotAvailable() {
        ContextualEnricher enricher = new ContextualEnricher();
        TextSegment seg = TextSegment.from("plain text content that is long enough to exceed the minimum threshold for context enrichment");

        List<TextSegment> result = enricher.enrich(List.of(seg), "readme.md");

        assertThat(result.get(0).text())
                .isEqualTo("[readme.md]\nplain text content that is long enough to exceed the minimum threshold for context enrichment");
    }

    @Test
    void shouldPreserveOriginalMetadata() {
        ContextualEnricher enricher = new ContextualEnricher();
        TextSegment seg = TextSegment.from("文档正文内容足够长以确保上下文增强器添加前缀而非直接跳过。");
        seg.metadata().put("file_name", "doc.txt");
        seg.metadata().put("file_type", "TXT");

        List<TextSegment> result = enricher.enrich(List.of(seg), "doc.txt");

        assertThat(result.get(0).metadata().getString("file_name")).isEqualTo("doc.txt");
        assertThat(result.get(0).metadata().getString("file_type")).isEqualTo("TXT");
    }

    @Test
    void shouldHandleEmptySegmentsList() {
        ContextualEnricher enricher = new ContextualEnricher();
        List<TextSegment> result = enricher.enrich(List.of(), "empty.md");
        assertThat(result).isEmpty();
    }

    @Test
    void shouldSkipPrefixForShortChunks() {
        ContextualEnricher enricher = new ContextualEnricher();
        TextSegment seg = TextSegment.from("短文本");
        seg.metadata().put("heading_path", "第一章");

        List<TextSegment> result = enricher.enrich(List.of(seg), "doc.pdf");

        // 短于 50 字符，不加前缀，保留原文
        assertThat(result.get(0).text()).isEqualTo("短文本");
        assertThat(result.get(0).metadata().getString("heading_path")).isEqualTo("第一章");
    }
}
