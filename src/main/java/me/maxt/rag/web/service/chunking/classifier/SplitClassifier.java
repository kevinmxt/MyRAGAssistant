package me.maxt.rag.web.service.chunking.classifier;

import me.maxt.rag.web.service.chunking.*;
import java.util.*;

public class SplitClassifier {

    private static final int SMALL_DOC_THRESHOLD = 5000;
    private static final int LARGE_DOC_THRESHOLD = 20000;
    private static final double HIGH_CODE_RATIO = 0.3;
    private static final double DEFAULT_SEMANTIC_THRESHOLD = 0.6;

    public SplitPlan classify(DocStructure structure, String fileType, String fileName) {
        boolean hasHierarchy = structure.hasClearHeadingHierarchy();
        boolean isSmall = structure.totalLength() < SMALL_DOC_THRESHOLD;
        boolean isLarge = structure.totalLength() > LARGE_DOC_THRESHOLD;
        boolean isCodeHeavy = structure.codeBlockRatio() > HIGH_CODE_RATIO;
        boolean isFlat = !hasHierarchy || structure.maxSectionDepth() < 2;

        List<StrategyEntry> strategies = new ArrayList<>();
        boolean needsAgentRefiner = false;

        if (isCodeHeavy) {
            strategies.add(new StrategyEntry("structure",
                    Map.of("preserveCodeBlocks", true, "headingLevel", 2)));
        } else if (hasHierarchy) {
            strategies.add(new StrategyEntry("structure", Map.of("headingLevel", 2)));
        }

        if (isSmall && hasHierarchy) {
            strategies.clear();
            strategies.add(new StrategyEntry("structure", Map.of("headingLevel", 2)));
        } else if (isLarge && isFlat) {
            if (!hasHierarchy && !isCodeHeavy) {
                strategies.add(new StrategyEntry("structure", Map.of()));
            }
            strategies.add(new StrategyEntry("semantic",
                    Map.of("threshold", DEFAULT_SEMANTIC_THRESHOLD)));
            needsAgentRefiner = true;
        } else if (!hasHierarchy && !isCodeHeavy) {
            strategies.add(new StrategyEntry("semantic",
                    Map.of("threshold", DEFAULT_SEMANTIC_THRESHOLD)));
        }

        if (strategies.isEmpty()) {
            strategies.add(new StrategyEntry("structure", Map.of()));
        }

        int targetChunkSize = determineTargetSize(structure);
        return new SplitPlan(strategies, needsAgentRefiner, targetChunkSize);
    }

    private int determineTargetSize(DocStructure structure) {
        if (structure.totalLength() < SMALL_DOC_THRESHOLD) return 500;
        if (structure.totalLength() > LARGE_DOC_THRESHOLD) return 2000;
        return 1000;
    }
}
