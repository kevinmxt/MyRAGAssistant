# RAG 效果自动化评估 实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 建立 RAG 效果自动化评估体系——五种文档格式独立测试基线，检索质量+答案质量双维度评估。

**Architecture:** 评估管线由 6 个组件串联：DatasetLoader → KnowledgeBaseSeeder → (RAGService) → RetrievalEvaluator → AnswerQualityEvaluator → BaselineManager。全部 Java 实现，复用 EmbeddingStoreManager、ChatModel、ChunkingPipeline。指标通过接口扩展，纯数学检索指标零 token 消耗，答案质量评估走 LLM-as-Judge。

**Tech Stack:** Java 17+, LangChain4j 1.12.1, JUnit 5, Mockito, AssertJ, Maven Surefire, Jackson

## Global Constraints

- 纯 Java 实现，不引入 Python 依赖
- 评估代码放在 `src/main/java/me/maxt/rag/web/service/evaluation/`
- 测试入口放在 `src/test/java/me/maxt/rag/web/evaluation/`
- 测试数据放在 `src/test/resources/evaluation/`
- 只 mock 系统边界（EmbeddingModel、ChatModel），不 mock 自己的模块
- 检索指标数学正确性通过单元测试验证
- 评估使用 InMemoryEmbeddingStore，隔离于生产 Milvus

---

### Task 1: TestCase DTO

**Files:**
- Create: `src/main/java/me/maxt/rag/web/service/evaluation/TestCase.java`

**Interfaces:**
- Produces: `TestCase(id, query, relevantDocs, relevantContent, expectedAnswer)` — 由 DatasetLoader 填充，被所有评估组件消费

- [ ] **Step 1: 创建 TestCase 记录类**

```java
package me.maxt.rag.web.service.evaluation;

import java.util.List;

/**
 * 评估测试用例，映射 testcases.json 中的单条记录。
 */
public record TestCase(
    String id,
    String query,
    List<String> relevantDocs,
    List<String> relevantContent,
    String expectedAnswer
) {
    public boolean hasRelevantContent() {
        return relevantContent != null && !relevantContent.isEmpty();
    }

    public boolean hasExpectedAnswer() {
        return expectedAnswer != null && !expectedAnswer.isEmpty();
    }
}
```

- [ ] **Step 2: 编译验证**

```bash
mvn compile -pl . -q
```

- [ ] **Step 3: 提交**

```bash
git add src/main/java/me/maxt/rag/web/service/evaluation/TestCase.java
git commit -m "feat: 新增 TestCase DTO，映射评估测试用例"
```

---

### Task 2: EvaluationReport DTO

**Files:**
- Create: `src/main/java/me/maxt/rag/web/service/evaluation/EvaluationReport.java`

**Interfaces:**
- Produces: `EvaluationReport` — 包含 metrics map、answerQuality map、perTestCase 列表、format/timestamp/type 字段
- Consumed by: BaselineManager（保存/加载/对比）

- [ ] **Step 1: 创建 EvaluationReport 及内部 DTO**

```java
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
```

- [ ] **Step 2: 编译验证**

```bash
mvn compile -pl . -q
```

- [ ] **Step 3: 提交**

```bash
git add src/main/java/me/maxt/rag/web/service/evaluation/EvaluationReport.java
git commit -m "feat: 新增 EvaluationReport DTO，覆盖基线/时间戳/汇总三种报告"
```

---

### Task 3: EvaluationConfig 接口 + AppConfig 实现

**Files:**
- Create: `src/main/java/me/maxt/rag/web/config/EvaluationConfig.java`
- Modify: `src/main/java/me/maxt/rag/web/config/AppConfig.java`

**Interfaces:**
- Produces: `EvaluationConfig.getEvaluationTopK()`, `getEvaluationFormats()`, `isAnswerQualityEnabled()`, `getDegradationThreshold()`

- [ ] **Step 1: 创建 EvaluationConfig 接口**

```java
package me.maxt.rag.web.config;

import java.util.List;

/**
 * 评估配置接口。
 */
public interface EvaluationConfig {
    /** Recall@K / Precision@K / NDCG@K 的 K 值 */
    int getEvaluationTopK();

    /** 启用的评估格式列表 */
    List<String> getEvaluationFormats();

    /** 是否启用 LLM 答案质量评估 */
    boolean isAnswerQualityEnabled();

    /** 退化判定阈值（如 0.05 表示 5%） */
    double getDegradationThreshold();
}
```

- [ ] **Step 2: 修改 AppConfig 实现 EvaluationConfig**

在 `AppConfig` 类声明中追加 `, EvaluationConfig`：

```java
public class AppConfig implements LlmConfig, RetrievalConfig, DocumentConfig,
        ServerConfig, QueryEnhancementConfig, MilvusConfig, RecallConfig,
        RerankConfig, EvaluationConfig {
```

添加字段：

```java
// ========== 评估配置 ==========
private int evaluationTopK = 5;
private List<String> evaluationFormats = Arrays.asList("markdown", "txt", "pdf", "docx", "json");
private boolean answerQualityEnabled = true;
private double degradationThreshold = 0.05;
```

添加配置解析逻辑（在现有 JSON 解析方法中追加，从 `config.json` 的 `evaluation` 节点读取）：

```java
// 解析 evaluation 配置
@SuppressWarnings("unchecked")
private void loadEvaluationConfig(Map<String, Object> root) {
    Map<String, Object> eval = (Map<String, Object>) root.get("evaluation");
    if (eval == null) return;
    if (eval.get("topK") instanceof Number n) this.evaluationTopK = n.intValue();
    if (eval.get("formats") instanceof List<?> l) {
        this.evaluationFormats = l.stream().map(Object::toString).toList();
    }
    if (eval.get("answerQualityEnabled") instanceof Boolean b) this.answerQualityEnabled = b;
    if (eval.get("degradationThreshold") instanceof Number n) this.degradationThreshold = n.doubleValue();
}
```

在构造函数或 `loadFromFile` 方法末尾调用 `loadEvaluationConfig(root)`。

添加 getter 实现：

```java
@Override public int getEvaluationTopK() { return evaluationTopK; }
@Override public List<String> getEvaluationFormats() { return evaluationFormats; }
@Override public boolean isAnswerQualityEnabled() { return answerQualityEnabled; }
@Override public double getDegradationThreshold() { return degradationThreshold; }
```

- [ ] **Step 3: 编译验证**

```bash
mvn compile -pl . -q
```

- [ ] **Step 4: 提交**

