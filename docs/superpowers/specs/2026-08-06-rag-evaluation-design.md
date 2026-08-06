# RAG 效果自动化评估设计

2026-08-06

## 目标

建立 RAG 效果自动化评估体系：五种文档格式独立测试基线、检索质量 + 答案质量双维度评估、纯 Java 实现零额外依赖、Maven profile 隔离运行。

## 架构

```
EvaluationPipeline (评估管线入口)
  ├── DatasetLoader        → 加载 testcases.json，校验字段完整性
  ├── KnowledgeBaseSeeder   → 将 docs/ 下的文档走完整切分→向量化→入库管线
  ├── RetrievalEvaluator    → Recall@K, Precision@K, MRR, NDCG（纯数学）
  ├── AnswerQualityEvaluator → LLM-as-Judge (Faithfulness, AnswerRelevancy)
  └── BaselineManager       → 基线保存/加载/对比/报告
```

全部 Java 实现，复用现有 `EmbeddingStoreManager`、`ChatModel`、`ChunkingPipeline`。不依赖 Python。

## 目录结构

```
src/test/resources/evaluation/
├── markdown/
│   ├── docs/              ← MD 知识库文档
│   ├── testcases.json     ← QA 测试用例
│   └── baseline.json      ← 基线分数（首次运行生成，提交 git）
├── txt/
│   ├── docs/
│   ├── testcases.json
│   └── baseline.json
├── pdf/
│   ├── docs/
│   ├── testcases.json
│   └── baseline.json
├── docx/
│   ├── docs/
│   ├── testcases.json
│   └── baseline.json
├── json/
│   ├── docs/              ← JSON 结构化知识库文档
│   ├── testcases.json
│   └── baseline.json
└── config.json            ← 全局评估配置

target/evaluation/         ← 运行时输出（gitignore）
├── markdown/
│   ├── baseline.json      ← 从 src/test/resources 复制
│   └── <timestamp>.json   ← 每次运行的时间戳报告
├── txt/...
└── summary.json           ← 全格式汇总 + 退化对比
```

## config.json 格式

```json
{
  "topK": 5,
  "formats": ["markdown", "txt", "pdf", "docx", "json"],
  "evaluation": {
    "answerQualityEnabled": true,
    "llmJudgeModel": null,
    "degradationThreshold": 0.05
  }
}
```

| 字段 | 默认值 | 说明 |
|------|--------|------|
| `topK` | 5 | Recall@K / Precision@K / NDCG@K 的 K 值 |
| `formats` | 全部 | 启用的文档格式列表 |
| `answerQualityEnabled` | true | 是否默认启用 LLM 答案质量评估 |
| `llmJudgeModel` | null | 评估专用模型名，null 则复用应用 ChatModel |
| `degradationThreshold` | 0.05 | 退化判定阈值（5%） |

## testcases.json 格式

```json
{
  "format": "markdown",
  "version": "1.0",
  "created": "2026-08-06",
  "testCases": [
    {
      "id": "md-001",
      "query": "如何安装这个项目？",
      "relevantDocs": ["README.md", "install-guide.md"],
      "relevantContent": [
        "mvn clean package 构建 Fat JAR",
        "docker compose up -d 启动 Milvus"
      ],
      "expectedAnswer": "通过 mvn clean package 构建，然后 docker compose up -d 启动 Milvus，最后 java -jar 启动应用。"
    }
  ]
}
```

| 字段 | 用途 | 必填 |
|------|------|------|
| `id` | 用例唯一标识 | ✅ |
| `query` | 测试问题 | ✅ |
| `relevantDocs` | 期望命中的文档名列表，用于 Recall/Precision | ✅ |
| `relevantContent` | 期望命中的关键内容片段，用于细粒度检索评估 | 可选 |
| `expectedAnswer` | 参考答案，用于 AnswerQuality 评估 | 可选 |

`relevantContent` 和 `expectedAnswer` 不填时，对应评估指标自动跳过。

## 检索评估指标

四个指标，全部纯数学计算，不调 LLM，零 token 消耗。

### 指标接口（可扩展）

```java
public interface EvaluationMetric {
    String name();
    double calculate(String query, List<String> retrievedDocNames, TestCase groundTruth);
}
```

### 内置实现

