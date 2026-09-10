# 检索编排深化（候选 3）验证报告

- **日期**：2026-09-10
- **代码基线**：分支 `retrieval-pipeline`，HEAD `0c0fc5a`（Task 1-6 全部落地），提交区间 `f4936e0..`（含本任务文档收尾提交）
- **设计文档**：`docs/superpowers/specs/2026-09-10-retrieval-pipeline-design.md`
- **验证人**：Task 7（端到端验证 + 基线重建 + 文档收尾）

## 一、全量测试（Step 1）

命令：`mvn test`

```
[INFO] Tests run: 170, Failures: 0, Errors: 0, Skipped: 0
[INFO] BUILD SUCCESS
```

测试数从旧基线 158 → **170**（+12），对账（改造前后 `@Test` 计数，经 `git show f4936e0~1` 核实旧值）：

| 测试类 | 改造前 | 改造后 | 变化 | 说明 |
|--------|--------|--------|------|------|
| `RetrievalPipelineTest` | —（不存在） | 13 | +13 | 新增：三通道检索、统一后处理（精排/截断）、覆盖参数、降级场景 |
| `RAGServiceTest` | 8 | 3 | −5 | 检索断言全部迁出，仅留对话组装（composePrompt、AnswerWithSources） |
| `EvaluationPipelineTest` | —（不存在） | 3 | +3 | 新增：skipAnswerQuality 分流走 retrieve 零 LLM 调用 |
| `MultiRecallRouterTest` | 4 | 5 | +1 | 新增：召回深度由调用方传入（3 参签名） |
| 其余 29 个测试类 | 不变 | 不变 | 0 | |
| **合计** | **158** | **170** | **+12** | 全绿 |

## 二、端到端冒烟（Step 2）

### 环境事实（如实记录）

| 项 | 状态 | 证据 |
|----|------|------|
| Docker daemon | **不可达** | `docker ps` → `failed to connect to the docker API at npipe:////./pipe/dockerDesktopLinuxEngine` |
| Milvus | DEGRADED（内存存储） | 启动日志：`向量库会话降级到内存存储: Milvus 不可达 (localhost:19530)` |
| 精排模型 | 降级（未下载） | `RAG_MODEL_AUTO_DOWNLOAD=false` 启动；日志：`精排模型未找到 (...models\bge-reranker-v2-m3\model.onnx), 重排序已降级跳过`——未触发约 2GB 下载 |
| LLM API key | 环境继承可用 | 3 次请求均返回非空 answer（未复现 answer=null 偶发） |

Controller 指示按 DEGRADED 路径冒烟（不 `docker compose up`），以上与指示一致。

### 冒烟步骤

1. 建默认文档目录 `./documents`（`AppConfig.getDocumentDir()` 默认值，AppConfig.java:224），自写中文文档 `retrieval-pipeline-smoke.md`（主题：检索管线三通道架构、精排降级、唯一事实源）；应用启动 autoIngest 自动摄入，日志 `切分质量评分: 0.70 (文件: retrieval-pipeline-smoke.md, 分段数: 4)`。
2. `mvn clean package -DskipTests` → BUILD SUCCESS（shaded fat JAR）。
3. `RAG_MODEL_AUTO_DOWNLOAD=false java -jar target/MyAIDemo2-1.0-SNAPSHOT.jar` 后台启动，`/api/health` 约 2s 就绪：`{"status":"ok","environment":{"total":6,"ok":2,"missing":3,"error":0,"skipped":1}}`。

### 三次请求与断言

| # | 请求 | 断言结果 |
|---|------|----------|
| 1 | `{"query":"检索管线内部包含哪三个召回通道？精排器不可用时系统如何降级？"}`（默认参数） | ✅ http 200，answer 611 字符非空；sources 3 条，元素含 `fileName/text/score` 三字段（与改造前一致），全部命中 `retrieval-pipeline-smoke.md` |
| 2 | `{"query":"RRF 融合的作用是什么？","enhancement":"none"}` | ✅ http 200，answer 324 字符；sources 3 条。该路径旧实现不精排，现走统一管线，降级精排下截断到 maxResults=3 |
| 3 | `{"query":"什么是唯一检索事实源？","recall":["dense"]}` | ✅ http 200，answer 275 字符；sources 3 条 |