```bash
git add src/main/java/me/maxt/rag/web/config/EvaluationConfig.java \
        src/main/java/me/maxt/rag/web/config/AppConfig.java
git commit -m "feat: 新增 EvaluationConfig 接口，AppConfig 实现评估配置解析"
```

---

### Task 4: 评估指标接口 + 4 个内置实现

**Files:**
- Create: `src/main/java/me/maxt/rag/web/service/evaluation/EvaluationMetric.java`
- Create: `src/main/java/me/maxt/rag/web/service/evaluation/RecallAtK.java`
- Create: `src/main/java/me/maxt/rag/web/service/evaluation/PrecisionAtK.java`
- Create: `src/main/java/me/maxt/rag/web/service/evaluation/MRR.java`
- Create: `src/main/java/me/maxt/rag/web/service/evaluation/NDCGAtK.java`

**Interfaces:**
- Produces: `EvaluationMetric.name()`, `EvaluationMetric.calculate(query, retrievedDocNames, testCase)` → `double`

- [ ] **Step 1: 创建 EvaluationMetric 接口**

```java
package me.maxt.rag.web.service.evaluation;

import java.util.List;

/**
 * 检索评估指标接口。新指标只需实现此接口并注册到 RetrievalEvaluator。
 */
public interface EvaluationMetric {
    String name();
    double calculate(String query, List<String> retrievedDocNames, TestCase testCase);
}
```

- [ ] **Step 2: 创建 RecallAtK 实现**

```java
package me.maxt.rag.web.service.evaluation;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class RecallAtK implements EvaluationMetric {

    private final int k;

    public RecallAtK(int k) {
        this.k = k;
    }

    @Override
    public String name() {
        return "recallAtK";
    }

    @Override
    public double calculate(String query, List<String> retrievedDocNames, TestCase testCase) {
        Set<String> relevant = new HashSet<>(testCase.relevantDocs());
        List<String> topK = retrievedDocNames.stream().limit(k).toList();
        long hitCount = topK.stream().filter(name -> matchesAny(name, relevant)).count();
        return relevant.isEmpty() ? 0.0 : (double) hitCount / relevant.size();
    }

    static boolean matchesAny(String retrievedName, Set<String> relevantNames) {
        for (String rn : relevantNames) {
            if (retrievedName.equals(rn) || retrievedName.endsWith("/" + rn) || retrievedName.endsWith("\\" + rn)) {
                return true;
            }
        }
        return false;
    }
}
```

- [ ] **Step 3: 创建 PrecisionAtK 实现**

```java
package me.maxt.rag.web.service.evaluation;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class PrecisionAtK implements EvaluationMetric {

    private final int k;

    public PrecisionAtK(int k) {
        this.k = k;
    }

    @Override
    public String name() {
        return "precisionAtK";
    }

    @Override
    public double calculate(String query, List<String> retrievedDocNames, TestCase testCase) {
        Set<String> relevant = new HashSet<>(testCase.relevantDocs());
        List<String> topK = retrievedDocNames.stream().limit(k).toList();
        long hitCount = topK.stream().filter(name -> RecallAtK.matchesAny(name, relevant)).count();
        return topK.isEmpty() ? 0.0 : (double) hitCount / topK.size();
    }
}
```

- [ ] **Step 4: 创建 MRR 实现**

```java
package me.maxt.rag.web.service.evaluation;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class MRR implements EvaluationMetric {

    @Override
    public String name() {
        return "mrr";
    }

    @Override
    public double calculate(String query, List<String> retrievedDocNames, TestCase testCase) {
        Set<String> relevant = new HashSet<>(testCase.relevantDocs());
        for (int i = 0; i < retrievedDocNames.size(); i++) {
            if (RecallAtK.matchesAny(retrievedDocNames.get(i), relevant)) {
                return 1.0 / (i + 1);
            }
        }
        return 0.0;
    }
}
```

- [ ] **Step 5: 创建 NDCGAtK 实现**

```java
package me.maxt.rag.web.service.evaluation;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class NDCGAtK implements EvaluationMetric {

    private final int k;

    public NDCGAtK(int k) {
        this.k = k;
    }

    @Override
    public String name() {
        return "ndcgAtK";
    }

    @Override
    public double calculate(String query, List<String> retrievedDocNames, TestCase testCase) {
        Set<String> relevant = new HashSet<>(testCase.relevantDocs());
        double dcg = 0.0;
        double idcg = 0.0;

        for (int i = 0; i < Math.min(retrievedDocNames.size(), k); i++) {
            int rel = RecallAtK.matchesAny(retrievedDocNames.get(i), relevant) ? 1 : 0;
            dcg += (Math.pow(2, rel) - 1) / (Math.log(i + 2) / Math.log(2));
        }

        int idealCount = Math.min(relevant.size(), k);
        for (int i = 0; i < idealCount; i++) {
            idcg += 1.0 / (Math.log(i + 2) / Math.log(2));
        }

        return idcg == 0.0 ? 0.0 : dcg / idcg;
    }
}
```

- [ ] **Step 6: 写单元测试**

创建 `src/test/java/me/maxt/rag/web/service/evaluation/EvaluationMetricsTest.java`：

