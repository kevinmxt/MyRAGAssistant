package me.maxt.rag.web.service.chunking.analyzer;

import com.vladsch.flexmark.ext.tables.TableBlock;
import com.vladsch.flexmark.ext.tables.TablesExtension;
import com.vladsch.flexmark.ast.*;
import com.vladsch.flexmark.parser.Parser;
import com.vladsch.flexmark.util.ast.Document;
import com.vladsch.flexmark.util.ast.Node;
import com.vladsch.flexmark.util.data.MutableDataSet;
import me.maxt.rag.web.service.chunking.DocStructure;
import me.maxt.rag.web.service.chunking.HeadingNode;
import me.maxt.rag.web.service.chunking.Range;

import java.util.ArrayList;
import java.util.List;

public class StructureAnalyzer {

    private final Parser parser;

    public StructureAnalyzer() {
        MutableDataSet options = new MutableDataSet();
        options.set(Parser.EXTENSIONS, java.util.List.of(new TablesExtension()));
        this.parser = Parser.builder(options).build();
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
