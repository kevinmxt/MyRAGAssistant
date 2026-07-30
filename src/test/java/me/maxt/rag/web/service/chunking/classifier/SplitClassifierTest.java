package me.maxt.rag.web.service.chunking.classifier;

import me.maxt.rag.web.service.chunking.*;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;

class SplitClassifierTest {

    private final SplitClassifier classifier = new SplitClassifier();

    @Test
    void shouldUseStructureForDocumentWithHeadings() {
        DocStructure structure = new DocStructure(
                List.of(new HeadingNode(2, "Section A", 0, 20), new HeadingNode(2, "Section B", 80, 100)),
                List.of(new Range(20, 80), new Range(100, 200)),
                List.of(), List.of(), 200);

        SplitPlan plan = classifier.classify(structure, "MD", "doc.md");

        assertThat(plan.strategies()).isNotEmpty();
        assertThat(plan.strategies().get(0).strategyName()).isEqualTo("structure");
    }

    @Test
    void shouldUseSemanticForFlatLongDocument() {
        DocStructure structure = new DocStructure(
                List.of(),
                generateParagraphs(50),
                List.of(), List.of(), 30000);

        SplitPlan plan = classifier.classify(structure, "TXT", "long.txt");

        boolean hasSemantic = plan.strategies().stream()
                .anyMatch(s -> s.strategyName().equals("semantic"));
        assertThat(hasSemantic).isTrue();
    }

    @Test
    void shouldNotUseAgentRefinerForSmallDocument() {
        DocStructure structure = new DocStructure(
                List.of(new HeadingNode(2, "Section", 0, 20)),
                List.of(new Range(20, 200)),
                List.of(), List.of(), 200);

        SplitPlan plan = classifier.classify(structure, "MD", "small.md");

        assertThat(plan.needsAgentRefinement()).isFalse();
    }

    @Test
    void shouldEnableAgentRefinerForLargeFlatDocument() {
        DocStructure structure = new DocStructure(
                List.of(),
                generateParagraphs(80),
                List.of(), List.of(), 40000);

        SplitPlan plan = classifier.classify(structure, "TXT", "huge.txt");

        assertThat(plan.needsAgentRefinement()).isTrue();
    }

    @Test
    void shouldPreserveCodeBlocksForCodeHeavyDocument() {
        DocStructure structure = new DocStructure(
                List.of(new HeadingNode(1, "Readme", 0, 20)),
                List.of(new Range(20, 100), new Range(400, 500)),
                List.of(new Range(100, 400)),
                List.of(), 500);

        SplitPlan plan = classifier.classify(structure, "MD", "readme.md");

        assertThat(plan.strategies()).anyMatch(s ->
                s.params().getOrDefault("preserveCodeBlocks", false).equals(true));
    }

    @Test
    void shouldReturnFallbackForEmptyStructure() {
        DocStructure structure = new DocStructure(
                List.of(), List.of(), List.of(), List.of(), 0);

        SplitPlan plan = classifier.classify(structure, "TXT", "empty.txt");

        assertThat(plan.strategies()).isNotEmpty();
    }

    private List<Range> generateParagraphs(int count) {
        List<Range> paragraphs = new java.util.ArrayList<>();
        int pos = 0;
        for (int i = 0; i < count; i++) {
            paragraphs.add(new Range(pos, pos + 500));
            pos += 600;
        }
        return paragraphs;
    }
}
