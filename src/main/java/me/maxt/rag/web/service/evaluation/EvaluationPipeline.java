package me.maxt.rag.web.service.evaluation;

import me.maxt.rag.web.config.EvaluationConfig;
import me.maxt.rag.web.service.RAGService;
import me.maxt.rag.web.service.RAGService.AnswerWithSources;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.*;

/**
 * 评估管线编排入口，串联 6 个组件执行一个格式的完整评估流程。
 */
public class EvaluationPipeline {

    private static final Logger log = LoggerFactory.getLogger(EvaluationPipeline.class);

    private final EvaluationConfig config;
    private final DatasetLoader datasetLoader;
    private final KnowledgeBaseSeeder seeder;
    private final RetrievalEvaluator retrievalEvaluator;
    private final AnswerQualityEvaluator answerQualityEvaluator;
    private final BaselineManager baselineManager;
    private final RAGService ragService;

    public EvaluationPipeline(EvaluationConfig config, DatasetLoader datasetLoader,
                              KnowledgeBaseSeeder seeder, RetrievalEvaluator retrievalEvaluator,
                              AnswerQualityEvaluator answerQualityEvaluator,
                              BaselineManager baselineManager, RAGService ragService) {
        this.config = config;
        this.datasetLoader = datasetLoader;
        this.seeder = seeder;
        this.retrievalEvaluator = retrievalEvaluator;
        this.answerQualityEvaluator = answerQualityEvaluator;
        this.baselineManager = baselineManager;
        this.ragService = ragService;
    }