| 指标 | 计算逻辑 | 说明 |
|------|---------|------|
| `RecallAtK` | `|检索结果 ∩ 相关文档| / |相关文档|` | 相关文档有多少被找到。K 取配置 topK |
| `PrecisionAtK` | `|检索结果 ∩ 相关文档| / K` | 检索结果中有多少是相关的 |
| `MRR` | `1 / 第一个相关结果的排名` | 正确答案排在第几位，越靠前越好 |
| `NDCGAtK` | `DCG / IDCG` | 排序位置加权的归一化折损累计增益 |

后续新增指标只需实现 `EvaluationMetric` 接口并注册到 `RetrievalEvaluator`。

**相关文档判定**：检索结果的文件名与 `relevantDocs` 中任一文件名匹配（忽略路径前缀）。

## 答案质量评估（LLM-as-Judge）

### 两个维度

**Faithfulness（忠实度）** — 答案是否严格基于检索到的上下文：

```
根据以下上下文，评估"答案"中的每句话是否都能从上下文中找到依据。
上下文: {retrieved_chunks}
答案: {generated_answer}
请给出 1-5 分（5=完全忠实，无任何编造），并简要说明扣分原因。
输出格式: {"score": <int>, "reason": "<str>"}
```

**Answer Relevancy（答案相关性）** — 答案是否直接回答了用户问题：

```
评估以下"答案"是否直接、完整地回答了"问题"。
问题: {query}
答案: {generated_answer}
请给出 1-5 分（5=完全切题，直接完整回答了问题），并简要说明理由。
输出格式: {"score": <int>, "reason": "<str>"}
```

### 调用策略

- 复用项目现有 `ChatModel`，temperature=0 确保评定稳定
- 两个 prompt 串行调用
- 答案实时生成：每个测试用例调 `RAGService.answerWithSources()` 跑完整管线
- `expectedAnswer` 不填时跳过答案质量评估

### 评估接口（可扩展）

```java
public interface AnswerQualityMetric {
    String name();
    QualityScore evaluate(String query, String answer, List<String> contexts, ChatModel judge);
}

public record QualityScore(int score, String reason) {}
```

## 基线管理

### 基线生命周期

```
首次评估 (src/test/resources 下无 baseline.json)
  → 跑评估，生成分数
  → 写入 src/test/resources/evaluation/<format>/baseline.json  ← 提交 git
  → 同时复制到 target/evaluation/<format>/baseline.json

后续评估 (src/test/resources 下已有 baseline.json)
  → 复制到 target/evaluation/<format>/baseline.json
  → 跑评估，当前分数 vs 基线对比
  → 输出时间戳报告到 target/evaluation/<format>/<timestamp>.json
```

基线锁定策略：
- 首次运行生成基线后，之后不再自动更新
- 手动更新基线：`mvn test -P evaluation -DupdateBaseline`（覆盖 src/test/resources 下的基线文件）
- 基线文件随源码一起提交 git，换环境 clone 即可使用
- 不会因分数更优自动更新，避免基线漂移

### 对比判定

| 变化 | 判定 |
|------|------|
| 当前分数 >= 基线分数 | ✅ 持平或改善 |
| 当前分数 < 基线分数，差距 <= 5% | ⚠️ 轻微退化 |
| 当前分数 < 基线分数，差距 > 5% | ❌ 显著退化 |

## 报告格式

### baseline.json / 时间戳报告

```json
{
  "format": "markdown",
  "version": "1.0",
  "timestamp": "2026-08-06T15:00:00",
  "type": "baseline",
  "metrics": {
    "recallAtK": { "score": 0.82, "details": { "passed": 8, "total": 10 } },
    "precisionAtK": { "score": 0.75, "details": { "passed": 8, "total": 10 } },
    "mrr": { "score": 0.88 },
    "ndcgAtK": { "score": 0.79 }
  },
  "answerQuality": {
    "faithfulness": { "score": 4.2, "scale": "1-5" },
    "answerRelevancy": { "score": 4.5, "scale": "1-5" }
  },
  "perTestCase": [
    {
      "id": "md-001",
      "recallAtK": 1.0,
      "precisionAtK": 0.5,
      "mrr": 1.0,
      "ndcgAtK": 1.0,
      "faithfulness": 5,
      "answerRelevancy": 5
    }
  ]
}
```

### summary.json（全格式汇总）

