package me.maxt.rag.web.service.evaluation;

import java.util.List;
import java.util.Map;

/**
 * 评估报告，对应 baseline.json / 时间戳报告 / summary.json。
 */
public class EvaluationReport {

    public String format;
    public String version = "1.0";
    public String timestamp;
    public String type; // "baseline" | "evaluation"
    public Map<String, MetricScore> metrics;
    public Map<String, MetricScore> answerQuality;
    public List<TestCaseScore> perTestCase;

    // summary.json 专用字段
    public List<FormatSummary> formats;
    public String overallStatus;
    public String baselineTimestamp;

    public static class MetricScore {
        public double score;
        public Map<String, Object> details;

        public MetricScore() {}

        public MetricScore(double score) {
            this.score = score;
        }

        public MetricScore(double score, Map<String, Object> details) {
            this.score = score;
            this.details = details;
        }
    }

    public static class TestCaseScore {
        public String id;
        public double recallAtK;
        public double precisionAtK;
        public double mrr;
        public double ndcgAtK;
        public Integer faithfulness;
        public Integer answerRelevancy;
    }

    public static class FormatSummary {
        public String format;
        public String status; // "pass" | "degraded"
        public List<Degradation> degradations;
    }

    public static class Degradation {
        public String metric;
        public double baseline;
        public double current;
        public double delta;
    }
}