    /**
     * 执行单个格式的评估。
     *
     * @param format         格式名
     * @param srcResourcesDir src/test/resources 路径
     * @param targetDir       target/evaluation 路径
     * @param updateBaseline  是否更新基线
     * @param skipAnswerQuality 是否跳过答案质量评估
     * @return 格式评估报告，失败返回 null
     */
    public EvaluationReport run(String format, Path srcResourcesDir, Path targetDir,
                                 boolean updateBaseline, boolean skipAnswerQuality) {
        log.info("=== 开始评估格式: {} ===", format);

        // 1. 加载测试用例
        DatasetFile dataset = datasetLoader.load(format);
        if (dataset == null || dataset.testCases().isEmpty()) {
            log.warn("{} 无测试用例，跳过", format);
            return null;
        }
        List<String> errors = datasetLoader.validate(dataset);
        if (!errors.isEmpty()) {
            log.error("测试用例校验失败: {}", errors);
            return null;
        }

        // 2. 处理基线
        EvaluationReport baseline = baselineManager.loadOrCreate(format, srcResourcesDir, targetDir);
        boolean isFirstRun = (baseline == null);

        // 3. 入库文档
        int docCount = seeder.seed(format);
        log.info("入库 {} 个文档", docCount);

        // 4. 逐用例评估
        List<EvaluationReport.TestCaseScore> perCase = new ArrayList<>();
        Map<String, List<Double>> allRetrievalScores = new LinkedHashMap<>();
        List<Integer> faithfulnessScores = new ArrayList<>();
        List<Integer> relevancyScores = new ArrayList<>();

        for (TestCase tc : dataset.testCases()) {
            // 4a. 调 RAGService 实时生成答案
            AnswerWithSources result = ragService.answerWithSources(tc.query());
            List<String> retrievedDocNames = result.sources.stream()
                    .map(s -> s.fileName)
                    .toList();
            List<String> contexts = result.sources.stream()
                    .map(s -> s.text)
                    .toList();

            // 4b. 检索指标
            Map<String, Double> retrievalScores = retrievalEvaluator.evaluate(tc, retrievedDocNames);

            // 4c. 答案质量（可选）
            Integer faith = null;
            Integer rel = null;
            if (!skipAnswerQuality && answerQualityEvaluator.isAvailable()) {
                Map<String, QualityScore> aq = answerQualityEvaluator.evaluate(
                        tc.query(), result.answer, contexts);
                if (aq.containsKey("faithfulness")) {
                    faith = aq.get("faithfulness").score();
                    faithfulnessScores.add(faith);
                }
                if (aq.containsKey("answerRelevancy")) {
                    rel = aq.get("answerRelevancy").score();
                    relevancyScores.add(rel);
                }
            }

            // 4d. 收集分数
            var caseScore = new EvaluationReport.TestCaseScore();
            caseScore.id = tc.id();
            caseScore.recallAtK = retrievalScores.getOrDefault("recallAtK", 0.0);
            caseScore.precisionAtK = retrievalScores.getOrDefault("precisionAtK", 0.0);
            caseScore.mrr = retrievalScores.getOrDefault("mrr", 0.0);
            caseScore.ndcgAtK = retrievalScores.getOrDefault("ndcgAtK", 0.0);
            caseScore.faithfulness = faith;
            caseScore.answerRelevancy = rel;
            perCase.add(caseScore);

            for (var entry : retrievalScores.entrySet()) {
                allRetrievalScores.computeIfAbsent(entry.getKey(), k -> new ArrayList<>()).add(entry.getValue());
            }
        }

        // 5. 汇总分数
        Map<String, Double> avgRetrieval = retrievalEvaluator.aggregate(allRetrievalScores);
        Map<String, EvaluationReport.MetricScore> metrics = new LinkedHashMap<>();
        for (var entry : avgRetrieval.entrySet()) {
            var ms = new EvaluationReport.MetricScore(entry.getValue(),
                    Map.of("passed", perCase.size(), "total", perCase.size()));
            metrics.put(entry.getKey(), ms);
        }

        Map<String, EvaluationReport.MetricScore> answerQuality = null;
        if (!faithfulnessScores.isEmpty() || !relevancyScores.isEmpty()) {
            answerQuality = new LinkedHashMap<>();
            if (!faithfulnessScores.isEmpty()) {
                double avg = faithfulnessScores.stream().mapToInt(Integer::intValue).average().orElse(0);
                answerQuality.put("faithfulness", new EvaluationReport.MetricScore(avg,
                        Map.of("scale", "1-5")));
            }
            if (!relevancyScores.isEmpty()) {
                double avg = relevancyScores.stream().mapToInt(Integer::intValue).average().orElse(0);
                answerQuality.put("answerRelevancy", new EvaluationReport.MetricScore(avg,
                        Map.of("scale", "1-5")));
            }
        }

        // 6. 构建报告
        EvaluationReport report = new EvaluationReport();
        report.format = format;
        report.version = "1.0";
        report.timestamp = BaselineManager.timestamp();
        report.type = isFirstRun || updateBaseline ? "baseline" : "evaluation";
        report.metrics = metrics;
        report.answerQuality = answerQuality;
        report.perTestCase = perCase;

        // 7. 保存报告
        Path targetFormatDir = targetDir.resolve(format);
        String filename = (isFirstRun || updateBaseline) ? "baseline.json"
                : report.timestamp.replace(":", "-") + ".json";
        baselineManager.save(report, targetFormatDir.resolve(filename));

        // 首次或更新基线：同时写回 src/test/resources
        if (isFirstRun || updateBaseline) {
            Path srcBaseline = srcResourcesDir.resolve(format).resolve("baseline.json");
            baselineManager.save(report, srcBaseline);
            log.info("基线已写入: {}", srcBaseline);
        }

        // 8. 对比基线
        if (!isFirstRun && baseline != null) {
            List<EvaluationReport.Degradation> degradations = baselineManager.compare(report, baseline);
            if (!degradations.isEmpty()) {
                log.warn("{} 发现 {} 项退化", format, degradations.size());
            } else {
                log.info("{} 对比基线无退化", format);
            }
        }

        log.info("=== 评估完成: {} ===", format);
        return report;
    }
}