```java
package me.maxt.rag.web.service.evaluation;

import org.junit.jupiter.api.Test;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class EvaluationMetricsTest {

    @Test
    void recallAtKShouldReturnFullRecallWhenAllRelevantFound() {
        RecallAtK metric = new RecallAtK(5);
        TestCase tc = new TestCase("t1", "q", List.of("a.txt", "b.txt"), null, null);
        double score = metric.calculate("q", List.of("a.txt", "b.txt", "c.txt"), tc);
        assertThat(score).isEqualTo(1.0);
    }

    @Test
    void recallAtKShouldReturnPartialRecall() {
        RecallAtK metric = new RecallAtK(5);
        TestCase tc = new TestCase("t1", "q", List.of("a.txt", "b.txt", "c.txt"), null, null);
        double score = metric.calculate("q", List.of("a.txt", "x.txt", "y.txt"), tc);
        assertThat(score).isCloseTo(1.0 / 3.0, within(0.01));
    }

    @Test
    void precisionAtKShouldReturnRatio() {
        PrecisionAtK metric = new PrecisionAtK(3);
        TestCase tc = new TestCase("t1", "q", List.of("a.txt", "b.txt"), null, null);
        double score = metric.calculate("q", List.of("a.txt", "x.txt", "y.txt"), tc);
        assertThat(score).isCloseTo(1.0 / 3.0, within(0.01));
    }

    @Test
    void mrrShouldReturnReciprocalOfFirstHit() {
        MRR metric = new MRR();
        TestCase tc = new TestCase("t1", "q", List.of("a.txt"), null, null);
        double score = metric.calculate("q", List.of("x.txt", "a.txt", "y.txt"), tc);
        assertThat(score).isEqualTo(0.5);
    }

    @Test
    void mrrShouldReturnZeroWhenNoHit() {
        MRR metric = new MRR();
        TestCase tc = new TestCase("t1", "q", List.of("a.txt"), null, null);
        double score = metric.calculate("q", List.of("x.txt", "y.txt"), tc);
        assertThat(score).isEqualTo(0.0);
    }

    @Test
    void ndcgAtKShouldBeOneWhenPerfectOrder() {
        NDCGAtK metric = new NDCGAtK(5);
        TestCase tc = new TestCase("t1", "q", List.of("a.txt", "b.txt"), null, null);
        double score = metric.calculate("q", List.of("a.txt", "b.txt", "c.txt"), tc);
        assertThat(score).isCloseTo(1.0, within(0.01));
    }

    @Test
    void ndcgAtKShouldBeLowerWhenRelevantRankedLast() {
        NDCGAtK metric = new NDCGAtK(3);
        TestCase tc = new TestCase("t1", "q", List.of("a.txt", "b.txt"), null, null);
        double good = metric.calculate("q", List.of("a.txt", "b.txt"), tc);
        double bad = metric.calculate("q", List.of("x.txt", "y.txt", "a.txt"), tc);
        assertThat(good).isGreaterThan(bad);
    }

    @Test
    void shouldMatchFilenameIgnoringPathPrefix() {
        RecallAtK metric = new RecallAtK(5);
        TestCase tc = new TestCase("t1", "q", List.of("README.md"), null, null);
        double score = metric.calculate("q", List.of("/docs/subdir/README.md"), tc);
        assertThat(score).isEqualTo(1.0);
    }

    @Test
    void shouldMatchWindowsPathPrefix() {
        RecallAtK metric = new RecallAtK(5);
        TestCase tc = new TestCase("t1", "q", List.of("guide.txt"), null, null);
        double score = metric.calculate("q", List.of("C:\\data\\guide.txt"), tc);
        assertThat(score).isEqualTo(1.0);
    }
}
```

- [ ] **Step 7: 运行测试验证通过**

```bash
mvn test -pl . -Dtest=EvaluationMetricsTest -q
```

- [ ] **Step 8: 提交**

```bash
git add src/main/java/me/maxt/rag/web/service/evaluation/EvaluationMetric.java \
        src/main/java/me/maxt/rag/web/service/evaluation/RecallAtK.java \
        src/main/java/me/maxt/rag/web/service/evaluation/PrecisionAtK.java \
        src/main/java/me/maxt/rag/web/service/evaluation/MRR.java \
        src/main/java/me/maxt/rag/web/service/evaluation/NDCGAtK.java \
        src/test/java/me/maxt/rag/web/service/evaluation/EvaluationMetricsTest.java
git commit -m "feat: 新增 4 个检索评估指标 (Recall/Precision/MRR/NDCG) 及单元测试"
```

---

### Task 5: RetrievalEvaluator 编排器

**Files:**
- Create: `src/main/java/me/maxt/rag/web/service/evaluation/RetrievalEvaluator.java`

**Interfaces:**
- Consumes: `List<EvaluationMetric>`, `EvaluationConfig.getEvaluationTopK()`
- Produces: `RetrievalEvaluator.evaluate(testCase, retrievedSourceNames)` → `Map<String, Double>`
- Consumed by: `EvaluationPipeline`

- [ ] **Step 1: 创建 RetrievalEvaluator**

```java
package me.maxt.rag.web.service.evaluation;

import me.maxt.rag.web.config.EvaluationConfig;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 检索评估编排器，对每个 TestCase 运行所有注册的指标。
 */
public class RetrievalEvaluator {

    private final List<EvaluationMetric> metrics;

    public RetrievalEvaluator(EvaluationConfig config) {
        this.metrics = new ArrayList<>();
        int k = config.getEvaluationTopK();
        metrics.add(new RecallAtK(k));
        metrics.add(new PrecisionAtK(k));
        metrics.add(new MRR());
        metrics.add(new NDCGAtK(k));
    }

    /**
     * 注册额外的自定义指标。
     */
    public void registerMetric(EvaluationMetric metric) {
        metrics.add(metric);
    }

    /**
     * 对单个测试用例运行所有指标。
     */
    public Map<String, Double> evaluate(TestCase testCase, List<String> retrievedDocNames) {
        Map<String, Double> results = new LinkedHashMap<>();
        for (EvaluationMetric metric : metrics) {
            results.put(metric.name(), metric.calculate(testCase.query(), retrievedDocNames, testCase));
        }
        return results;
    }

    /**
     * 对全部用例计算平均分数。
     */
    public Map<String, Double> aggregate(Map<String, List<Double>> allScores) {
        Map<String, Double> averages = new LinkedHashMap<>();
        for (var entry : allScores.entrySet()) {
            double avg = entry.getValue().stream().mapToDouble(Double::doubleValue).average().orElse(0.0);
            averages.put(entry.getKey(), avg);
        }
        return averages;
    }

    public List<EvaluationMetric> getMetrics() {
        return metrics;
    }
}
```

- [ ] **Step 2: 写单元测试**

创建 `src/test/java/me/maxt/rag/web/service/evaluation/RetrievalEvaluatorTest.java`：

```java
package me.maxt.rag.web.service.evaluation;

import me.maxt.rag.web.config.EvaluationConfig;
import me.maxt.rag.web.service.evaluation.RetrievalEvaluator;
import me.maxt.rag.web.service.evaluation.TestCase;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RetrievalEvaluatorTest {

    @Test
    void shouldRunAllFourMetrics() {
        EvaluationConfig config = mock(EvaluationConfig.class);
        when(config.getEvaluationTopK()).thenReturn(5);
        RetrievalEvaluator evaluator = new RetrievalEvaluator(config);
        TestCase tc = new TestCase("t1", "query", List.of("a.txt"), null, null);
        Map<String, Double> results = evaluator.evaluate(tc, List.of("a.txt"));
        assertThat(results).containsKeys("recallAtK", "precisionAtK", "mrr", "ndcgAtK");
    }

    @Test
    void shouldAggregateCorrectly() {
        EvaluationConfig config = mock(EvaluationConfig.class);
        when(config.getEvaluationTopK()).thenReturn(5);
        RetrievalEvaluator evaluator = new RetrievalEvaluator(config);
        Map<String, List<Double>> scores = Map.of(
                "recallAtK", List.of(0.8, 1.0),
                "mrr", List.of(0.5, 1.0)
        );
        Map<String, Double> avg = evaluator.aggregate(scores);
        assertThat(avg.get("recallAtK")).isEqualTo(0.9);
        assertThat(avg.get("mrr")).isEqualTo(0.75);
    }
}
```

