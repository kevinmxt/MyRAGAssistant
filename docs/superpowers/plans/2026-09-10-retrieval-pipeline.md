# RetrievalPipeline 检索编排深化（架构评审候选 3）实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 把 RAGService 蔓延的三分支检索编排收敛为单一 `RetrievalPipeline` 深模块，统一精排与深度语义，使检索管线成为唯一事实源（LLM 上下文 = 前端展示的 sources）。

**Architecture:** 新建 `RetrievalPipeline`（唯一入口 `retrieve(query, overrides)`，三通道为私有方法，统一"召回→融合→精排→映射"后处理）；`RAGService` 瘦身为对话门面（composePrompt + 无 retriever 的 assistant）；`MultiRecallRouter` 签名收敛（深度由调用方传入，删 instanceof）；`EvaluationPipeline` 接入 retrieve 入口。

**Tech Stack:** Java 21、langchain4j 1.12.1（AiServices/EmbeddingMatch/InMemoryEmbeddingStore）、JUnit 5 + Mockito + AssertJ、Maven。

**Spec:** `docs/superpowers/specs/2026-09-10-retrieval-pipeline-design.md`（本计划从 spec 出发，执行者应同时读两份）。

## Global Constraints

- commit message 中文，前缀 `新增: ` / `重构: ` / `修复: ` / `文档: `。
- 每个任务收尾 `mvn test` 必须全绿（Task 1-6）；基线测试数 158 会变化，以全绿为准。surefire 报告里 EvaluationTest/MultiRecallRouterIT 是陈旧残留文件，勿混淆计数。
- 报告类产物写 `docs/reviews/`，禁止 /tmp。
- 公共类与公共方法写中文 Javadoc（codeChecker 约定）。
- 不引入新配置键、不动 `QueryEnhancementRouter`/`CrossEncoderReranker` 内部实现。
- 行为变化 4 项已在 spec 确认（单变体/朴素路径精排生效、E 多变体池 2×→3×、E 不可用裁剪 recallTopK→maxResults、两两折叠→fuseN）。

## 完成后的公共 API（类型速查，后续任务引用此处签名）

```java
// me.maxt.rag.web.service.vector.RetrievalPipeline
public RetrievalPipeline(Deps deps)
public List<Source> retrieve(String query, RetrievalOverrides overrides)   // overrides 可为 null
public record RetrievalOverrides(String enhancementMode, List<String> recallModes)
public record Source(String fileName, String text, double score)
public record Deps(EmbeddingStoreManager storeManager, EmbeddingModel embeddingModel,
                   RetrievalConfig retrievalConfig,
                   QueryEnhancementRouter enhancementRouter, QueryEnhancementConfig enhancementConfig,
                   MultiRecallRouter multiRecallRouter, RecallConfig recallConfig,
                   Reranker reranker, RerankConfig rerankConfig)

// MultiRecallRouter（Task 3 改）
public List<EmbeddingMatch<TextSegment>> recall(String query, List<String> modes, int perStrategyTopK)

// RAGService（Task 5 改）
public RAGService(RetrievalPipeline pipeline, ChatModel chatModel, RetrievalConfig config)
public AnswerWithSources answerWithSources(String query)                                   // 保留
public AnswerWithSources answerWithSources(String query, String enhancementMode)           // 保留
public AnswerWithSources answerWithSources(String query, String enhancementMode, List<String> recallModes)  // 保留
```

---

### Task 1: RetrievalPipeline 骨架 + P 通道 + 统一后处理

**Files:**
- Create: `src/main/java/me/maxt/rag/web/service/vector/RetrievalPipeline.java`
- Test: `src/test/java/me/maxt/rag/web/service/vector/RetrievalPipelineTest.java`

**Interfaces:**
- Consumes: `EmbeddingStoreManager.search(EmbeddingSearchRequest)` / `.add(Embedding, TextSegment)`（既有）；`Reranker.rerank(query, candidates, topK)` / `.isAvailable()`（既有）；配置接口（既有）。
- Produces: `RetrievalPipeline` 构造 + `retrieve` + 三个嵌套 record（见上文速查）。E/M 通道本任务不实现（配置开关关闭时自然落到 P），Task 2/4 补齐。

- [ ] **Step 1: 写失败测试**

新建 `RetrievalPipelineTest.java`。桩的写法沿用 RAGServiceTest：真实 `InMemoryEmbeddingStore` + `EmbeddingStoreManager(() -> store)`、mock `EmbeddingModel`（固定查询向量）、mock `Reranker`。辅助工厂方法构造 Deps（E/M 一律关闭，走 P 通道）：