响应样例（resp 1，截取）：

```
answer: ……检索管线内部包含三个召回通道：1. Dense 通道：基于向量嵌入的稠密召回……
  source[0]: file=retrieval-pipeline-smoke.md score=0.7442
  source[1]: file=retrieval-pipeline-smoke.md score=0.7235
  source[2]: file=retrieval-pipeline-smoke.md score=0.7194
```

resp 2 的 answer 为"参考资料不足以完整说明 RRF 的定义……"——诚实拒答，验证了 composePrompt 模板中"若参考资料不足以回答，请如实说明"指令生效（检索本身命中正确段落）。

### 统一管线日志证据

ChatController INFO 行确认覆盖参数到达入口：

```
Chat query: 检索管线内部包含哪三个召回通道…… (enhancement: null, recall: null)
Chat query: RRF 融合的作用是什么？ (enhancement: none, recall: null)
Chat query: 什么是唯一检索事实源？ (enhancement: null, recall: [dense])
```

请求 1/2 的调用栈（safeEnhance 降级日志展开）：

```
QueryEnhancementRouter.route(QueryEnhancementRouter.java:55)
  → RetrievalPipeline.recallViaEnhancement(RetrievalPipeline.java:83)
  → RetrievalPipeline.recallPool(RetrievalPipeline.java:60)
  → RetrievalPipeline.retrieve(RetrievalPipeline.java:50)
```

请求 1 另有 `Query enhancement mode: rewrite (requested: auto)`，确认增强路由 → 管线 E 通道路径。

### 清理

应用进程停止后 `/api/health` 连接失败（exit 000）、`netstat` 无 `:8080 LISTENING`——无残留进程。

### 冒烟观察（非缺陷）

LLM 回复首句两次提到"问题似乎有乱码"：Windows curl `-d` 以本地编码发送 UTF-8 中文，服务端按 UTF-8 解析出现替换字符。LLM 仍按语义正确理解并回答，检索三问三中。属发送侧工具编码问题，非应用缺陷（HTTP 层以 JSON 字节流为准，服务端解析逻辑未变）。

## 三、评估基线重建（Step 3）

命令：`mvn test -P evaluation -Devaluation.updateBaseline=true`

```
[INFO] Tests run: 1, Failures: 0, Errors: 0, Skipped: 0 -- in me.maxt.rag.web.evaluation.EvaluationTest
[INFO] BUILD SUCCESS
```

五个格式全部 `基线写入: src\test\resources\evaluation\<fmt>\baseline.json` + `对比基线未退化`。

### 前后对比

| 格式 | recallAtK（旧→新） | precisionAtK | MRR | NDCG | 变化 |
|------|-------------------|--------------|-----|------|------|
| markdown | 1.0 → 1.0 | 0.5 → 0.5 | 0.75 → 0.75 | 0.815465 → 0.815465 | 持平 |
| txt | 1.0 → 1.0 | 0.5 → 0.5 | 0.75 → 0.75 | 0.815465 → 0.815465 | 持平 |
| pdf | 1.0 → 1.0 | 1.0 → 1.0 | 1.0 → 1.0 | 1.0 → 1.0 | 持平 |
| docx | 1.0 → 1.0 | 1.0 → 1.0 | 1.0 → 1.0 | 1.0 → 1.0 | 持平 |
| json | 1.0 → 1.0 | 1.0 → 1.0 | 1.0 → 1.0 | 1.0 → 1.0 | 持平 |

每个 baseline.json 仅 timestamp 一行变化（2026-08-10 → 2026-09-10，`git diff --stat` 5 文件各 1 行）。

### 持平解读（为何 ①②④ 未体现在数字上）

设计预期"行为变化 ①②④ 会体现在数字上"，但评估桩（EvaluationTest.java:90-101）刻意构造了确定性最小系统：`unavailableReranker.isAvailable()=false`、enhancement 禁用、multiRecall 禁用——评估走 **P 通道 + 精排不可用**路径：