- [ ] **Step 3: 运行测试**

```bash
mvn test -pl . -Dtest=RetrievalEvaluatorTest -q
```

- [ ] **Step 4: 提交**

```bash
git add src/main/java/me/maxt/rag/web/service/evaluation/RetrievalEvaluator.java \
        src/test/java/me/maxt/rag/web/service/evaluation/RetrievalEvaluatorTest.java
git commit -m "feat: 新增 RetrievalEvaluator 编排器及单元测试"
```

---

### Task 6: AnswerQualityMetric 接口 + AnswerQualityEvaluator

**Files:**
- Create: `src/main/java/me/maxt/rag/web/service/evaluation/AnswerQualityMetric.java`
- Create: `src/main/java/me/maxt/rag/web/service/evaluation/QualityScore.java`
- Create: `src/main/java/me/maxt/rag/web/service/evaluation/AnswerQualityEvaluator.java`

**Interfaces:**
- Produces: `AnswerQualityMetric.evaluate(query, answer, contexts, judge)` → `QualityScore`
- Consumes: `ChatModel`（复用应用 ChatModel）
- Consumed by: `EvaluationPipeline`

- [ ] **Step 1: 创建 QualityScore 记录**

```java
package me.maxt.rag.web.service.evaluation;

/**
 * LLM 答案质量评分结果。
 */
public record QualityScore(int score, String reason) {}
```

- [ ] **Step 2: 创建 AnswerQualityMetric 接口**

```java
package me.maxt.rag.web.service.evaluation;

import dev.langchain4j.model.chat.ChatModel;
import java.util.List;

/**
 * 答案质量评估指标接口。
 */
public interface AnswerQualityMetric {
    String name();
    QualityScore evaluate(String query, String answer, List<String> contexts, ChatModel judge);
}
```

- [ ] **Step 3: 创建 AnswerQualityEvaluator**

```java
package me.maxt.rag.web.service.evaluation;

import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 答案质量评估编排器，内建 Faithfulness 和 AnswerRelevancy 两个指标。
 */
public class AnswerQualityEvaluator {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private final List<AnswerQualityMetric> metrics;
    private final ChatModel judge;

    public AnswerQualityEvaluator(ChatModel judge) {
        this.judge = judge;
        this.metrics = new ArrayList<>();
        this.metrics.add(new FaithfulnessMetric());
        this.metrics.add(new AnswerRelevancyMetric());
    }

    public void registerMetric(AnswerQualityMetric metric) {
        metrics.add(metric);
    }

    public Map<String, QualityScore> evaluate(String query, String answer, List<String> contexts) {
        Map<String, QualityScore> results = new LinkedHashMap<>();
        for (AnswerQualityMetric metric : metrics) {
            results.put(metric.name(), metric.evaluate(query, answer, contexts, judge));
        }
        return results;
    }

    public boolean isAvailable() {
        return judge != null;
    }

    // --- 内建指标 ---

    static class FaithfulnessMetric implements AnswerQualityMetric {
        @Override
        public String name() { return "faithfulness"; }

        @Override
        public QualityScore evaluate(String query, String answer, List<String> contexts, ChatModel judge) {
            String ctx = String.join("\n---\n", contexts);
            String prompt = String.format(
                "根据以下上下文，评估\"答案\"中的每句话是否都能从上下文中找到依据。\n" +
                "上下文:\n%s\n\n答案: %s\n\n" +
                "请给出 1-5 分（5=完全忠实，无任何编造），并简要说明扣分原因。\n" +
                "输出格式: {\"score\": <int>, \"reason\": \"<str>\"}",
                ctx, answer
            );
            return callJudge(judge, prompt);
        }
    }

    static class AnswerRelevancyMetric implements AnswerQualityMetric {
        @Override
        public String name() { return "answerRelevancy"; }

        @Override
        public QualityScore evaluate(String query, String answer, List<String> contexts, ChatModel judge) {
            String prompt = String.format(
                "评估以下\"答案\"是否直接、完整地回答了\"问题\"。\n" +
                "问题: %s\n答案: %s\n\n" +
                "请给出 1-5 分（5=完全切题，直接完整回答了问题），并简要说明理由。\n" +
                "输出格式: {\"score\": <int>, \"reason\": \"<str>\"}",
                query, answer
            );
            return callJudge(judge, prompt);
        }
    }

    @SuppressWarnings("unchecked")
    private static QualityScore callJudge(ChatModel judge, String prompt) {
        try {
            ChatResponse resp = judge.chat(ChatRequest.builder()
                    .messages(dev.langchain4j.data.message.UserMessage.from(prompt))
                    .temperature(0.0)
                    .build());
            String text = resp.aiMessage().text();
            // 提取 JSON 对象
            int start = text.indexOf('{');
            int end = text.lastIndexOf('}');
            if (start >= 0 && end > start) {
                Map<String, Object> map = MAPPER.readValue(text.substring(start, end + 1), Map.class);
                int score = ((Number) map.get("score")).intValue();
                String reason = (String) map.get("reason");
                return new QualityScore(score, reason != null ? reason : "");
            }
        } catch (Exception e) {
            // 评估失败时返回默认值
        }
        return new QualityScore(3, "evaluation failed, default score");
    }
}
```

- [ ] **Step 4: 编译验证**

```bash
mvn compile -pl . -q
```

- [ ] **Step 5: 提交**

```bash
git add src/main/java/me/maxt/rag/web/service/evaluation/QualityScore.java \
        src/main/java/me/maxt/rag/web/service/evaluation/AnswerQualityMetric.java \
        src/main/java/me/maxt/rag/web/service/evaluation/AnswerQualityEvaluator.java
git commit -m "feat: 新增 AnswerQualityEvaluator，LLM-as-Judge 评估忠实度和答案相关性"
```

---

### Task 7: DatasetLoader

**Files:**
- Create: `src/main/java/me/maxt/rag/web/service/evaluation/DatasetLoader.java`
- Create: `src/main/java/me/maxt/rag/web/service/evaluation/DatasetFile.java`