```java
package me.maxt.rag.web.service.vector;

import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.output.Response;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import dev.langchain4j.store.embedding.EmbeddingSearchRequest;
import dev.langchain4j.store.embedding.inmemory.InMemoryEmbeddingStore;
import me.maxt.rag.web.config.QueryEnhancementConfig;
import me.maxt.rag.web.config.RecallConfig;
import me.maxt.rag.web.config.RerankConfig;
import me.maxt.rag.web.config.RetrievalConfig;
import me.maxt.rag.web.service.EmbeddingStoreManager;
import me.maxt.rag.web.service.vector.recall.MultiRecallRouter;
import me.maxt.rag.web.service.vector.rerank.Reranker;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class RetrievalPipelineTest {

    private EmbeddingStoreManager storeManager;
    private RetrievalConfig retrievalConfig;
    private QueryEnhancementConfig enhConfig;
    private RecallConfig recallConfig;
    private RerankConfig rerankConfig;
    private Reranker reranker;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        InMemoryEmbeddingStore<TextSegment> store = new InMemoryEmbeddingStore<>();
        storeManager = spy(new EmbeddingStoreManager(() -> store));

        retrievalConfig = mock(RetrievalConfig.class);
        when(retrievalConfig.getMaxResults()).thenReturn(3);
        when(retrievalConfig.getMinScore()).thenReturn(0.0);

        enhConfig = mock(QueryEnhancementConfig.class);
        when(enhConfig.isQueryEnhancementEnabled()).thenReturn(false);

        recallConfig = mock(RecallConfig.class);
        when(recallConfig.isMultiRecallEnabled()).thenReturn(false);

        rerankConfig = mock(RerankConfig.class);
        when(rerankConfig.getRerankTopK()).thenReturn(5);
        when(rerankConfig.getRerankExpansionFactor()).thenReturn(3);

        reranker = mock(Reranker.class);
        when(reranker.isAvailable()).thenReturn(false);
    }

    /** 构造 P 通道管线（E/M 关闭，reranker 可用性由参数控制） */
    private RetrievalPipeline plainPipeline(EmbeddingModel embeddingModel) {
        return new RetrievalPipeline(new RetrievalPipeline.Deps(
                storeManager, embeddingModel, retrievalConfig,
                mock(QueryEnhancementRouter.class), enhConfig,
                new MultiRecallRouter(recallConfig, java.util.Map.of()), recallConfig,
                reranker, rerankConfig));
    }

    private EmbeddingModel fixedVectorModel(float[] vector) {
        EmbeddingModel model = mock(EmbeddingModel.class);
        Response<Embedding> resp = mock(Response.class);
        when(resp.content()).thenReturn(Embedding.from(vector));
        when(model.embed(anyString())).thenReturn(resp);
        return model;
    }

    @Test
    void shouldRetrieveCorrectSources() {
        float[] v1 = {0.5f, 0.5f, 0.5f};
        TextSegment s1 = TextSegment.from("Paris is the capital of France.");
        s1.metadata().put("file_name", "facts.txt");
        storeManager.add(Embedding.from(v1), s1);

        List<RetrievalPipeline.Source> sources =
                plainPipeline(fixedVectorModel(v1)).retrieve("What is the capital of France?", null);

        assertThat(sources).hasSize(1);
        assertThat(sources.get(0).text()).isEqualTo("Paris is the capital of France.");
        assertThat(sources.get(0).fileName()).contains("facts.txt");
        assertThat(sources.get(0).score()).isGreaterThan(0.9);
    }

    @Test
    void shouldIncludeDirectoryPathInFileName() {
        float[] v = {0.3f, 0.3f, 0.3f};
        TextSegment seg = TextSegment.from("London is the capital of UK.");
        seg.metadata().put("file_name", "geo.txt");
        seg.metadata().put("absolute_directory_path", "/docs");
        storeManager.add(Embedding.from(v), seg);

        List<RetrievalPipeline.Source> sources =
                plainPipeline(fixedVectorModel(v)).retrieve("boiling point of water", null);

        assertThat(sources).hasSize(1);
        assertThat(sources.get(0).fileName()).isEqualTo("/docs/geo.txt");
    }

    @Test
    void shouldReturnEmptySourcesWhenNoRelevantDocs() {
        List<RetrievalPipeline.Source> sources =
                plainPipeline(fixedVectorModel(new float[]{1.0f, 0.0f, 0.0f}))
                        .retrieve("random question", null);
        assertThat(sources).isEmpty();
    }

    @Test
    void shouldLimitToMaxResultsWhenRerankerUnavailable() {
        for (int i = 0; i < 5; i++) {
            storeManager.add(Embedding.from(new float[]{0.5f, 0.5f}), TextSegment.from("doc " + i));
        }
        when(retrievalConfig.getMaxResults()).thenReturn(2);

        List<RetrievalPipeline.Source> sources =
                plainPipeline(fixedVectorModel(new float[]{0.5f, 0.5f})).retrieve("query", null);

        assertThat(sources).hasSize(2);
        verify(reranker, never()).rerank(anyString(), anyList(), anyInt());
    }

    @Test
    @SuppressWarnings("unchecked")
    void shouldRerankPlainRetrievalAndExpandPoolWhenAvailable() {
        // 行为变化①：朴素路径也精排；且候选池按 expansionFactor 扩展
        when(reranker.isAvailable()).thenReturn(true);
        float[] v = {0.5f, 0.5f};
        storeManager.add(Embedding.from(v), TextSegment.from("candidate"));
        EmbeddingMatch<TextSegment> reranked = mock(EmbeddingMatch.class);
        TextSegment seg = TextSegment.from("reranked text");
        seg.metadata().put("file_name", "r.txt");
        when(reranked.embedded()).thenReturn(seg);
        when(reranked.score()).thenReturn(0.95);
        when(reranker.rerank(eq("query"), anyList(), eq(5))).thenReturn(List.of(reranked));

        List<RetrievalPipeline.Source> sources =
                plainPipeline(fixedVectorModel(v)).retrieve("query", null);

        // 候选池深度 = maxResults(3) × expansion(3) = 9
        ArgumentCaptor<EmbeddingSearchRequest> captor = ArgumentCaptor.forClass(EmbeddingSearchRequest.class);
        verify(storeManager, atLeastOnce()).search(captor.capture());
        assertThat(captor.getValue().maxResults()).isEqualTo(9);
        // 精排结果成为 sources
        assertThat(sources).hasSize(1);
        assertThat(sources.get(0).text()).isEqualTo("reranked text");
    }

    @Test
    @SuppressWarnings("unchecked")
    void shouldNotExpandPoolWhenRerankerUnavailable() {
        float[] v = {0.5f, 0.5f};
        storeManager.add(Embedding.from(v), TextSegment.from("candidate"));

        plainPipeline(fixedVectorModel(v)).retrieve("query", null);

        ArgumentCaptor<EmbeddingSearchRequest> captor = ArgumentCaptor.forClass(EmbeddingSearchRequest.class);
        verify(storeManager, atLeastOnce()).search(captor.capture());
        assertThat(captor.getValue().maxResults()).isEqualTo(3);
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `mvn test -Dtest=RetrievalPipelineTest`
Expected: 编译失败（`RetrievalPipeline` 不存在）。

- [ ] **Step 3: 最小实现**

新建 `RetrievalPipeline.java`（E/M 通道位置先留 channel 判定空缺——判定顺序写好，E/M 分支体在 Task 2/4 落地；本任务里 `recallPool` 只含 M 判定（落空）→ E 判定（落空）→ P 检索）：

```java
package me.maxt.rag.web.service.vector;

import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import dev.langchain4j.store.embedding.EmbeddingSearchRequest;
import dev.langchain4j.store.embedding.EmbeddingSearchResult;
import me.maxt.rag.web.config.QueryEnhancementConfig;
import me.maxt.rag.web.config.RecallConfig;
import me.maxt.rag.web.config.RerankConfig;
import me.maxt.rag.web.config.RetrievalConfig;
import me.maxt.rag.web.service.EmbeddingStoreManager;
import me.maxt.rag.web.service.vector.recall.MultiRecallRouter;
import me.maxt.rag.web.service.vector.rerank.Reranker;

import java.util.List;

/**
 * 检索管线：拥有"从 query 到带来源候选列表"的检索编排。
 *
 * <p>唯一入口 {@link #retrieve(String, RetrievalOverrides)}。内部按配置开关选择召回通道
 * （多路召回 → 查询增强 → 朴素稠密检索），统一执行"召回（候选池仅在精排可用时按扩展倍数放大）
 * → 融合 → 精排/截断 → 映射 Source"的后处理。</p>
 *
 * <p>通道自降级契约：通道内部失败不上抛（多路召回 per-strategy 容错；查询增强失败回落原查询）。</p>
 *
 * @author maxt
 * @since 1.0
 */
public class RetrievalPipeline {

    private final Deps deps;

    public RetrievalPipeline(Deps deps) {
        this.deps = deps;
    }

    /**
     * 执行检索编排，返回带来源信息的候选列表。
     *
     * @param query     用户原始问题
     * @param overrides 每次调用的覆盖项，null 字段回退配置默认值；整个参数可为 null
     * @return 映射后的来源列表，无命中返回空列表
     */
    public List<Source> retrieve(String query, RetrievalOverrides overrides) {
        RetrievalOverrides o = overrides != null ? overrides : new RetrievalOverrides(null, null);
        boolean rerankOn = deps.reranker().isAvailable();
        List<EmbeddingMatch<TextSegment>> pool = recallPool(query, o, rerankOn);
        List<EmbeddingMatch<TextSegment>> ranked = postProcess(query, pool, rerankOn);
        return ranked.stream().map(RetrievalPipeline::toSource).toList();
    }

    private List<EmbeddingMatch<TextSegment>> recallPool(String query, RetrievalOverrides o, boolean rerankOn) {
        if (deps.recallConfig().isMultiRecallEnabled()) {
            return recallViaMultiRecall(query, o, rerankOn);   // Task 4 落地
        }
        if (deps.enhancementConfig().isQueryEnhancementEnabled()) {
            return recallViaEnhancement(query, o, rerankOn);   // Task 2 落地
        }
        return denseSearch(query, baseDepth(rerankOn));
    }

    /** Task 4 实现；本任务先抛不支持，保证未被配置启用的路径不可达。 */
    private List<EmbeddingMatch<TextSegment>> recallViaMultiRecall(String query, RetrievalOverrides o, boolean rerankOn) {
        throw new UnsupportedOperationException("Task 4 落地");
    }

