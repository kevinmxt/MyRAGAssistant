package me.maxt.rag.web.service.vector;

import dev.langchain4j.data.segment.TextSegment;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ContextualEnricherTest {

    @Test
    void shouldPrependFileNamePrefix() {
        ContextualEnricher enricher = new ContextualEnricher();
        TextSegment seg = TextSegment.from("系统启动时需要加载配置文件");
        List<TextSegment> result = enricher.enrich(List.of(seg), "产品手册");

        assertThat(result).hasSize(1);
        assertThat(result.get(0).text())
                .startsWith("[产品手册]")
                .contains("系统启动时需要加载配置文件");
    }

    @Test
    void shouldPrependHeadingPathWhenAvailable() {
        ContextualEnricher enricher = new ContextualEnricher();
        TextSegment seg = TextSegment.from("配置项说明");
        seg.metadata().put("heading_path", "第三章 > 配置说明");

        List<TextSegment> result = enricher.enrich(List.of(seg), "产品手册");

        assertThat(result.get(0).text())
                .startsWith("[产品手册] 第三章 > 配置说明\n配置项说明");
    }

    @Test
    void shouldSkipHeadingPathWhenNotAvailable() {
        ContextualEnricher enricher = new ContextualEnricher();
        TextSegment seg = TextSegment.from("plain text");

        List<TextSegment> result = enricher.enrich(List.of(seg), "readme.md");

        assertThat(result.get(0).text())
                .isEqualTo("[readme.md]\nplain text");
    }

    @Test
    void shouldPreserveOriginalMetadata() {
        ContextualEnricher enricher = new ContextualEnricher();
        TextSegment seg = TextSegment.from("content");
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
}