- ①（朴素/单变体精排生效）与 ②（E 多变体候选池 3×）：仅在**精排可用**/**E 通道**时触发，评估配置两者皆无 → 不触发；
- ④（fuseN 替换两两折叠）：仅 E 多变体路径触发，评估不进入 → 不触发；
- ③（E 不可用分支裁剪 recallTopK→maxResults）：E 通道未进入 → 不触发。

因此持平的确切含义是：**统一管线迁移在评估确定性配置下检索质量零回归**（5 格式 recall/MRR/NDCG 逐位一致，"对比基线未退化"）。真实环境（精排可用 + 增强开启）的行为变化由 `RetrievalPipelineTest` 13 个用例以 mock 精排器锁定，其中本次 DEGRADED 冒烟的"降级精排下截断到 maxResults"路径在三次请求中均可观察（sources 恒为 3 = maxResults）。

## 四、行为变化 5 项实际观察

| # | 行为变化 | 观察渠道 | 结果 |
|---|----------|----------|------|
| ① | 单变体/朴素路径精排生效（候选池 ×expansion → rerank → rerankTopK） | `RetrievalPipelineTest`（reranker 可用桩断言精排触发 + 池扩展）；评估桩走不可用分支 | 单测锁定 ✅；真实环境精排模型未安装，实际生效待模型安装后（见遗留） |
| ② | E 多变体候选池 2×→3×（expansionFactor 默认 3） | `RetrievalPipelineTest`（多变体融合后统一精排）；评估不进入 E 通道 | 单测锁定 ✅ |
| ③ | E 多变体不可用分支裁剪 recallTopK→maxResults（bug 修复） | `RetrievalPipelineTest`（不可用截断到 maxResults）；冒烟 3 请求 sources 恒 3 条 | 单测锁定 ✅ + 冒烟可观察 ✅ |
| ④ | 融合算法两两折叠→fuseN 一次性 N 路 | `RetrievalPipelineTest` + `RrfFusionTest`（N 路）；冒烟请求 1（auto→rewrite 变体路径）走通 | 单测锁定 ✅ + 冒烟路径走通 ✅ |
| ⑤ | 精排不可用路径召回深度缩小：M 通道每路 ×3→×1、E 多变体每变体 ×2→×1（终审发现、有意设计——"池仅精排可用时扩展"省白搜，RRF k=60 下 rank>topK 候选得分 ≤1/66） | `RetrievalPipelineTest`：shouldPreferMultiRecallOverEnhancement 断言 M 深度 eq(4)=recallTopK（精排不可用不扩池）；shouldLimitToMaxResultsWhenRerankerUnavailable 断言截断 2=maxResults | 终审 diff 复核 + 单测断言 ✅（未跑运行时数字，不伪造） |

## 五、遗留问题

1. **Milvus CONNECTED 路径未冒烟**：Docker daemon 本机不可达（共享环境，Controller 未授权 compose up）。DEGRADED（内存存储）路径已完整验证；CONNECTED 路径的差异仅在存储介质，检索编排逻辑相同，由 `MilvusSessionTest`/`MilvusSessionIT` 覆盖。
2. **精排可用路径未在真实环境冒烟**：模型未下载（2GB，按指示禁用自动下载）。统一精排在真实链路的生效由单元测试（reranker 可用桩）保证；后续模型安装后可自然验证（一键安装 → 自动加载已由候选 2 修复 add642c）。
3. **LLM answer=null 偶发**：本次 3 请求均未复现，无需重试。
4. **评估数字不覆盖行为变化 ①②④**：评估桩刻意禁用精排与增强（确定性要求），基线持平只能证明"无回归"。若需在评估中观察行为变化数字，需在评估配置中启用精排桩（设计取舍，非缺陷）。

## 六、文件变更清单（本任务）

- 新增 `docs/reviews/retrieval-pipeline-verification-20260910.md`（本报告）
- 修改 `CONTEXT.md`（追加术语"检索管线（RetrievalPipeline）"）
- 修改 `docs/reviews/architecture-backlog.md`（候选 3 → ✅）
- 修改 `CLAUDE.md`（关键入口表 + 测试数 158→170）
- 重建 `src/test/resources/evaluation/{markdown,txt,pdf,docx,json}/baseline.json`（各 1 行 timestamp）
- `README.md` 未改：grep 确认无旧三分支表述（"多路召回三路并行"描述的是 MultiRecallRouter 召回模式，仍准确；`/api/chat` 文档的 enhancement/recall 参数与 fileName/text/score 字段和现实现一致）