    /** Task 2 实现。 */
    private List<EmbeddingMatch<TextSegment>> recallViaEnhancement(String query, RetrievalOverrides o, boolean rerankOn) {
        throw new UnsupportedOperationException("Task 2 落地");
    }

    /** 朴素/增强通道的候选池深度：精排可用时按扩展倍数放大。 */
    private int baseDepth(boolean rerankOn) {
        int base = deps.retrievalConfig().getMaxResults();
        return rerankOn ? base * deps.rerankConfig().getRerankExpansionFactor() : base;
    }

    private List<EmbeddingMatch<TextSegment>> postProcess(String query,
            List<EmbeddingMatch<TextSegment>> pool, boolean rerankOn) {
        if (rerankOn) {
            return deps.reranker().rerank(query, pool, deps.rerankConfig().getRerankTopK());
        }
        return pool.stream().limit(finalTopK()).toList();
    }

    /** 精排不可用时的终裁条数：多路召回用 recallTopK，其余用 maxResults。 */
    private int finalTopK() {
        return deps.recallConfig().isMultiRecallEnabled()
                ? deps.recallConfig().getRecallTopK()
                : deps.retrievalConfig().getMaxResults();
    }

    private List<EmbeddingMatch<TextSegment>> denseSearch(String text, int maxResults) {
        Embedding qe = deps.embeddingModel().embed(text).content();
        EmbeddingSearchResult<TextSegment> result = deps.storeManager().search(
                EmbeddingSearchRequest.builder()
                        .queryEmbedding(qe)
                        .maxResults(maxResults)
                        .minScore(deps.retrievalConfig().getMinScore())
                        .build());
        return result.matches();
    }

    private static Source toSource(EmbeddingMatch<TextSegment> match) {
        String dir = match.embedded().metadata().getString("absolute_directory_path");
        String name = match.embedded().metadata().getString("file_name");
        String fileName = dir != null && name != null ? dir + "/" + name : (name != null ? name : "unknown");
        return new Source(fileName, match.embedded().text(), match.score());
    }

    /** 每次调用的覆盖项，null 字段回退配置默认值。 */
    public record RetrievalOverrides(String enhancementMode, List<String> recallModes) {
    }

    /** 检索来源 DTO：文件名（含路径）、片段文本、相似度分数。 */
    public record Source(String fileName, String text, double score) {
    }

    /** 完整依赖集合，全部必非 null；"关闭/不可用"由协作者自身表达。 */
    public record Deps(EmbeddingStoreManager storeManager, EmbeddingModel embeddingModel,
                       RetrievalConfig retrievalConfig,
                       QueryEnhancementRouter enhancementRouter, QueryEnhancementConfig enhancementConfig,
                       MultiRecallRouter multiRecallRouter, RecallConfig recallConfig,
                       Reranker reranker, RerankConfig rerankConfig) {
    }
}
```

注意 `toSource` 对 dir/name 的组合判断与旧逻辑等价：旧代码 dir 非 null 时拼 `dir/name`（name 为 null 会拼出 "dir/null"，属缺陷）；新代码只在两者都非 null 时拼接。

- [ ] **Step 4: 跑测试确认通过**

Run: `mvn test -Dtest=RetrievalPipelineTest`
Expected: 6 个测试全 PASS。

- [ ] **Step 5: 全量回归 + 提交**

Run: `mvn test`（全绿）
```bash
git add src/main/java/me/maxt/rag/web/service/vector/RetrievalPipeline.java src/test/java/me/maxt/rag/web/service/vector/RetrievalPipelineTest.java
git commit -m "新增: RetrievalPipeline 深模块骨架与 P 通道统一后处理"
```

---

### Task 2: E 通道（查询增强：单变体检索 / 多变体 fuseN 融合）

**Files:**
- Modify: `src/main/java/me/maxt/rag/web/service/vector/RetrievalPipeline.java`（替换 `recallViaEnhancement` 占位）
- Test: `src/test/java/me/maxt/rag/web/service/vector/RetrievalPipelineTest.java`（追加 E 通道测试）

**Interfaces:**
- Consumes: `QueryEnhancementRouter.route(String query, String mode) → List<String>`（既有）；`RrfFusion.fuseN(List<List<EmbeddingMatch<TextSegment>>>, int topK, int k)`（既有）；`QueryEnhancementConfig.isQueryEnhancementEnabled()/getDefaultEnhancementMode()/getRrfK()`（既有）。
- Produces: E 通道行为（spec 深度表 E 行）。

- [ ] **Step 1: 追加失败测试**

在 `RetrievalPipelineTest` 追加（放在类尾部）：

```java
    // ===== E 通道 =====

    private RetrievalPipeline enhancedPipeline(EmbeddingModel embeddingModel, QueryEnhancementRouter router) {
        when(enhConfig.isQueryEnhancementEnabled()).thenReturn(true);
        when(enhConfig.getDefaultEnhancementMode()).thenReturn("rewrite");
        when(enhConfig.getRrfK()).thenReturn(60);
        return new RetrievalPipeline(new RetrievalPipeline.Deps(
                storeManager, embeddingModel, retrievalConfig,
                router, enhConfig,
                new MultiRecallRouter(recallConfig, java.util.Map.of()), recallConfig,
                reranker, rerankConfig));
    }

    @Test
    void shouldUseQueryEnhancementSingleVariant() {
        float[] v1 = {0.5f, 0.5f, 0.5f};
        TextSegment s1 = TextSegment.from("安装教程：下载后解压运行");
        s1.metadata().put("file_name", "guide.txt");
        storeManager.add(Embedding.from(v1), s1);

        QueryEnhancementRouter router = mock(QueryEnhancementRouter.class);
        when(router.route("怎么装", "rewrite")).thenReturn(List.of("安装教程"));

        List<RetrievalPipeline.Source> sources =
                enhancedPipeline(fixedVectorModel(v1), router).retrieve("怎么装", null);

        assertThat(sources).hasSize(1);
        assertThat(sources.get(0).text()).isEqualTo("安装教程：下载后解压运行");
    }

    @Test
    void shouldFuseMultiVariantsAndRerank() {
        // 行为变化①④：多变体融合（fuseN）后统一精排
        when(reranker.isAvailable()).thenReturn(true);
        float[] v = {0.5f, 0.5f};
        storeManager.add(Embedding.from(v), TextSegment.from("变体命中"));
        EmbeddingMatch<TextSegment> reranked = mock(EmbeddingMatch.class);
        TextSegment seg = TextSegment.from("精排结果");
        seg.metadata().put("file_name", "fused.txt");
        when(reranked.embedded()).thenReturn(seg);
        when(reranked.score()).thenReturn(0.9);
        when(reranker.rerank(eq("原始问题"), anyList(), eq(5))).thenReturn(List.of(reranked));

        QueryEnhancementRouter router = mock(QueryEnhancementRouter.class);
        when(router.route(anyString(), eq("both"))).thenReturn(List.of("变体1", "变体2"));

        List<RetrievalPipeline.Source> sources =
                enhancedPipeline(fixedVectorModel(v), router).retrieve("原始问题", null);

        // 每个变体各检索一次（2 次 embed + search），精排结果成为 sources
        assertThat(sources).hasSize(1);
        assertThat(sources.get(0).text()).isEqualTo("精排结果");
        verify(reranker).rerank(eq("原始问题"), anyList(), eq(5));
    }

    @Test
    void shouldPreferOverrideModeOverConfigDefault() {
        float[] v = {0.5f, 0.5f};
        storeManager.add(Embedding.from(v), TextSegment.from("命中"));
        QueryEnhancementRouter router = mock(QueryEnhancementRouter.class);
        when(router.route("q", "hyde")).thenReturn(List.of("假设文档"));

        enhancedPipeline(fixedVectorModel(v), router)
                .retrieve("q", new RetrievalPipeline.RetrievalOverrides("hyde", null));

        verify(router).route("q", "hyde");
    }

    @Test
    void shouldDefaultModeToNoneWhenConfigNull() {
        float[] v = {0.5f, 0.5f};
        storeManager.add(Embedding.from(v), TextSegment.from("命中"));
        when(enhConfig.getDefaultEnhancementMode()).thenReturn(null);
        QueryEnhancementRouter router = mock(QueryEnhancementRouter.class);
        when(router.route("q", "none")).thenReturn(List.of("q"));

        enhancedPipeline(fixedVectorModel(v), router).retrieve("q", null);

        verify(router).route("q", "none");
    }