**Interfaces:**
- Produces: `DatasetFile(format, version, created, testCases)` — JSON 解析结果
- Produces: `DatasetLoader.load(format)` → `DatasetFile`

- [ ] **Step 1: 创建 DatasetFile 记录**

```java
package me.maxt.rag.web.service.evaluation;

import java.util.List;

/**
 * testcases.json 文件的完整内容映射。
 */
public record DatasetFile(
    String format,
    String version,
    String created,
    List<TestCase> testCases
) {}
```

- [ ] **Step 2: 创建 DatasetLoader**

```java
package me.maxt.rag.web.service.evaluation;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 从 src/test/resources/evaluation/<format>/testcases.json 加载测试用例。
 */
public class DatasetLoader {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Logger log = LoggerFactory.getLogger(DatasetLoader.class);

    private static final String RESOURCE_ROOT = "evaluation";

    /**
     * 加载指定格式的测试用例。
     *
     * @param format 格式名（如 "markdown"）
     * @return 解析后的 DatasetFile，或 null（文件不存在或解析失败时）
     */
    public DatasetFile load(String format) {
        String resourcePath = RESOURCE_ROOT + "/" + format + "/testcases.json";
        try (InputStream in = getClass().getClassLoader().getResourceAsStream(resourcePath)) {
            if (in == null) {
                log.warn("测试用例文件不存在: {}", resourcePath);
                return null;
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> root = MAPPER.readValue(in, Map.class);
            return parseDataset(format, root);
        } catch (Exception e) {
            log.error("加载测试用例失败: {} — {}", resourcePath, e.getMessage());
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    private DatasetFile parseDataset(String format, Map<String, Object> root) {
        String version = (String) root.getOrDefault("version", "1.0");
        String created = (String) root.getOrDefault("created", "");
        List<Map<String, Object>> cases = (List<Map<String, Object>>) root.get("testCases");
        List<TestCase> testCases = new ArrayList<>();
        if (cases != null) {
            for (Map<String, Object> c : cases) {
                testCases.add(new TestCase(
                    (String) c.get("id"),
                    (String) c.get("query"),
                    (List<String>) c.get("relevantDocs"),
                    (List<String>) c.get("relevantContent"),
                    (String) c.get("expectedAnswer")
                ));
            }
        }
        return new DatasetFile(format, version, created, testCases);
    }

    /**
     * 校验测试用例字段完整性。
     */
    public List<String> validate(DatasetFile dataset) {
        List<String> errors = new ArrayList<>();
        for (TestCase tc : dataset.testCases()) {
            if (tc.id() == null || tc.id().isBlank()) errors.add("缺少 id");
            if (tc.query() == null || tc.query().isBlank()) errors.add(tc.id() + ": 缺少 query");
            if (tc.relevantDocs() == null || tc.relevantDocs().isEmpty())
                errors.add(tc.id() + ": 缺少 relevantDocs");
        }
        return errors;
    }
}
```

- [ ] **Step 3: 写单元测试**

创建 `src/test/resources/evaluation/markdown/testcases.json`（最小测试数据）：

```json
{
  "format": "markdown",
  "version": "1.0",
  "testCases": [
    {
      "id": "md-001",
      "query": "测试问题",
      "relevantDocs": ["test.md"]
    }
  ]
}
```

创建 `src/test/java/me/maxt/rag/web/service/evaluation/DatasetLoaderTest.java`：

```java
package me.maxt.rag.web.service.evaluation;

import org.junit.jupiter.api.Test;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;

class DatasetLoaderTest {

    @Test
    void shouldLoadTestCasesFromJson() {
        DatasetLoader loader = new DatasetLoader();
        DatasetFile ds = loader.load("markdown");
        assertThat(ds).isNotNull();
        assertThat(ds.format()).isEqualTo("markdown");
        assertThat(ds.testCases()).hasSize(1);
        assertThat(ds.testCases().get(0).id()).isEqualTo("md-001");
    }

    @Test
    void shouldReturnNullForMissingFormat() {
        DatasetLoader loader = new DatasetLoader();
        DatasetFile ds = loader.load("nonexistent");
        assertThat(ds).isNull();
    }

    @Test
    void shouldValidateMissingFields() {
        DatasetLoader loader = new DatasetLoader();
        TestCase bad = new TestCase("", null, List.of(), null, null);
        DatasetFile ds = new DatasetFile("test", "1.0", "", List.of(bad));
        List<String> errors = loader.validate(ds);
        assertThat(errors).isNotEmpty();
    }
}
```

- [ ] **Step 4: 运行测试**

```bash
mvn test -pl . -Dtest=DatasetLoaderTest -q
```

- [ ] **Step 5: 提交**

```bash
git add src/main/java/me/maxt/rag/web/service/evaluation/DatasetFile.java \
        src/main/java/me/maxt/rag/web/service/evaluation/DatasetLoader.java \
        src/test/java/me/maxt/rag/web/service/evaluation/DatasetLoaderTest.java \
        src/test/resources/evaluation/markdown/testcases.json
git commit -m "feat: 新增 DatasetLoader，加载解析 testcases.json 并校验字段完整性"
```

---

### Task 8: KnowledgeBaseSeeder

**Files:**
- Create: `src/main/java/me/maxt/rag/web/service/evaluation/KnowledgeBaseSeeder.java`

**Interfaces:**
- Consumes: `EmbeddingStoreManager`, `EmbeddingModel`, `DocumentConfig`
- Produces: `KnowledgeBaseSeeder.seed(format)` → 将 docs/ 下文档入库
- Consumed by: `EvaluationPipeline`

- [ ] **Step 1: 创建 KnowledgeBaseSeeder**

