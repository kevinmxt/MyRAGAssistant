# RetrievalPipeline 检索编排深化（架构评审候选 3）设计

2026-09-10

## 背景与问题

源自 [2026-08-20 架构评审](../../reviews/architecture-review-20260820.html)候选 3。RAGService 的检索编排随功能叠加蔓延出三类症状，外加本次探索发现的两个隐藏问题：

| # | 症状 | 位置 |
|---|------|------|
| 1 | `instanceof RerankConfig` 运行时探测接口之外的事实 | `RAGService.java:223`、`MultiRecallRouter.java:35`（全限定名写法，评审只登记了前者） |
| 2 | `answerWithSources` 三分支重复"检索→融合→精排→映射"骨架 | `RAGService.java:145-204` |
| 3 | 4 个望远镜构造函数，null 表达可选协作者 | `RAGService.java:63-109` |
| 4 | 精排行为不一致：多路召回/增强多变体路径精排，单变体/朴素路径静默跳过（历史蔓延，非有意设计） | `searchAndCollect` 不经 `rerankIfAvailable` |
| 5 | 双轨检索：LLM 上下文来自 AiServices 内置 `contentRetriever`（朴素稠密检索），前端展示的 sources 来自自管管线——两者可能不一致 | `RAGService.java:104-108,202` |

另有一个顺带 bug：精排不可用时 `rerankIfAvailable` 用 `recallConfig.getRecallTopK()` 裁剪所有通道的候选——增强通道的候选也会被多路召回的 topK 误裁。

## 已确认决策（brainstorming 结论）

1. **精排统一生效**：所有检索路径的候选，Reranker 可用时一律精排；接受行为变化与评估基线重建。
2. **管线成唯一检索事实源**：答案生成的上下文 = 管线检索结果，`assistant` 卸掉 `contentRetriever`；前端展示的 sources 与 LLM 上下文恒等。
3. **EvaluationPipeline 本次接入** `retrieve` 入口：`skipAnswerQuality` 时纯检索指标评估，零 LLM 调用。
4. **方案取 A（单入口深模块）**，策略接口化（B）与最小修补（C）被否——通道选择是启动期配置开关而非运行时多态需求，B 属过度设计；C 与决策 1/2 冲突。

## 架构

```
                    ┌───────────────────────── RetrievalPipeline（深模块）─────────────────────────┐
                    │  retrieve(query, overrides)                                              │
                    │    1. 选通道（配置开关）: M 多路召回 / E 查询增强 / P 朴素                    │
                    │    2. 通道召回（自降级，不抛异常）→ 候选池（topK × expansion，仅精排可用时扩）    │
                    │    3. 统一后处理: 精排(rerankTopK) / 截断(finalTopK) → 映射 List<Source>     │
                    └──────────────────────────────────────────────────────────────────────────┘
RAGService（对话门面）: sources = pipeline.retrieve(...) → composePrompt(query, sources) → assistant（无 retriever，保留 chatMemory）
EvaluationPipeline   : skipAnswerQuality → pipeline.retrieve(...)（零 LLM）；否则 ragService.answerWithSources(...)
```

## 接口

新类 `me.maxt.rag.web.service.vector.RetrievalPipeline`，公共 API 只有三样：

```java
public List<Source> retrieve(String query, RetrievalOverrides overrides)

public record RetrievalOverrides(String enhancementMode, List<String> recallModes)
// null 字段 = 使用配置默认值

public record Source(String fileName, String text, double score)
// 从 RAGService.Source 迁出；Jackson 序列化字段名与现状一致（fileName/text/score）
```

### 构造：一个完整依赖对象，全部必非 null

```java
public record Deps(
    EmbeddingStoreManager storeManager,
    EmbeddingModel embeddingModel,
    RetrievalConfig retrievalConfig,                    // maxResults / minScore
    QueryEnhancementRouter enhancementRouter,
    QueryEnhancementConfig enhancementConfig,           // isQueryEnhancementEnabled 表达开关
    MultiRecallRouter multiRecallRouter,
    RecallConfig recallConfig,                          // isMultiRecallEnabled 表达开关
    Reranker reranker,                                  // isAvailable() 表达可用性
    RerankConfig rerankConfig)                          // rerankTopK / rerankExpansionFactor 显式注入
```

"关闭/不可用"由协作者自身表达，删光 null 防御与两处 `instanceof`。

### MultiRecallRouter 签名收敛

```java
// 现状: recall(String query, List<String> modes) —— 内部 instanceof RerankConfig 探测 expansionFactor
// 新:   recall(String query, List<String> modes, int perStrategyTopK)
```

召回深度是编排参数，由管线算好传入；router 不再依赖 `RerankConfig`。

### RAGService 瘦身

```java
public RAGService(RetrievalPipeline pipeline, ChatModel chatModel, RetrievalConfig config)
```

- `answerWithSources` 三个公共签名保留（ChatController / EvaluationPipeline 调用面零改动）
- `AnswerWithSources` 保持 public 字段类不动，引用新 `Source`
- 删除：`answer(String)`（无生产调用方）、4 个望远镜构造、`searchAndCollect`、`rerankIfAvailable`、`toSource`、`contentRetriever` 装配

### 组装根（WebApplication）

- 总是构造 `MultiRecallRouter`（现状 disabled 时跳过）；策略 registry 构造轻量无 I/O，sparse 维持 `nativeClient != null` 条件注册
- 构造 `RetrievalPipeline` 后传入瘦身的 `RAGService`

## 数据流与深度语义

### 通道选择（启动期配置开关，按序判定）