```json
{
  "timestamp": "2026-08-06T15:00:00",
  "baselineTimestamp": "2026-08-06T14:00:00",
  "formats": [
    {
      "format": "markdown",
      "status": "pass",
      "degradations": []
    },
    {
      "format": "pdf",
      "status": "degraded",
      "degradations": [
        { "metric": "recallAtK", "baseline": 0.80, "current": 0.72, "delta": -0.08 }
      ]
    }
  ],
  "overallStatus": "degraded"
}
```

## Maven Profile

```xml
<profile>
  <id>evaluation</id>
  <build>
    <plugins>
      <plugin>
        <artifactId>maven-surefire-plugin</artifactId>
        <configuration>
          <includes>
            <include>**/evaluation/*Test.java</include>
          </includes>
        </configuration>
      </plugin>
    </plugins>
  </build>
</profile>
```

### 命令

| 命令 | 用途 |
|------|------|
| `mvn test -P evaluation` | 跑全部格式评估，对比基线 |
| `mvn test -P evaluation -Dformat=markdown` | 只跑一种格式 |
| `mvn test -P evaluation -DupdateBaseline` | 更新基线（首次或重置） |
| `mvn test -P evaluation -DskipAnswerQuality` | 跳过 LLM 评估，只跑检索指标 |

## 数据流

```
mvn test -P evaluation
  │
  ├─→ BaselineManager.loadOrCreate(format)
  │     ├─ src/test/resources 有 baseline.json → 复制到 target
  │     └─ 无 → 标记为首次运行（结束后写入 src/test/resources）
  │
  ├─→ KnowledgeBaseSeeder.seed(format)
  │     └─ 遍历 docs/* → ChunkingPipeline → EmbeddingModel → EmbeddingStoreManager
  │
  ├─→ for each TestCase in testcases.json:
  │     ├─ RAGService.answerWithSources(query)  → answer + sources
  │     ├─ RetrievalEvaluator.evaluate(query, sources, testCase)
  │     └─ AnswerQualityEvaluator.evaluate(query, answer, contexts)  [可跳过]
  │
  └─→ BaselineManager.report(currentScores, baselineScores)
        ├─ target/evaluation/<format>/<timestamp>.json
        └─ target/evaluation/summary.json
```

评估使用 `InMemoryEmbeddingStore`（隔离的临时存储），不与生产 Milvus 数据混合。

## 文件清单

| 操作 | 文件 | 职责 |
|------|------|------|
| 新增 | `service/evaluation/EvaluationPipeline.java` | 评估管线编排入口 |
| 新增 | `service/evaluation/DatasetLoader.java` | 加载解析 testcases.json |
| 新增 | `service/evaluation/KnowledgeBaseSeeder.java` | 文档入库（切分→向量化→存储） |
| 新增 | `service/evaluation/EvaluationMetric.java` | 检索指标接口 |
| 新增 | `service/evaluation/RecallAtK.java` | Recall@K 实现 |
| 新增 | `service/evaluation/PrecisionAtK.java` | Precision@K 实现 |
| 新增 | `service/evaluation/MRR.java` | MRR 实现 |
| 新增 | `service/evaluation/NDCGAtK.java` | NDCG@K 实现 |
| 新增 | `service/evaluation/RetrievalEvaluator.java` | 编排检索指标计算 |
| 新增 | `service/evaluation/AnswerQualityMetric.java` | 答案质量指标接口 |
| 新增 | `service/evaluation/AnswerQualityEvaluator.java` | LLM-as-Judge 编排 |
| 新增 | `service/evaluation/BaselineManager.java` | 基线加载/保存/对比/报告 |
| 新增 | `service/evaluation/EvaluationReport.java` | 报告 DTO（baseline/timestamp/summary） |
| 新增 | `service/evaluation/TestCase.java` | 测试用例 DTO |
| 新增 | `config/EvaluationConfig.java` | 评估配置接口 |
| 修改 | `config/AppConfig.java` | 实现 EvaluationConfig |
| 修改 | `pom.xml` | 新增 evaluation profile |
| 新增 | `src/test/resources/evaluation/*/` | 各格式测试数据 |
| 新增 | `src/test/resources/evaluation/config.json` | 全局评估配置 |
| 新增 | `evaluation/EvaluationTest.java` | JUnit 5 评估测试入口 |

## 测试

- `DatasetLoaderTest`：校验 JSON 解析、字段完整性检查
- `RetrievalEvaluatorTest`：mock 检索结果，验证四个指标计算正确性
- `BaselineManagerTest`：验证基线加载/保存/对比/退化判定逻辑
- `EvaluationPipelineTest`：mock 各组件，验证编排流程