```java
package me.maxt.rag.web.service.evaluation;

import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.document.loader.FileSystemDocumentLoader;
import dev.langchain4j.data.document.parser.apache.tika.ApacheTikaDocumentParser;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.store.embedding.inmemory.InMemoryEmbeddingStore;
import me.maxt.rag.web.config.DocumentConfig;
import me.maxt.rag.web.service.EmbeddingStoreManager;
import me.maxt.rag.web.service.chunking.ChunkingPipeline;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

/**
 * 将评估用文档走完整切分→向量化→入库管线。
 * 使用独立的 InMemoryEmbeddingStore，不污染生产数据。
 */
public class KnowledgeBaseSeeder {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeBaseSeeder.class);

    private final EmbeddingStoreManager storeManager;
    private final EmbeddingModel embeddingModel;
    private final DocumentConfig docConfig;
    private final ChunkingPipeline chunkingPipeline;

    public KnowledgeBaseSeeder(EmbeddingStoreManager storeManager, EmbeddingModel embeddingModel,
                               DocumentConfig docConfig, ChunkingPipeline chunkingPipeline) {
        this.storeManager = storeManager;
        this.embeddingModel = embeddingModel;
        this.docConfig = docConfig;
        this.chunkingPipeline = chunkingPipeline;
    }

    /**
     * 将指定格式的 docs/ 目录下所有文档入库。
     *
     * @param format 格式名（如 "markdown"）
     * @return 成功入库的文档数
     */
    public int seed(String format) {
        // 使用 classpath 资源路径解析 docs 目录
        String docsResource = "evaluation/" + format + "/docs";
        var classLoader = getClass().getClassLoader();
        var url = classLoader.getResource(docsResource);
        if (url == null) {
            log.warn("评估文档目录不存在: {}", docsResource);
            return 0;
        }

        Path docsPath;
        try {
            docsPath = Paths.get(url.toURI());
        } catch (Exception e) {
            log.error("无法解析文档路径: {}", docsResource, e);
            return 0;
        }

        File dir = docsPath.toFile();
        if (!dir.exists() || !dir.isDirectory()) {
            log.warn("评估文档目录无效: {}", docsPath);
            return 0;
        }

        // 使用 Tika 解析器加载所有文档
        List<Document> documents = FileSystemDocumentLoader.loadDocuments(
                docsPath, new ApacheTikaDocumentParser());

        int count = 0;
        for (Document doc : documents) {
            try {
                List<TextSegment> chunks;
                if (chunkingPipeline != null) {
                    chunks = chunkingPipeline.chunk(doc);
                } else {
                    // 降级：简单按段落切分
                    chunks = List.of(TextSegment.from(doc.text()));
                }

                for (TextSegment chunk : chunks) {
                    Embedding embedding = embeddingModel.embed(chunk.text()).content();
                    storeManager.add(embedding, chunk);
                }
                count++;
            } catch (Exception e) {
                log.error("文档 {} 入库失败: {}", doc.metadata().get("file_name"), e.getMessage());
            }
        }
        log.info("评估知识库入库完成: {} 个文档 (格式: {})", count, format);
        return count;
    }
}
```

- [ ] **Step 2: 编译验证**

```bash
mvn compile -pl . -q
```

- [ ] **Step 3: 提交**

```bash
git add src/main/java/me/maxt/rag/web/service/evaluation/KnowledgeBaseSeeder.java
git commit -m "feat: 新增 KnowledgeBaseSeeder，将评估文档入库到隔离的 InMemory 存储"
```

---

### Task 9: BaselineManager

**Files:**
- Create: `src/main/java/me/maxt/rag/web/service/evaluation/BaselineManager.java`

**Interfaces:**
- Consumes: `EvaluationConfig.getDegradationThreshold()`
- Produces: `BaselineManager.loadOrCreate(format)` → `EvaluationReport`（基线）
- Produces: `BaselineManager.save(report, path)` → void
- Produces: `BaselineManager.compare(current, baseline, threshold)` → `List<Degradation>`
- Consumed by: `EvaluationPipeline`

- [ ] **Step 1: 创建 BaselineManager**

```java
package me.maxt.rag.web.service.evaluation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import me.maxt.rag.web.config.EvaluationConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * 基线管理器：加载/保存基线、对比当前分数与基线、输出报告。
 */
public class BaselineManager {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .enable(SerializationFeature.INDENT_OUTPUT);
    private static final Logger log = LoggerFactory.getLogger(BaselineManager.class);
    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");

    private final double degradationThreshold;

    public BaselineManager(EvaluationConfig config) {
        this.degradationThreshold = config.getDegradationThreshold();
    }

    /**
     * 加载基线或标记为首次运行。
     * <p>src/test/resources 下有 baseline.json → 复制到 target 并返回；
     * 无 → 返回 null（首次运行，结束后需写回 src/test/resources）。
     */
    public EvaluationReport loadOrCreate(String format, Path srcResourcesDir, Path targetDir) {
        Path srcBaseline = srcResourcesDir.resolve(format).resolve("baseline.json");
        Path targetFormatDir = targetDir.resolve(format);
        Path targetBaseline = targetFormatDir.resolve("baseline.json");

        if (Files.exists(srcBaseline)) {
            try {
                Files.createDirectories(targetFormatDir);
                Files.copy(srcBaseline, targetBaseline, StandardCopyOption.REPLACE_EXISTING);
                EvaluationReport baseline = MAPPER.readValue(srcBaseline.toFile(), EvaluationReport.class);
                log.info("加载基线: {} (时间: {})", format, baseline.timestamp);
                return baseline;
            } catch (IOException e) {
                log.error("加载基线失败: {}", srcBaseline, e);
            }
        }
        log.info("{} 基线不存在，标记为首次运行", format);
        return null;
    }

    /**
     * 保存报告到指定路径。
     */
    public void save(EvaluationReport report, Path filePath) {
        try {
            Files.createDirectories(filePath.getParent());
            MAPPER.writeValue(filePath.toFile(), report);
        } catch (IOException e) {
            log.error("保存报告失败: {}", filePath, e);
        }
    }

    /**
     * 将当前分数与基线对比，返回退化列表。
     */
    public List<EvaluationReport.Degradation> compare(EvaluationReport current, EvaluationReport baseline) {
        List<EvaluationReport.Degradation> degradations = new ArrayList<>();
        compareMetrics(current.metrics, baseline.metrics, degradations);
        if (current.answerQuality != null && baseline.answerQuality != null) {
            compareMetrics(current.answerQuality, baseline.answerQuality, degradations);
        }
        return degradations;
    }

    private void compareMetrics(java.util.Map<String, EvaluationReport.MetricScore> current,
                                 java.util.Map<String, EvaluationReport.MetricScore> baseline,
                                 List<EvaluationReport.Degradation> degradations) {
        for (var entry : current.entrySet()) {
            String name = entry.getKey();
            double curScore = entry.getValue().score;
            var baseScore = baseline.get(name);
            if (baseScore == null) continue;
            double delta = curScore - baseScore.score;
            if (delta < -degradationThreshold) {
                var d = new EvaluationReport.Degradation();
                d.metric = name;
                d.baseline = baseScore.score;
                d.current = curScore;
                d.delta = delta;
                degradations.add(d);
                log.warn("指标退化: {} {} → {} (Δ={})", name, baseScore.score, curScore, String.format("%.3f", delta));
            }
        }
    }

    /**
     * 生成时间戳字符串。
     */
    public static String timestamp() {
        return LocalDateTime.now().format(FMT);
    }

    /**
     * 判定整体状态。
     */
    public static String overallStatus(List<EvaluationReport.Degradation> degradations) {
        if (degradations.isEmpty()) return "pass";
        boolean hasSignificant = degradations.stream().anyMatch(d -> d.delta < -0.05);
        return hasSignificant ? "degraded" : "warning";
    }
}
```