1. `recallConfig.isMultiRecallEnabled()` → M 通道
2. `enhancementConfig.isQueryEnhancementEnabled()` → E 通道
3. 否则 → P 通道

### 通道召回（候选池，自降级契约：失败不上抛）

- **M**：`router.recall(query, modes, perStrategyTopK)`——并行多路 + RRF（既有 per-strategy 容错、30s 超时）
- **E**：`enhancementRouter.route(query, mode)`——单变体单路稠密检索；多变体每路检索后 `RrfFusion.fuseN` 一次性 N 路融合（替换现状两两折叠，canonical RRF；既有降级：classify 失败→rewrite、enhancer 失败→原查询）
- **P**：单路稠密检索

### 深度语义统一表（★ = 行为变化）

| 通道 | 候选池现状 | 候选池新值（精排可用时） | 精排不可用时终裁 |
|------|-----------|------------------------|-----------------|
| M 多路召回 | recallTopK×3 | recallTopK×expansion（不变） | limit recallTopK（不变） |
| E 多变体 | maxResults×2 | ★ maxResults×expansion（默认 3，池变大） | ★ limit maxResults（现状误用 recallTopK 裁剪，属 bug 修复） |
| E 单变体 / P | maxResults，从不精排 | ★ maxResults×expansion → rerank → rerankTopK | limit maxResults |

候选池仅在精排可用时扩展；不可用时 ×1（稠密检索天然有序，扩了不裁等于白搜）。

### 答案生成（composePrompt，管线成唯一事实源）

```
sources = pipeline.retrieve(query, overrides)
answer  = assistant.answer(composePrompt(query, sources))
```

prompt 模板（类内常量）：

```
请根据以下参考资料回答问题。若参考资料不足以回答，请如实说明。

参考资料：
[1] {text}
[2] {text}

问题：{query}
```

空候选时输出"（无参考资料）"，答案仍生成不抛异常。

**多轮对话影响（已查证 langchain4j 1.12.1 文档）**：AiServices 默认 `storeRetrievedContentInChatMemory=true`——现状记忆里存的本就是"问题+当轮检索资料"的加厚消息。composePrompt 方案记忆形态与现状逐点等价：窗口机制不变（MessageWindowChatMemory 按条数，默认 10）、Q-A 结构不变、每条 user 消息厚度同量级（资料从朴素检索换成管线结果，质量更高）。附带改善：追问历史轮资料时，LLM 看到的与前端展示的 sources 恒等。

## EvaluationPipeline 接入

构造签名追加 `RetrievalPipeline pipeline`（`ragService` 保留）：

```java
if (skipAnswerQuality || !answerQualityEvaluator.isAvailable()) {
    sources = pipeline.retrieve(tc.query(), null);          // 纯检索指标，零 LLM 调用
} else {
    result = ragService.answerWithSources(tc.query());      // 答案 + 来源
}
```

## 错误处理与降级

- **通道自降级契约**（管线级约定，写入 Javadoc）：通道内失败不上抛——M 通道 per-strategy 容错 + 30s 超时（既有）；E 通道 classify/enhancer 降级（既有）。管线不新增 try/catch
- **精排降级**：`reranker.isAvailable()=false` → 候选池不扩、limit(finalTopK)
- **store DEGRADED**：内存 store 检索照常（MilvusSession 既有职责，管线无感知）
- **空候选**：prompt 模板兜底，答案仍生成

## 测试策略

| 层 | 内容 |
|----|------|
| `RetrievalPipelineTest`（新） | 迁移 RAGServiceTest 的 7 个检索断言（sources 正确性、maxResults 限制、增强触发、精排触发/跳过/null 场景）；构造改 Deps + 配置开关桩（"无增强" = enabled=false 桩，不再传 null 协作者）；新增深度语义用例（单变体路径精排生效、不可用截断到 maxResults） |
| `RAGServiceTest`（瘦身） | 对话组装：composePrompt 含参考资料块、answer 非 null、AnswerWithSources 字段完整 |
| `EvaluationPipelineTest` | 分流两路：skipAnswerQuality 走 retrieve 不触 LLM；否则 answerWithSources |
| 评估基线 | 行为变化落地后跑 `mvn test -P evaluation` 重建基线，对比记录写 `docs/reviews/` |
| 兼容回归 | `/api/chat` 响应 JSON 字段与现状一致（端到端验证环节覆盖） |

测试总数从 158 变化（迁移约 7 个 + 新增若干），以最终 `mvn test` 全绿为准。

## 行为变化清单（4 项）

1. 单变体/朴素路径精排生效（决策 1）
2. E 多变体候选池 2×→3×（expansionFactor 默认值）
3. E 多变体不可用分支裁剪 recallTopK→maxResults（bug 修复）
4. 融合算法两两折叠→`fuseN` 一次性 N 路（排名细节微差）

①②④ 会反映到评估基线数字；交付时重建基线并记录对比。

## 明确不做（YAGNI）

- 不引入 `RetrievalChannel` 策略接口层——通道选择是配置开关，非运行时多态
- 不动 `QueryEnhancementRouter` / `MultiRecallRouter` / `CrossEncoderReranker` 内部实现（后者仅签名跟随）
- 不引入 `storeRetrievedContentInChatMemory(false)` 精简记忆——该开关仅 augmentor 场景生效，与 composePrompt 无关
- `RecallConfig.recallTopK` 与 `RetrievalConfig.maxResults` 的语义重叠（两个"最终条数"旋钮）不在本次收敛——两通道各有默认值语义，动配置面超出本候选范围