```

- [ ] **Step 2: 跑测试确认失败**

Run: `mvn test -Dtest=RetrievalPipelineTest`
Expected: 新增 4 个 E 通道测试 FAIL（`UnsupportedOperationException: Task 2 落地`），原 6 个仍 PASS。

- [ ] **Step 3: 实现 E 通道**

替换 `recallViaEnhancement` 占位：

```java
    /** E 通道：查询增强。单变体直接稠密检索；多变体逐变体检索后 fuseN 一次性融合。 */
    private List<EmbeddingMatch<TextSegment>> recallViaEnhancement(String query,
            RetrievalOverrides o, boolean rerankOn) {
        String mode = o.enhancementMode() != null ? o.enhancementMode()
                : deps.enhancementConfig().getDefaultEnhancementMode();
        if (mode == null) {
            mode = "none";
        }
        List<String> variants = deps.enhancementRouter().route(query, mode);
        int depth = baseDepth(rerankOn);
        if (variants.size() <= 1) {
            return denseSearch(variants.isEmpty() ? query : variants.get(0), depth);
        }
        List<List<EmbeddingMatch<TextSegment>>> groups = new ArrayList<>();
        for (String variant : variants) {
            groups.add(denseSearch(variant, depth));
        }
        return RrfFusion.fuseN(groups, depth, deps.enhancementConfig().getRrfK());
    }
```

（import 补 `java.util.ArrayList`。`variants.isEmpty()` 分支是防御：route 契约不返回空列表，但防御成本一行。）

- [ ] **Step 4: 跑测试确认通过**

Run: `mvn test -Dtest=RetrievalPipelineTest`
Expected: 10 个测试全 PASS。

- [ ] **Step 5: 全量回归 + 提交**

Run: `mvn test`（全绿）
```bash
git add src/main/java/me/maxt/rag/web/service/vector/RetrievalPipeline.java src/test/java/me/maxt/rag/web/service/vector/RetrievalPipelineTest.java
git commit -m "新增: RetrievalPipeline E 通道，多变体 fuseN 融合接入统一精排"
```

---

### Task 3: MultiRecallRouter 签名收敛（删 instanceof，深度由调用方传入）

**Files:**
- Modify: `src/main/java/me/maxt/rag/web/service/vector/recall/MultiRecallRouter.java:32-38`
- Modify: `src/main/java/me/maxt/rag/web/service/RAGService.java:155-159`（调用点临时适配，Task 5 整体删除）
- Test: `src/test/java/me/maxt/rag/web/service/vector/recall/MultiRecallRouterTest.java`
- Test: `src/test/java/me/maxt/rag/web/service/RAGServiceTest.java:195,230,263`（3 处 stub 临时适配）

**Interfaces:**
- Consumes: 既有 router 行为（单路 limit recallTopK、多路 fuseN 上限 perStrategyTopK）。
- Produces: `recall(String query, List<String> modes, int perStrategyTopK)`（见速查）。

- [ ] **Step 1: 改测试先行**

`MultiRecallRouterTest` 四处 `router.recall("query", List.of(...))` 全部加第三参，并新增深度传递断言：

```java
// 四处调用点改为（示例，shouldRouteToActiveStrategies）：
List<EmbeddingMatch<TextSegment>> result = router.recall("query", List.of("dense", "sparse"), 15);
// 其余三处同理：recall("query", List.of("dense", "graph"), 15)、recall("query", List.of("dense", "sparse"), 15)、recall("query", List.of(), 15)

// 新增测试：
    @Test
    @SuppressWarnings("unchecked")
    void shouldPassDepthToStrategies() {
        RecallConfig config = mock(RecallConfig.class);
        when(config.getRecallModes()).thenReturn(List.of("dense"));
        when(config.getRecallTopK()).thenReturn(5);
        when(config.getRecallRrfK()).thenReturn(60);

        RecallStrategy dense = mock(RecallStrategy.class);
        when(dense.name()).thenReturn("dense");
        EmbeddingMatch<TextSegment> match = mock(EmbeddingMatch.class);
        when(match.embedded()).thenReturn(TextSegment.from("r"));
        when(match.score()).thenReturn(0.9);
        when(dense.recall(eq("query"), eq(15))).thenReturn(List.of(match));

        MultiRecallRouter router = new MultiRecallRouter(config, Map.of("dense", dense));

        router.recall("query", List.of("dense"), 15);

        // 深度由调用方传入，router 不再自行探测 RerankConfig
        verify(dense).recall(eq("query"), eq(15));
    }