- [ ] **Step 2: 写单元测试**

创建 `src/test/java/me/maxt/rag/web/service/evaluation/BaselineManagerTest.java`：

```java
package me.maxt.rag.web.service.evaluation;

import me.maxt.rag.web.config.EvaluationConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class BaselineManagerTest {

    @Test
    void shouldReturnNullWhenNoBaselineExists(@TempDir Path srcDir, @TempDir Path targetDir) {
        EvaluationConfig config = mock(EvaluationConfig.class);
        when(config.getDegradationThreshold()).thenReturn(0.05);
        BaselineManager bm = new BaselineManager(config);
        EvaluationReport result = bm.loadOrCreate("markdown", srcDir, targetDir);
        assertThat(result).isNull();
    }

    @Test
    void shouldDetectSignificantDegradation() {
        EvaluationConfig config = mock(EvaluationConfig.class);
        when(config.getDegradationThreshold()).thenReturn(0.05);
        BaselineManager bm = new BaselineManager(config);

        EvaluationReport current = new EvaluationReport();
        current.metrics = Map.of("recallAtK", new EvaluationReport.MetricScore(0.70));

        EvaluationReport baseline = new EvaluationReport();
        baseline.metrics = Map.of("recallAtK", new EvaluationReport.MetricScore(0.80));

        var degradations = bm.compare(current, baseline);
        assertThat(degradations).hasSize(1);
        assertThat(degradations.get(0).metric).isEqualTo("recallAtK");
        assertThat(degradations.get(0).delta).isEqualTo(-0.10);
    }

    @Test
    void shouldNotFlagSmallDegradation() {
        EvaluationConfig config = mock(EvaluationConfig.class);
        when(config.getDegradationThreshold()).thenReturn(0.05);
        BaselineManager bm = new BaselineManager(config);

        EvaluationReport current = new EvaluationReport();
        current.metrics = Map.of("recallAtK", new EvaluationReport.MetricScore(0.77));

        EvaluationReport baseline = new EvaluationReport();
        baseline.metrics = Map.of("recallAtK", new EvaluationReport.MetricScore(0.80));

        var degradations = bm.compare(current, baseline);
        assertThat(degradations).isEmpty();
    }
}
```

- [ ] **Step 3: 运行测试**

```bash
mvn test -pl . -Dtest=BaselineManagerTest -q
```

- [ ] **Step 4: 提交**

```bash
git add src/main/java/me/maxt/rag/web/service/evaluation/BaselineManager.java \
        src/test/java/me/maxt/rag/web/service/evaluation/BaselineManagerTest.java
git commit -m "feat: 新增 BaselineManager，基线的加载/保存/对比/退化判定"
```

---

### Task 10: EvaluationPipeline 编排入口

**Files:**
- Create: `src/main/java/me/maxt/rag/web/service/evaluation/EvaluationPipeline.java`

**Interfaces:**
- Consumes: DatasetLoader, KnowledgeBaseSeeder, RetrievalEvaluator, AnswerQualityEvaluator, BaselineManager, RAGService, EvaluationConfig
- Produces: `EvaluationPipeline.run(format)` → `EvaluationReport`（汇总报告）
- Consumed by: `EvaluationTest`

- [ ] **Step 1: 创建 EvaluationPipeline**

```java
package me.maxt.rag.web.service.evaluation;

import me.maxt.rag.web.config.EvaluationConfig;
import me.maxt.rag.web.service.RAGService;
import me.maxt.rag.web.service.RAGService.AnswerWithSources;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.nio.file.Paths;
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
```

- [ ] **Step 2: 编译验证**

```bash
mvn compile -pl . -q
```

- [ ] **Step 3: 提交**

```bash
git add src/main/java/me/maxt/rag/web/service/evaluation/EvaluationPipeline.java
git commit -m "feat: 新增 EvaluationPipeline，串联完整评估流程"
```

---

### Task 11: Maven Profile + 测试数据骨架 + EvaluationTest

**Files:**
- Modify: `pom.xml`
- Create: `src/test/resources/evaluation/config.json`
- Create: `src/test/resources/evaluation/txt/testcases.json`
- Create: `src/test/resources/evaluation/pdf/testcases.json`
- Create: `src/test/resources/evaluation/docx/testcases.json`
- Create: `src/test/resources/evaluation/json/testcases.json`
- Create: `src/test/java/me/maxt/rag/web/evaluation/EvaluationTest.java`

**Interfaces:**
- Consumes: EvaluationPipeline, EvaluationConfig

- [ ] **Step 1: 添加 Maven evaluation profile**

在 `pom.xml` 的 `<project>` 内、`</project>` 前添加：

```xml
<profiles>
  <profile>
    <id>evaluation</id>
    <build>
      <plugins>
        <plugin>
          <groupId>org.apache.maven.plugins</groupId>
          <artifactId>maven-surefire-plugin</artifactId>
          <configuration>
            <includes>
              <include>**/evaluation/EvaluationTest.java</include>
            </includes>
            <systemPropertyVariables>
              <evaluation.updateBaseline>${updateBaseline}</evaluation.updateBaseline>
              <evaluation.format>${format}</evaluation.format>
              <evaluation.skipAnswerQuality>${skipAnswerQuality}</evaluation.skipAnswerQuality>
            </systemPropertyVariables>
          </configuration>
        </plugin>
      </plugins>
    </build>
  </profile>
</profiles>
```

- [ ] **Step 2: 创建全局评估配置**

`src/test/resources/evaluation/config.json`：

```json
{
  "topK": 5,
  "formats": ["markdown", "txt", "pdf", "docx", "json"],
  "evaluation": {
    "answerQualityEnabled": true,
    "degradationThreshold": 0.05
  }
}
```

- [ ] **Step 3: 创建各格式的最小 testcases.json**

`src/test/resources/evaluation/txt/testcases.json`：
```json
{ "format": "txt", "version": "1.0", "testCases": [] }
```

`src/test/resources/evaluation/pdf/testcases.json`：
```json
{ "format": "pdf", "version": "1.0", "testCases": [] }
```