```

`RAGServiceTest` 三处 M 通道 stub 同步改 3 参（临时，Task 5 删除）：

```java
// 195/230/263 行：
when(mockRouter.recall(anyString(), anyList(), anyInt())).thenReturn(List.of(recalled));
```

- [ ] **Step 2: 跑测试确认失败**

Run: `mvn test -Dtest=MultiRecallRouterTest`
Expected: 编译失败（2 参 recall 不存在）。

- [ ] **Step 3: 实现**

`MultiRecallRouter.recall` 签名与深度来源改动（其余逻辑不动）：

```java
    public List<EmbeddingMatch<TextSegment>> recall(String query, List<String> modes, int perStrategyTopK) {
        List<String> effectiveModes = resolveModes(modes);
        // perStrategyTopK 由调用方（RetrievalPipeline）按"最终 topK × 精排扩展倍数"算好传入
        ...（并行召回/fusion 逻辑原样，删除 instanceof RerankConfig 两行与本地 expansionFactor 计算）
```

`RAGService.java` M 分支调用点临时适配（Task 5 随整个方法删除）：

```java
            List<String> modes = recallModes != null ? recallModes : recallConfig.getRecallModes();
            int expansion = (config instanceof RerankConfig rc) ? rc.getRerankExpansionFactor() : 3;
            List<EmbeddingMatch<TextSegment>> matches =
                    multiRecallRouter.recall(query, modes, recallConfig.getRecallTopK() * expansion);
```

（临时沿用 instanceof 计算深度，保证旧 RAGService 在 Task 5 删除前行为不变。）

- [ ] **Step 4: 跑测试确认通过**

Run: `mvn test -Dtest=MultiRecallRouterTest,RAGServiceTest`
Expected: 全 PASS。

- [ ] **Step 5: 全量回归 + 提交**

Run: `mvn test`（全绿）
```bash
git add src/main/java/me/maxt/rag/web/service/vector/recall/MultiRecallRouter.java src/main/java/me/maxt/rag/web/service/RAGService.java src/test/java/me/maxt/rag/web/service/vector/recall/MultiRecallRouterTest.java src/test/java/me/maxt/rag/web/service/RAGServiceTest.java
git commit -m "重构: MultiRecallRouter 召回深度改由调用方传入，删 RerankConfig 探测"
```

---

### Task 4: M 通道（多路召回接入管线，通道优先级 M>E>P）

**Files:**
- Modify: `src/main/java/me/maxt/rag/web/service/vector/RetrievalPipeline.java`（替换 `recallViaMultiRecall` 占位）
- Test: `src/test/java/me/maxt/rag/web/service/vector/RetrievalPipelineTest.java`（追加 M 通道测试）

**Interfaces:**
- Consumes: Task 3 的 `recall(query, modes, perStrategyTopK)`。
- Produces: M 通道行为（spec 深度表 M 行）；三通道齐全的 `retrieve`。

- [ ] **Step 1: 追加失败测试**

在 `RetrievalPipelineTest` 追加：

```java
    // ===== M 通道 =====

    private MultiRecallRouter mockRouter;

    private RetrievalPipeline multiRecallPipeline(EmbeddingModel embeddingModel) {
        when(recallConfig.isMultiRecallEnabled()).thenReturn(true);
        when(recallConfig.getRecallTopK()).thenReturn(5);
        when(recallConfig.getRecallModes()).thenReturn(List.of("dense"));
        mockRouter = mock(MultiRecallRouter.class);
        return new RetrievalPipeline(new RetrievalPipeline.Deps(
                storeManager, embeddingModel, retrievalConfig,
                mock(QueryEnhancementRouter.class), enhConfig,
                mockRouter, recallConfig,
                reranker, rerankConfig));
    }

    @Test
    @SuppressWarnings("unchecked")
    void shouldRecallViaMultiRecallWithExpandedDepth() {
        when(reranker.isAvailable()).thenReturn(true);
        EmbeddingMatch<TextSegment> recalled = mock(EmbeddingMatch.class);
        TextSegment seg = TextSegment.from("多路召回候选");
        seg.metadata().put("file_name", "m.txt");
        when(recalled.embedded()).thenReturn(seg);
        when(recalled.score()).thenReturn(0.9);
        when(mockRouter.recall(eq("q"), anyList(), eq(15))).thenReturn(List.of(recalled));  // 5×3
        when(reranker.rerank(eq("q"), anyList(), eq(5))).thenReturn(List.of(recalled));

        List<RetrievalPipeline.Source> sources =
                multiRecallPipeline(fixedVectorModel(new float[]{0.5f})).retrieve("q", null);

        assertThat(sources).hasSize(1);
        assertThat(sources.get(0).text()).isEqualTo("多路召回候选");
    }

    @Test
    @SuppressWarnings("unchecked")
    void shouldPreferMultiRecallOverEnhancement() {
        // M 与 E 同时启用时走 M（通道优先级）
        when(enhConfig.isQueryEnhancementEnabled()).thenReturn(true);
        when(reranker.isAvailable()).thenReturn(false);
        when(recallConfig.getRecallTopK()).thenReturn(4);
        EmbeddingMatch<TextSegment> recalled = mock(EmbeddingMatch.class);
        TextSegment seg = TextSegment.from("m 优先");
        seg.metadata().put("file_name", "m.txt");
        when(recalled.embedded()).thenReturn(seg);
        when(recalled.score()).thenReturn(0.8);
        when(mockRouter.recall(eq("q"), anyList(), anyInt())).thenReturn(List.of(recalled));

        List<RetrievalPipeline.Source> sources =
                multiRecallPipeline(fixedVectorModel(new float[]{0.5f})).retrieve("q", null);

        assertThat(sources).hasSize(1);
        assertThat(sources.get(0).text()).isEqualTo("m 优先");
        // 精排不可用：深度不扩（=recallTopK=4），终裁 limit recallTopK
        verify(mockRouter).recall(eq("q"), anyList(), eq(4));
        verify(reranker, never()).rerank(anyString(), anyList(), anyInt());
    }

    @Test
    @SuppressWarnings("unchecked")
    void shouldPreferOverrideRecallModes() {
        when(reranker.isAvailable()).thenReturn(false);
        EmbeddingMatch<TextSegment> recalled = mock(EmbeddingMatch.class);
        TextSegment seg = TextSegment.from("指定模式");
        seg.metadata().put("file_name", "m.txt");
        when(recalled.embedded()).thenReturn(seg);
        when(recalled.score()).thenReturn(0.8);
        when(mockRouter.recall(eq("q"), eq(List.of("sparse")), anyInt())).thenReturn(List.of(recalled));

        multiRecallPipeline(fixedVectorModel(new float[]{0.5f}))
                .retrieve("q", new RetrievalPipeline.RetrievalOverrides(null, List.of("sparse")));

        verify(mockRouter).recall(eq("q"), eq(List.of("sparse")), anyInt());
    }
```

- [ ] **Step 2: 跑测试确认失败**

Run: `mvn test -Dtest=RetrievalPipelineTest`
Expected: 新增 3 个 M 通道测试 FAIL（`UnsupportedOperationException: Task 4 落地`），原 10 个仍 PASS。

- [ ] **Step 3: 实现 M 通道**

替换 `recallViaMultiRecall` 占位：

```java
    /** M 通道：多路召回。深度 = recallTopK ×（精排可用 ? 扩展倍数 : 1），由管线算好传给 router。 */
    private List<EmbeddingMatch<TextSegment>> recallViaMultiRecall(String query,
            RetrievalOverrides o, boolean rerankOn) {
        int depth = deps.recallConfig().getRecallTopK()
                * (rerankOn ? deps.rerankConfig().getRerankExpansionFactor() : 1);
        List<String> modes = o.recallModes() != null ? o.recallModes()
                : deps.recallConfig().getRecallModes();
        return deps.multiRecallRouter().recall(query, modes, depth);
    }
```

- [ ] **Step 4: 跑测试确认通过**

Run: `mvn test -Dtest=RetrievalPipelineTest`
Expected: 13 个测试全 PASS。

- [ ] **Step 5: 全量回归 + 提交**

Run: `mvn test`（全绿）
```bash
git add src/main/java/me/maxt/rag/web/service/vector/RetrievalPipeline.java src/test/java/me/maxt/rag/web/service/vector/RetrievalPipelineTest.java
git commit -m "新增: RetrievalPipeline M 通道，三通道编排齐备"
```

---

### Task 5: RAGService 瘦身 + 组装根接线

**Files:**
- Modify: `src/main/java/me/maxt/rag/web/service/RAGService.java`（整文件重写）
- Modify: `src/main/java/me/maxt/rag/web/WebApplication.java:176-236`（总是构造 router；构造 pipeline；新 RAGService 接线）
- Test: `src/test/java/me/maxt/rag/web/service/RAGServiceTest.java`（整文件重写）

**Interfaces:**
- Consumes: Task 1-4 的 `RetrievalPipeline`；`shared.Assistant`（AiServices）；`MessageWindowChatMemory`。
- Produces: `RAGService(RetrievalPipeline, ChatModel, RetrievalConfig)`；`answerWithSources` 三签名保留；`AnswerWithSources` 引用 `RetrievalPipeline.Source`。删除 `answer(String)`、4 个旧构造、`searchAndCollect`、`rerankIfAvailable`、`toSource`、`contentRetriever` 装配。

- [ ] **Step 1: 重写 RAGServiceTest**

旧 8 个测试的检索断言已迁移至 RetrievalPipelineTest（Task 1-4），本文件重写为对话组装测试（ChatModel 桩沿用旧写法）：

```java
package me.maxt.rag.web.service;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.ChatResponseMetadata;
import dev.langchain4j.model.output.TokenUsage;
import me.maxt.rag.web.config.RetrievalConfig;
import me.maxt.rag.web.service.vector.RetrievalPipeline;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RAGServiceTest {

    private ChatModel chatModel;
    private RetrievalPipeline pipeline;
    private RetrievalConfig config;

    @BeforeEach
    void setUp() {
        config = mock(RetrievalConfig.class);
        when(config.getMemorySize()).thenReturn(10);

        chatModel = mock(ChatModel.class);
        ChatResponseMetadata metadata = ChatResponseMetadata.builder()
                .tokenUsage(new TokenUsage(10, 10)).build();
        ChatResponse chatResponse = ChatResponse.builder()
                .aiMessage(AiMessage.from("stub answer"))
                .metadata(metadata).build();
        when(chatModel.chat(any(ChatRequest.class))).thenReturn(chatResponse);

        pipeline = mock(RetrievalPipeline.class);
    }

    @Test
    void shouldComposePromptWithReferenceMaterials() {
        when(pipeline.retrieve(eq("问题"), any())).thenReturn(List.of(
                new RetrievalPipeline.Source("a.txt", "资料甲", 0.9),
                new RetrievalPipeline.Source("b.txt", "资料乙", 0.8)));

        RAGService service = new RAGService(pipeline, chatModel, config);
        RAGService.AnswerWithSources result = service.answerWithSources("问题");

        ArgumentCaptor<ChatRequest> captor = ArgumentCaptor.forClass(ChatRequest.class);
        verify(chatModel).chat(captor.capture());
        String prompt = captor.getValue().messages().get(captor.getValue().messages().size() - 1).text();
        assertThat(prompt).contains("参考资料");
        assertThat(prompt).contains("[1] 资料甲");
        assertThat(prompt).contains("[2] 资料乙");
        assertThat(prompt).contains("问题：问题");
        assertThat(result.answer).isEqualTo("stub answer");
        assertThat(result.sources).hasSize(2);
        assertThat(result.sources.get(0).fileName()).isEqualTo("a.txt");
    }

    @Test
    void shouldFallbackWhenNoSources() {
        when(pipeline.retrieve(anyString(), any())).thenReturn(List.of());

        RAGService service = new RAGService(pipeline, chatModel, config);
        RAGService.AnswerWithSources result = service.answerWithSources("问题");

        assertThat(result.answer).isEqualTo("stub answer");
        assertThat(result.sources).isEmpty();
        ArgumentCaptor<ChatRequest> captor = ArgumentCaptor.forClass(ChatRequest.class);
        verify(chatModel).chat(captor.capture());
        String prompt = captor.getValue().messages().get(captor.getValue().messages().size() - 1).text();
        assertThat(prompt).contains("（无参考资料）");
    }

    @Test
    void shouldPassOverridesThrough() {
        when(pipeline.retrieve(anyString(), any())).thenReturn(List.of());

        RAGService service = new RAGService(pipeline, chatModel, config);
        service.answerWithSources("q", "hyde", List.of("dense"));

        verify(pipeline).retrieve(eq("q"), eq(new RetrievalPipeline.RetrievalOverrides("hyde", List.of("dense"))));
    }
}
```

（若 langchain4j 1.12.1 的 `ChatMessage.text()` 取值 API 名不同，以编译器提示为准调整——断言意图是"发给 LLM 的最后一条 user 消息含参考资料"。）

- [ ] **Step 2: 跑测试确认失败**

Run: `mvn test -Dtest=RAGServiceTest`
Expected: 编译失败（新构造不存在）。

- [ ] **Step 3: 重写 RAGService**

```java
package me.maxt.rag.web.service;

import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.service.AiServices;
import me.maxt.rag.web.config.RetrievalConfig;
import me.maxt.rag.web.service.vector.RetrievalPipeline;
import shared.Assistant;

import java.util.List;

/**
 * RAG 对话门面：检索委托 {@link RetrievalPipeline}（唯一事实源），
 * 将检索结果组装为"参考资料 + 问题"的提示词交给 LLM，并附带多轮对话记忆。
 *
 * <p>LLM 看到的上下文与返回给调用方的 sources 恒等。</p>
 *
 * @author maxt
 * @since 1.0
 */
public class RAGService {

    private static final String PROMPT_TEMPLATE = """
            请根据以下参考资料回答问题。若参考资料不足以回答，请如实说明。

            参考资料：
            %s

            问题：%s""";

    private final RetrievalPipeline pipeline;
    private final Assistant assistant;

    /**
     * @param pipeline  检索管线（唯一事实源）
     * @param chatModel 聊天模型
     * @param config    检索配置（取记忆窗口大小）
     */
    public RAGService(RetrievalPipeline pipeline, ChatModel chatModel, RetrievalConfig config) {
        this.pipeline = pipeline;
        this.assistant = AiServices.builder(Assistant.class)
                .chatModel(chatModel)
                .chatMemory(MessageWindowChatMemory.withMaxMessages(config.getMemorySize()))
                .build();
    }

    /** 根据用户问题生成回答，并附带检索到的文档来源（全部覆盖项回退配置默认值）。 */
    public AnswerWithSources answerWithSources(String query) {
        return answerWithSources(query, null);
    }

    /** 根据用户问题生成回答，并附带检索到的文档来源（覆盖查询增强模式）。 */
    public AnswerWithSources answerWithSources(String query, String enhancementMode) {
        return answerWithSources(query, enhancementMode, null);
    }

    /**
     * 根据用户问题生成回答，并附带检索到的文档来源。
     *
     * @param query           用户问题
     * @param enhancementMode 查询增强模式（可选，null 时使用配置默认值）
     * @param recallModes     多路召回模式列表（可选，null 时使用配置默认模式）
     * @return 包含回答文本和来源列表的结果对象
     */
    public AnswerWithSources answerWithSources(String query, String enhancementMode, List<String> recallModes) {
        List<RetrievalPipeline.Source> sources =
                pipeline.retrieve(query, new RetrievalPipeline.RetrievalOverrides(enhancementMode, recallModes));
        String answer = assistant.answer(composePrompt(query, sources));
        return new AnswerWithSources(answer, sources);
    }

    private String composePrompt(String query, List<RetrievalPipeline.Source> sources) {
        StringBuilder materials = new StringBuilder();
        if (sources.isEmpty()) {
            materials.append("（无参考资料）");
        } else {
            for (int i = 0; i < sources.size(); i++) {
                materials.append("[").append(i + 1).append("] ").append(sources.get(i).text()).append("\n");
            }
        }
        return PROMPT_TEMPLATE.formatted(materials, query);
    }

    /** 带来源引用的回答结果 DTO。 */
    public static class AnswerWithSources {
        /** AI 生成的回答文本 */
        public String answer;
        /** 检索到的参考来源列表 */
        public List<RetrievalPipeline.Source> sources;

        /**
         * 构造带来源引用的回答结果。
         *
         * @param answer AI 生成的回答文本
         * @param sources 检索到的参考来源列表
         */
        public AnswerWithSources(String answer, List<RetrievalPipeline.Source> sources) {
            this.answer = answer;
            this.sources = sources;
        }
    }
}
```

（旧 `Source` 类整体删除——调用方一律改用 `RetrievalPipeline.Source`。）

- [ ] **Step 4: 接线 WebApplication**

`WebApplication.java` 多路召回段（176-211 行区域）改为**无条件构造** router，然后构造 pipeline 与新 RAGService（替换 235-236 行）：

```java
        // 多路召回：总是构造（是否启用由 RecallConfig.isMultiRecallEnabled 表达）
        Map<String, RecallStrategy> registry = new LinkedHashMap<>();
        registry.put("dense", new DenseRecallStrategy(storeManager, embeddingModel));
        if (milvusSession.nativeClient() != null) {
            registry.put("sparse", new SparseRecallStrategy(
                    milvusSession::nativeClient, config.getMilvusCollectionName()));
        }
        registry.put("graph", new GraphRecallStrategy(kgService, lightRagBridge,
                config.getLightRagQueryMode()));
        this.multiRecallRouter = new MultiRecallRouter(config, registry);

        // 检索管线（唯一事实源）+ 对话门面
        RetrievalPipeline retrievalPipeline = new RetrievalPipeline(new RetrievalPipeline.Deps(
                storeManager, embeddingModel, config,
                enhancementRouter, config,
                multiRecallRouter, config,
                crossEncoderReranker, config));
        this.ragService = new RAGService(retrievalPipeline, chatModel, config);
```

（AppConfig 同时实现 RetrievalConfig/QueryEnhancementConfig/RecallConfig/RerankConfig，故四个配置位都传 `config`；`if (config.isMultiRecallEnabled())` 判断删除。）

- [ ] **Step 5: 跑测试确认通过 + 全量回归**

Run: `mvn test`
Expected: 全绿（RAGServiceTest 3 个新测试 PASS；EvaluationTest 属 evaluation profile 不在默认跑，但会被编译——若编译报错（它还在用 4 参构造），顺手按 Task 6 Step 3 的最终接法改掉它的构造，不引入额外行为）。

- [ ] **Step 6: 提交**

```bash
git add src/main/java/me/maxt/rag/web/service/RAGService.java src/main/java/me/maxt/rag/web/WebApplication.java src/test/java/me/maxt/rag/web/service/RAGServiceTest.java
git commit -m "重构: RAGService 瘦身为对话门面，检索编排移交 RetrievalPipeline"
```

---

### Task 6: EvaluationPipeline 接入 retrieve 入口

**Files:**
- Modify: `src/main/java/me/maxt/rag/web/service/evaluation/EvaluationPipeline.java:19-38,80-107`
- Test: `src/test/java/me/maxt/rag/web/service/evaluation/EvaluationPipelineTest.java`（新建）
- Test: `src/test/java/me/maxt/rag/web/evaluation/EvaluationTest.java:48-96`（构造点改为禁用桩等价语义；若 Task 5 已改则核对）

**Interfaces:**
- Consumes: Task 5 的 `RAGService`；`RetrievalPipeline.retrieve`。
- Produces: `EvaluationPipeline(config, datasetLoader, seeder, retrievalEvaluator, answerQualityEvaluator, baselineManager, ragService, retrievalPipeline)`（ragService 之后追加 pipeline 参数）。

- [ ] **Step 1: 写失败测试**

新建 `EvaluationPipelineTest.java`：

```java
package me.maxt.rag.web.service.evaluation;

import me.maxt.rag.web.config.EvaluationConfig;
import me.maxt.rag.web.service.RAGService;
import me.maxt.rag.web.service.vector.RetrievalPipeline;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class EvaluationPipelineTest {

    private DatasetLoader datasetLoader;
    private KnowledgeBaseSeeder seeder;
    private RetrievalEvaluator retrievalEvaluator;
    private AnswerQualityEvaluator answerQualityEvaluator;
    private BaselineManager baselineManager;
    private EvaluationConfig config;
    private RAGService ragService;
    private RetrievalPipeline retrievalPipeline;

    @BeforeEach
    void setUp() {
        config = mock(EvaluationConfig.class);
        datasetLoader = mock(DatasetLoader.class);
        seeder = mock(KnowledgeBaseSeeder.class);
        retrievalEvaluator = mock(RetrievalEvaluator.class);
        answerQualityEvaluator = mock(AnswerQualityEvaluator.class);
        baselineManager = mock(BaselineManager.class);
        ragService = mock(RAGService.class);
        retrievalPipeline = mock(RetrievalPipeline.class);

        when(seeder.seed(any())).thenReturn(1);
        when(answerQualityEvaluator.isAvailable()).thenReturn(true);
        when(retrievalPipeline.retrieve(anyString(), any())).thenReturn(List.of());
        when(retrievalEvaluator.evaluate(any(), any())).thenReturn(java.util.Map.of(
                "recallAtK", 1.0, "precisionAtK", 1.0, "mrr", 1.0, "ndcgAtK", 1.0));
    }

    private EvaluationPipeline pipeline() {
        return new EvaluationPipeline(config, datasetLoader, seeder, retrievalEvaluator,
                answerQualityEvaluator, baselineManager, ragService, retrievalPipeline);
    }

    @Test
    void shouldSkipLlmWhenAnswerQualitySkipped() {
        // skipAnswerQuality=true → 只调 pipeline.retrieve，不生成答案
        pipeline().run("md", null, null, false, true);

        verify(retrievalPipeline).retrieve(anyString(), any());
        verify(ragService, never()).answerWithSources(anyString());
    }

    @Test
    void shouldSkipLlmWhenEvaluatorUnavailable() {
        when(answerQualityEvaluator.isAvailable()).thenReturn(false);

        pipeline().run("md", null, null, false, false);

        verify(retrievalPipeline).retrieve(anyString(), any());
        verify(ragService, never()).answerWithSources(anyString());
    }

    @Test
    void shouldGenerateAnswerWhenQualityEvaluated() {
        when(ragService.answerWithSources(anyString())).thenReturn(
                new RAGService.AnswerWithSources("answer", List.of()));

        pipeline().run("md", null, null, false, false);

        verify(ragService).answerWithSources(anyString());
    }
}
```

（`DatasetLoader.load/validate`、`BaselineManager.loadOrCreate/save/compare/timestamp`、`TestCase` record 的 mock 桩按编译器提示补齐——意图：run() 走到逐用例循环。若 `run` 的文件落盘逻辑难以纯 mock 驱动，允许给 `BaselineManager.save` 与 `loadOrCreate` 打桩返回 null/直接返回，重点断言的是分流。）

- [ ] **Step 2: 跑测试确认失败**

Run: `mvn test -Dtest=EvaluationPipelineTest`
Expected: 编译失败（构造签名无 pipeline 参数）。

- [ ] **Step 3: 实现**

`EvaluationPipeline` 构造追加 `RetrievalPipeline retrievalPipeline` 字段与参数（放 `ragService` 之后）；`run()` 逐用例段（原 80-107 行）改为分流：

```java
        for (TestCase tc : dataset.testCases()) {
            // 答案质量要评估才调 LLM；纯检索指标走 pipeline.retrieve（零 LLM 调用）
            String answer = null;
            List<String> contexts = null;
            List<RetrievalPipeline.Source> sources;
            if (!skipAnswerQuality && answerQualityEvaluator.isAvailable()) {
                AnswerWithSources result = ragService.answerWithSources(tc.query());
                answer = result.answer;
                sources = result.sources;
                contexts = sources.stream().map(RetrievalPipeline.Source::text).toList();
            } else {
                sources = retrievalPipeline.retrieve(tc.query(), null);
            }
            List<String> retrievedDocNames = sources.stream()
                    .map(RetrievalPipeline.Source::fileName)
                    .toList();

            // 检索指标
            Map<String, Double> retrievalScores = retrievalEvaluator.evaluate(tc, retrievedDocNames);

            // 答案质量（answer 为 null 时跳过，逻辑同原来 faithfulness/rel 判空）
            Integer faith = null;
            Integer rel = null;
            if (answer != null && contexts != null) {
                Map<String, QualityScore> aq = answerQualityEvaluator.evaluate(tc.query(), answer, contexts);
                if (aq.containsKey("faithfulness")) {
                    faith = aq.get("faithfulness").score();
                    faithfulnessScores.add(faith);
                }
                if (aq.containsKey("answerRelevancy")) {
                    rel = aq.get("answerRelevancy").score();
                    relevancyScores.add(rel);
                }
            }
            ...（caseScore 组装与原逻辑相同）
```

（`isAvailable()` 条件从"是否评估质量"上移到"是否调 LLM"，与原行为差异仅在：原来不可用时仍白生成答案。）

`EvaluationTest.java` 构造点同步更新（保持"朴素检索、无精排"的确定性评估语义，等价旧 4 参构造）：

```java
        // 检索管线与 RAGService：禁用增强/多路召回、精排不可用（等价旧 4 参朴素语义，保证确定性）
        QueryEnhancementConfig disabledEnh = mock(QueryEnhancementConfig.class);
        when(disabledEnh.isQueryEnhancementEnabled()).thenReturn(false);
        RecallConfig disabledRecall = mock(RecallConfig.class);
        when(disabledRecall.isMultiRecallEnabled()).thenReturn(false);
        Reranker unavailableReranker = mock(Reranker.class);
        when(unavailableReranker.isAvailable()).thenReturn(false);
        MultiRecallRouter emptyRouter = new MultiRecallRouter(disabledRecall, java.util.Map.of());
        RetrievalPipeline retrievalPipeline = new RetrievalPipeline(new RetrievalPipeline.Deps(
                storeManager, embeddingModel, appConfig,
                mock(QueryEnhancementRouter.class), disabledEnh,
                emptyRouter, disabledRecall,
                unavailableReranker, appConfig));
        RAGService ragService = new RAGService(retrievalPipeline, chatModel, appConfig);
        ...
        EvaluationPipeline pipeline = new EvaluationPipeline(appConfig, datasetLoader, seeder,
                retrievalEvaluator, answerQualityEvaluator, baselineManager, ragService, retrievalPipeline);
```

- [ ] **Step 4: 跑测试确认通过**

Run: `mvn test -Dtest=EvaluationPipelineTest`
Expected: 3 个测试 PASS。

- [ ] **Step 5: 全量回归 + 提交**

Run: `mvn test`（全绿）
```bash
git add src/main/java/me/maxt/rag/web/service/evaluation/EvaluationPipeline.java src/test/java/me/maxt/rag/web/service/evaluation/EvaluationPipelineTest.java src/test/java/me/maxt/rag/web/evaluation/EvaluationTest.java
git commit -m "重构: 评估管线接入 retrieve 入口，纯检索指标评估零 LLM 调用"
```

---

### Task 7: 端到端验证 + 基线重建 + 文档收尾

**Files:**
- Create: `docs/reviews/retrieval-pipeline-verification-20260910.md`
- Modify: `CONTEXT.md`（新术语）
- Modify: `docs/reviews/architecture-backlog.md`（候选 3 状态）
- Modify: `CLAUDE.md`（关键入口）、`README.md`（检索架构段，如有描述旧三分支处）

**Interfaces:**
- Consumes: 全部前序任务产物。
- Produces: 验证报告、更新后的术语与文档、重建的评估基线（`src/test/resources/evaluation/*/baseline.json`）。

- [ ] **Step 1: 全量测试**

Run: `mvn test`
Expected: 全绿。记录最终测试数（对照旧基线 158：迁移 8 个 RAGService 检索测试 → RetrievalPipelineTest 13 个 + RAGServiceTest 3 个 + EvaluationPipelineTest 3 个 + MultiRecallRouterTest +1）。

- [ ] **Step 2: 端到端冒烟（真实链路）**

1. `docker ps` 确认 myaidemo2-milvus 容器组在跑（不在则 `docker compose up -d`）
2. `mvn clean package -DskipTests` 后 `java -jar target/MyAIDemo2-1.0-SNAPSHOT.jar`
3. `curl -X POST http://localhost:8080/api/chat -H "Content-Type: application/json" -d '{"query":"任一已入库问题"}'`
4. 断言：响应 JSON 含 `answer` 与 `sources[]`，字段名 `fileName/text/score` 与改造前一致；观察日志确认走统一管线
5. 带 `enhancement` 与 `recall` 覆盖参数各发一次请求，确认覆盖项生效

- [ ] **Step 3: 评估基线重建**

Run: `mvn test -P evaluation -Devaluation.updateBaseline=true`
（行为变化 ①②④ 会体现在数字上。）对比新旧基线 recallAtK/MRR/NDCG，数字与解读写进验证报告。若 `RAG_LLM_API_KEY` 未设或网络异常导致评估失败，如实记录环境阻塞项，不强跑。

- [ ] **Step 4: 写验证报告**

`docs/reviews/retrieval-pipeline-verification-20260910.md`：测试数变化、冒烟结果（含 /api/chat 响应样例）、基线前后对比、行为变化 4 项的实际观察、遗留问题。

- [ ] **Step 5: 文档收尾**

1. `CONTEXT.md` 追加术语"检索管线（RetrievalPipeline）"：唯一检索事实源、通道为内部接缝、候选池仅在精排可用时扩展；*避免叫*：RetrievalService（不表达"编排"）、SearchOrchestrator（丢"检索增强"语义）
2. `docs/reviews/architecture-backlog.md` 候选 3 状态改 ✅ 并附提交区间
3. `CLAUDE.md` 关键入口表加 `RetrievalPipeline` 行、`RAGService` 行改述为"对话门面"；`mvn test` 数量同步
4. `README.md` 检索/对话流程描述如有旧三分支表述则更新

- [ ] **Step 6: 提交**

```bash
git add CONTEXT.md CLAUDE.md README.md docs/reviews/architecture-backlog.md docs/reviews/retrieval-pipeline-verification-20260910.md src/test/resources/evaluation
git commit -m "文档: 候选 3 检索编排深化验证报告与基线重建，术语入库"
```

---

## Self-Review 记录

- **Spec 覆盖**：五问题（instanceof×2→Task 3/5；三分支→Task 1-4；望远镜构造→Task 5；精排不一致→Task 1 统一后处理；双轨检索→Task 5 composePrompt）；三决策（统一生效→Task 1；事实源→Task 5；评估接入→Task 6）；深度表三行→Task 1（P/E 单变体）、Task 2（E 多变体）、Task 4（M）；4 项行为变化→Task 1/2 落地、Task 7 观察记录。✓
- **占位符扫描**：Task 1/2/4 的 `UnsupportedOperationException` 是刻意的 TDD 中间态（先测后实现），不是计划占位；Task 6 Step 1 对 mock 桩的"按编译器提示补齐"给出了桩的意图与断言重点，属可执行指引。✓
- **类型一致性**：`recall(query, modes, perStrategyTopK)` 在 Task 3 产出、Task 4 消费；`Deps`/`RetrievalOverrides`/`Source` 三 record 从 Task 1 起贯穿；`Source` 访问器（`fileName()`/`text()`）在 Task 5/6 的调用方均用 record 风格。✓
- **顺序风险**：Task 3 对 RAGService 的 instanceof 临时适配在 Task 5 被整体删除，已注明；Task 5 Step 5 预判了 EvaluationTest 编译破裂的联动修复。✓