`src/test/resources/evaluation/docx/testcases.json`：
```json
{ "format": "docx", "version": "1.0", "testCases": [] }
```

`src/test/resources/evaluation/json/testcases.json`：
```json
{ "format": "json", "version": "1.0", "testCases": [] }
```

> 注意：各格式的 `docs/` 目录和真实测试用例在后续使用时手动补充；markdown 格式已在 Task 7 中创建了含一条用例的 testcases.json。

- [ ] **Step 4: 创建 JUnit 5 评估测试入口**

`src/test/java/me/maxt/rag/web/evaluation/EvaluationTest.java`：

```java
package me.maxt.rag.web.evaluation;

import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.ChatResponseMetadata;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.output.Response;
import dev.langchain4j.model.output.TokenUsage;
import dev.langchain4j.store.embedding.inmemory.InMemoryEmbeddingStore;
import me.maxt.rag.web.config.AppConfig;
import me.maxt.rag.web.config.EvaluationConfig;
import me.maxt.rag.web.service.EmbeddingStoreManager;
import me.maxt.rag.web.service.RAGService;
import me.maxt.rag.web.service.chunking.ChunkingPipeline;
import me.maxt.rag.web.service.evaluation.*;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 评估测试入口。
 *
 * <p>命令：
 * <ul>
 *   <li>mvn test -P evaluation</li>
 *   <li>mvn test -P evaluation -Dformat=markdown</li>
 *   <li>mvn test -P evaluation -DupdateBaseline=true</li>
 *   <li>mvn test -P evaluation -DskipAnswerQuality=true</li>
 * </ul>
 */
class EvaluationTest {

    private static final Path SRC_RESOURCES = Paths.get("src/test/resources/evaluation");
    private static final Path TARGET_DIR = Paths.get("target/evaluation");

    @Test
    void runEvaluation() {
        String formatFilter = System.getProperty("evaluation.format");
        boolean updateBaseline = "true".equals(System.getProperty("evaluation.updateBaseline"));
        boolean skipAnswerQuality = "true".equals(System.getProperty("evaluation.skipAnswerQuality"));

        // 构建最小依赖（mock 系统边界）
        AppConfig appConfig = new AppConfig();
        EmbeddingStoreManager storeManager = new EmbeddingStoreManager(new InMemoryEmbeddingStore<>());

        // Mock embedding model
        EmbeddingModel embeddingModel = mock(EmbeddingModel.class);
        Response<Embedding> embedResp = mock(Response.class);
        when(embedResp.content()).thenReturn(Embedding.from(new float[]{0.5f, 0.5f, 0.5f}));
        when(embeddingModel.embed(any(String.class))).thenReturn(embedResp);

        // Mock chat model
        ChatModel chatModel = mock(ChatModel.class);
        ChatResponseMetadata metadata = ChatResponseMetadata.builder()
                .tokenUsage(new TokenUsage(10, 10)).build();
        ChatResponse chatResponse = ChatResponse.builder()
                .aiMessage(dev.langchain4j.data.message.AiMessage.from("stub answer"))
                .metadata(metadata).build();
        when(chatModel.chat(any(ChatRequest.class))).thenReturn(chatResponse);

        // 构建 RAGService（最简构造器）
        RAGService ragService = new RAGService(appConfig, storeManager, embeddingModel, chatModel);

        // 构建评估组件
        DatasetLoader datasetLoader = new DatasetLoader();
        KnowledgeBaseSeeder seeder = new KnowledgeBaseSeeder(storeManager, embeddingModel, appConfig, null);
        RetrievalEvaluator retrievalEvaluator = new RetrievalEvaluator(appConfig);
        AnswerQualityEvaluator answerQualityEvaluator = new AnswerQualityEvaluator(chatModel);
        BaselineManager baselineManager = new BaselineManager(appConfig);

        EvaluationPipeline pipeline = new EvaluationPipeline(appConfig, datasetLoader, seeder,
                retrievalEvaluator, answerQualityEvaluator, baselineManager, ragService);

        List<String> formats = appConfig.getEvaluationFormats();
        if (formatFilter != null && !formatFilter.isBlank()) {
            formats = List.of(formatFilter);
        }

        for (String format : formats) {
            EvaluationReport report = pipeline.run(format, SRC_RESOURCES, TARGET_DIR,
                    updateBaseline, skipAnswerQuality);
            if (report != null) {
                System.out.println("[" + format + "] recallAtK=" +
                        report.metrics.get("recallAtK").score);
            }
        }
    }
}
```

- [ ] **Step 5: 验证 profile 编译**

```bash
mvn test-compile -P evaluation -q
```

- [ ] **Step 6: 提交**

```bash
git add pom.xml \
        src/test/resources/evaluation/config.json \
        src/test/resources/evaluation/txt/testcases.json \
        src/test/resources/evaluation/pdf/testcases.json \
        src/test/resources/evaluation/docx/testcases.json \
        src/test/resources/evaluation/json/testcases.json \
        src/test/java/me/maxt/rag/web/evaluation/EvaluationTest.java
git commit -m "feat: 新增 Maven evaluation profile、测试数据骨架、EvaluationTest 入口"
```

---

### Task 12: 集成验证

**Files:**
- （无新增文件，验证所有组件可编译、所有单元测试通过）

- [ ] **Step 1: 运行全部单元测试**

```bash
mvn test -q
```

预期：所有 107+ 个已有测试 + 新增评估测试全部通过。

- [ ] **Step 2: 运行 evaluation profile 验证**

```bash
mvn test -P evaluation -DskipAnswerQuality=true -q
```

预期：评估管线启动、加载 markdown 测试用例、输出评估报告到 `target/evaluation/`。

- [ ] **Step 3: 验证基线生成**

```bash
# 删除已有基线（如有）
rm -f src/test/resources/evaluation/markdown/baseline.json

# 首次运行生成基线
mvn test -P evaluation -Dformat=markdown -DskipAnswerQuality=true
```

验证：`src/test/resources/evaluation/markdown/baseline.json` 已创建，内容格式正确。

- [ ] **Step 4: 验证基线对比**

```bash
# 再次运行，应加载基线并对比
mvn test -P evaluation -Dformat=markdown -DskipAnswerQuality=true
```

验证：日志输出"加载基线"和"对比基线无退化"或报告退化。

- [ ] **Step 4: 提交并运行全部测试**

```bash
mvn test -q
git add .
git commit -m "验证: 评估管线集成测试通过，基线生成/加载/对比正常"
```
