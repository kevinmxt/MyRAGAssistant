# 重排序增强（Re-rank）实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在多路召回/RRF 融合之后，插入 Cross-Encoder 精排层，提升送给 LLM 的 Top-K 片段相关性。

**Architecture:** 新增 `Reranker` 接口和 `CrossEncoderReranker` 实现，使用 ONNX Runtime 加载 bge-reranker-v2-m3 模型，启动时自动检测模型文件（存在则加载，不存在则日志警告 + 降级跳过）。插入点在 `RAGService.answerWithSources` 中，召回完成后、LLM 调用前。

**Tech Stack:** Java 17, langchain4j 1.12.1, ONNX Runtime Java, DJL HuggingFace Tokenizers, JUnit 5 + AssertJ + Mockito

## Global Constraints

- 重排序始终生效，无 enable/disable 开关；模型缺失自动降级跳过
- 粗召回多拿 candidates = recallTopK × expansionFactor（默认 3x）
- `config.json` 新增 `rerank` 段；环境变量前缀 `RAG_RERANK_`
- 遵循现有模式：接口 + 单一实现，构造函数注入，logback 日志
- Java 17，使用 `var` 和 `toList()`

---

### Task 1: RerankConfig 配置接口

**Files:**
- Create: `src/main/java/me/maxt/rag/web/config/RerankConfig.java`

**Interfaces:**
- Produces: `RerankConfig` 接口 — `getRerankModelPath(): String`, `getRerankExpansionFactor(): int`, `getRerankTopK(): int`

- [ ] **Step 1: 创建 RerankConfig 接口**

```java
package me.maxt.rag.web.config;

public interface RerankConfig {
    /** ONNX 精排模型文件路径，默认 models/bge-reranker-v2-m3 */
    String getRerankModelPath();

    /** 粗召回扩展倍数，recallTopK × 此值 = 进入精排的候选数量，默认 3 */
    int getRerankExpansionFactor();

    /** 精排后返回给 LLM 的结果数，默认 5 */
    int getRerankTopK();
}
```

- [ ] **Step 2: 编译验证**

Run: `mvn compile -q`
Expected: 编译成功

- [ ] **Step 3: Commit**

```bash
git add src/main/java/me/maxt/rag/web/config/RerankConfig.java
git commit -m "feat: 新增 RerankConfig 配置接口"
```

---

### Task 2: AppConfig 实现 RerankConfig

**Files:**
- Modify: `src/main/java/me/maxt/rag/web/config/AppConfig.java`

**Interfaces:**
- Consumes: `RerankConfig` 接口
- Produces: `AppConfig` 实现 `RerankConfig`，包含字段、默认值、JSON 解析、环境变量覆盖、getter

**需要修改的位置（行号基于当前文件）：**

1. 类声明 `implements` 追加 `RerankConfig`（第28行）
2. 添加字段（第158行后）
3. `AppConfig()` 构造函数添加默认值（第205行后）
4. `applyFileConfig` 添加 `rerank` JSON 解析（第332行后）
5. `applyEnvOverrides` 添加环境变量覆盖（第380行后）
6. 添加 getter（第508行后）

- [ ] **Step 1: 修改类声明，追加 RerankConfig**

```java
public class AppConfig implements LlmConfig, RetrievalConfig, DocumentConfig, ServerConfig, QueryEnhancementConfig, MilvusConfig, RecallConfig, RerankConfig {
```

- [ ] **Step 2: 在字段区域末尾（第158行后）添加重排序字段**

```java
    // ========== 重排序参数 ==========

    /** ONNX 精排模型路径，可通过环境变量 RAG_RERANK_MODEL_PATH 覆盖 */
    private String rerankModelPath;

    /** 粗召回扩展倍数，可通过环境变量 RAG_RERANK_EXPANSION_FACTOR 覆盖 */
    private int rerankExpansionFactor;

    /** 精排后返回给 LLM 的结果数，可通过环境变量 RAG_RERANK_TOP_K 覆盖 */
    private int rerankTopK;
```

- [ ] **Step 3: 在构造函数默认值区域末尾（第205行后）添加默认值**

```java
        this.rerankModelPath = "models/bge-reranker-v2-m3";
        this.rerankExpansionFactor = 3;
        this.rerankTopK = 5;
```

- [ ] **Step 4: 在 applyFileConfig 末尾（第332行后，`}` 闭合前）添加 JSON 解析**

```java
        Map<String, Object> rerank = (Map<String, Object>) fileConfig.get("rerank");
        if (rerank != null) {
            config.rerankModelPath = getString(rerank, "modelPath", config.rerankModelPath);
            config.rerankExpansionFactor = getInt(rerank, "expansionFactor", config.rerankExpansionFactor);
            config.rerankTopK = getInt(rerank, "topK", config.rerankTopK);
        }
```

- [ ] **Step 5: 在 applyEnvOverrides 末尾（第380行后）添加环境变量覆盖**

```java
        config.rerankModelPath = env("RAG_RERANK_MODEL_PATH", config.rerankModelPath);
        config.rerankExpansionFactor = envInt("RAG_RERANK_EXPANSION_FACTOR", config.rerankExpansionFactor);
        config.rerankTopK = envInt("RAG_RERANK_TOP_K", config.rerankTopK);
```

- [ ] **Step 6: 在 getter 区域末尾（第508行后）添加 getter**

```java
    /** @return ONNX 精排模型路径 */
    public String getRerankModelPath() { return rerankModelPath; }
    /** @return 粗召回扩展倍数 */
    public int getRerankExpansionFactor() { return rerankExpansionFactor; }
    /** @return 精排后返回给 LLM 的结果数 */
    public int getRerankTopK() { return rerankTopK; }
```

- [ ] **Step 7: 编译验证**

Run: `mvn compile -q`
Expected: 编译成功

- [ ] **Step 8: Commit**

```bash
git add src/main/java/me/maxt/rag/web/config/AppConfig.java
git commit -m "feat: AppConfig 实现 RerankConfig，支持 JSON 和环境变量配置"
```

---

### Task 3: AppConfigTest 新增重排序配置测试

**Files:**
- Modify: `src/test/java/me/maxt/rag/web/config/AppConfigTest.java`

**Interfaces:**
- Consumes: `RerankConfig` getter

- [ ] **Step 1: 先确认 AppConfigTest 现有结构**

Read the file to understand the existing test patterns.

- [ ] **Step 2: 添加重排序默认值测试**

```java
    @Test
    void shouldHaveDefaultRerankConfig() {
        AppConfig config = new AppConfig();
        assertThat(config.getRerankModelPath()).isEqualTo("models/bge-reranker-v2-m3");
        assertThat(config.getRerankExpansionFactor()).isEqualTo(3);
        assertThat(config.getRerankTopK()).isEqualTo(5);
    }
```

- [ ] **Step 3: 运行测试验证**

Run: `mvn test -pl . -Dtest=AppConfigTest#shouldHaveDefaultRerankConfig -DfailIfNoTests=false`
Expected: PASS

- [ ] **Step 4: Commit**

```bash
git add src/test/java/me/maxt/rag/web/config/AppConfigTest.java
git commit -m "test: AppConfig 重排序默认值测试"
```

---

### Task 4: Reranker 接口

**Files:**
- Create: `src/main/java/me/maxt/rag/web/service/vector/rerank/Reranker.java`

**Interfaces:**
- Produces: `Reranker` 接口 — `name(): String`, `isAvailable(): boolean`, `rerank(query, candidates, topK): List<EmbeddingMatch<TextSegment>>`

- [ ] **Step 1: 创建 Reranker 接口**

```java
package me.maxt.rag.web.service.vector.rerank;

import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.store.embedding.EmbeddingMatch;

import java.util.List;

public interface Reranker {
    String name();

    /** 精排模型是否可用（模型文件存在且加载成功） */
    boolean isAvailable();

    /**
     * 对粗召回候选集进行精细重排序。
     * @param query 原始查询
     * @param candidates 粗召回候选集
     * @param topK 返回数量
     * @return 精排后的 topK 结果，按相关性降序
     */
    List<EmbeddingMatch<TextSegment>> rerank(String query, List<EmbeddingMatch<TextSegment>> candidates, int topK);
}
```

- [ ] **Step 2: 编译验证**

Run: `mvn compile -q`
Expected: 编译成功

- [ ] **Step 3: Commit**

```bash
git add src/main/java/me/maxt/rag/web/service/vector/rerank/Reranker.java
git commit -m "feat: 新增 Reranker 重排序接口"
```

---

### Task 5: 添加 ONNX Runtime 和 DJL Tokenizers 依赖

**Files:**
- Modify: `pom.xml`

**新增两个依赖：**
- `com.microsoft.onnxruntime:onnxruntime:1.18.0` — ONNX Runtime Java 绑定
- `ai.djl.huggingface:tokenizers:0.31.0` — HuggingFace BERT/WordPiece 分词器

- [ ] **Step 1: 在 pom.xml dependencies 中添加**

```xml
    <dependency>
      <groupId>com.microsoft.onnxruntime</groupId>
      <artifactId>onnxruntime</artifactId>
      <version>1.18.0</version>
    </dependency>

    <dependency>
      <groupId>ai.djl.huggingface</groupId>
      <artifactId>tokenizers</artifactId>
      <version>0.31.0</version>
    </dependency>
```

- [ ] **Step 2: 验证依赖下载成功**

Run: `mvn dependency:resolve -q`
Expected: BUILD SUCCESS

- [ ] **Step 3: Commit**

```bash
git add pom.xml
git commit -m "build: 添加 onnxruntime 和 djl-tokenizers 依赖"
```

---

### Task 6: CrossEncoderReranker 实现

**Files:**
- Create: `src/main/java/me/maxt/rag/web/service/vector/rerank/CrossEncoderReranker.java`

**Interfaces:**
- Consumes: `Reranker` 接口, `RerankConfig` 配置
- Produces: `CrossEncoderReranker` — ONNX Cross-Encoder 精排器

**实现要点：**
- 构造函数接收 `RerankConfig`，尝试加载 ONNX 模型和 tokenizer
- 模型文件不存在 → `log.warn` + available=false
- `rerank()` 中 available=false → 直接返回 `candidates.stream().limit(topK).toList()`
- `rerank()` 中 candidates 为空 → 返回空列表
- 批量 tokenize (query, passage) 对，一次 ONNX 推理，sigmoid 转分数
- tokenizer.json 随 ONNX 模型一起放在 modelPath 目录下（bge-reranker-v2-m3 导出时自带）

- [ ] **Step 1: 创建 CrossEncoderReranker**

```java
package me.maxt.rag.web.service.vector.rerank;

import ai.djl.huggingface.tokenizers.Encoding;
import ai.djl.huggingface.tokenizers.HuggingFaceTokenizer;
import ai.onnxruntime.*;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import me.maxt.rag.web.config.RerankConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.nio.file.Path;
import java.util.*;

public class CrossEncoderReranker implements Reranker {

    private static final Logger log = LoggerFactory.getLogger(CrossEncoderReranker.class);
    private static final int MAX_SEQ_LENGTH = 512;

    private final OrtEnvironment env;
    private final OrtSession session;
    private final HuggingFaceTokenizer tokenizer;
    private final boolean available;

    public CrossEncoderReranker(RerankConfig config) {
        String modelPath = config.getRerankModelPath();
        File modelDir = new File(modelPath);
        File onnxFile = new File(modelDir, "model.onnx");
        File tokenizerFile = new File(modelDir, "tokenizer.json");

        if (!onnxFile.exists()) {
            log.warn("精排模型未找到 ({}), 重排序已降级跳过", onnxFile.getAbsolutePath());
            this.env = null;
            this.session = null;
            this.tokenizer = null;
            this.available = false;
            return;
        }

        try {
            this.env = OrtEnvironment.getEnvironment();
            var sessionOptions = new OrtSession.SessionOptions();
            this.session = env.createSession(onnxFile.getAbsolutePath(), sessionOptions);
            this.tokenizer = tokenizerFile.exists()
                    ? HuggingFaceTokenizer.newInstance(tokenizerFile.toPath())
                    : HuggingFaceTokenizer.newInstance(Path.of(modelPath));
            this.available = true;
            log.info("精排模型已加载: {} ({} 候选扩倍数, topK={})",
                    onnxFile.getAbsolutePath(), config.getRerankExpansionFactor(), config.getRerankTopK());
        } catch (OrtException e) {
            log.error("加载精排模型失败: {}", e.getMessage());
            throw new RuntimeException("Failed to load reranker ONNX model", e);
        }
    }

    @Override
    public String name() {
        return "cross-encoder";
    }

    @Override
    public boolean isAvailable() {
        return available;
    }

    @Override
    public List<EmbeddingMatch<TextSegment>> rerank(String query, List<EmbeddingMatch<TextSegment>> candidates, int topK) {
        if (candidates.isEmpty()) {
            return List.of();
        }
        if (!available) {
            return candidates.stream().limit(topK).toList();
        }

        int n = candidates.size();
        float[][] inputIds = new float[n][MAX_SEQ_LENGTH];
        float[][] attentionMask = new float[n][MAX_SEQ_LENGTH];
        float[][] tokenTypeIds = new float[n][MAX_SEQ_LENGTH];

        for (int i = 0; i < n; i++) {
            String passage = candidates.get(i).embedded().text();
            Encoding encoding = tokenizer.encode(query, passage);
            long[] ids = encoding.getIds();
            long[] attention = encoding.getAttentionMask();
            long[] typeIds = encoding.getTypeIds();

            int len = Math.min(ids.length, MAX_SEQ_LENGTH);
            for (int j = 0; j < len; j++) {
                inputIds[i][j] = ids[j];
                attentionMask[i][j] = attention[j];
                tokenTypeIds[i][j] = typeIds[j];
            }
        }

        try {
            var inputIdsTensor = OnnxTensor.createTensor(env, inputIds);
            var attentionMaskTensor = OnnxTensor.createTensor(env, attentionMask);
            var tokenTypeIdsTensor = OnnxTensor.createTensor(env, tokenTypeIds);

            var inputs = Map.<String, OnnxTensor>of(
                    "input_ids", inputIdsTensor,
                    "attention_mask", attentionMaskTensor,
                    "token_type_ids", tokenTypeIdsTensor
            );

            var results = session.run(inputs);
            var logits = (float[][]) results.get(0).getValue();

            // sigmoid + 按分数降序取 topK
            List<ScoredMatch> scored = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                float sigmoidScore = 1.0f / (1.0f + (float) Math.exp(-logits[i][0]));
                scored.add(new ScoredMatch(sigmoidScore, candidates.get(i)));
            }

            scored.sort((a, b) -> Float.compare(b.score, a.score));

            return scored.stream()
                    .limit(topK)
                    .map(sm -> sm.match)
                    .toList();
        } catch (OrtException e) {
            log.error("精排推理失败: {}", e.getMessage());
            return candidates.stream().limit(topK).toList();
        }
    }

    private record ScoredMatch(float score, EmbeddingMatch<TextSegment> match) {}
}
```

- [ ] **Step 2: 编译验证**

Run: `mvn compile -q`
Expected: 编译成功

- [ ] **Step 3: Commit**

```bash
git add src/main/java/me/maxt/rag/web/service/vector/rerank/CrossEncoderReranker.java
git commit -m "feat: CrossEncoderReranker — ONNX Cross-Encoder 精排，模型缺失自动降级"
```

---

### Task 7: CrossEncoderReranker 单元测试

**Files:**
- Create: `src/test/java/me/maxt/rag/web/service/vector/rerank/CrossEncoderRerankerTest.java`

**Interfaces:**
- Consumes: `CrossEncoderReranker`, `RerankConfig`

Mock 策略：CrossEncoderReranker 内部使用 ONNX Runtime，无法轻易 mock 推理过程。测试模型不存在时的降级行为和空候选集处理。模型加载成功的单元测试需要真实模型文件（在集成测试中覆盖）。

- [ ] **Step 1: 创建测试类**

```java
package me.maxt.rag.web.service.vector.rerank;

import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import me.maxt.rag.web.config.RerankConfig;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class CrossEncoderRerankerTest {

    @Test
    @SuppressWarnings("unchecked")
    void shouldDegradeWhenModelNotFound() {
        RerankConfig config = mock(RerankConfig.class);
        when(config.getRerankModelPath()).thenReturn("./nonexistent/path");
        when(config.getRerankExpansionFactor()).thenReturn(3);
        when(config.getRerankTopK()).thenReturn(5);

        CrossEncoderReranker reranker = new CrossEncoderReranker(config);
        assertThat(reranker.isAvailable()).isFalse();
        assertThat(reranker.name()).isEqualTo("cross-encoder");
    }

    @Test
    @SuppressWarnings("unchecked")
    void shouldReturnCandidatesAsIsWhenNotAvailable() {
        RerankConfig config = mock(RerankConfig.class);
        when(config.getRerankModelPath()).thenReturn("./nonexistent/path");
        CrossEncoderReranker reranker = new CrossEncoderReranker(config);

        TextSegment seg1 = TextSegment.from("candidate 1");
        TextSegment seg2 = TextSegment.from("candidate 2");
        TextSegment seg3 = TextSegment.from("candidate 3");

        EmbeddingMatch<TextSegment> m1 = mock(EmbeddingMatch.class);
        when(m1.embedded()).thenReturn(seg1);
        when(m1.score()).thenReturn(0.9);
        EmbeddingMatch<TextSegment> m2 = mock(EmbeddingMatch.class);
        when(m2.embedded()).thenReturn(seg2);
        when(m2.score()).thenReturn(0.8);
        EmbeddingMatch<TextSegment> m3 = mock(EmbeddingMatch.class);
        when(m3.embedded()).thenReturn(seg3);
        when(m3.score()).thenReturn(0.7);

        var candidates = List.of(m1, m2, m3);
        var result = reranker.rerank("test query", candidates, 2);

        // 模型不可用时原样返回，切片到 topK
        assertThat(result).hasSize(2);
        assertThat(result.get(0).embedded().text()).isEqualTo("candidate 1");
        assertThat(result.get(1).embedded().text()).isEqualTo("candidate 2");
    }

    @Test
    @SuppressWarnings("unchecked")
    void shouldHandleEmptyCandidates() {
        RerankConfig config = mock(RerankConfig.class);
        when(config.getRerankModelPath()).thenReturn("./nonexistent/path");
        CrossEncoderReranker reranker = new CrossEncoderReranker(config);

        var result = reranker.rerank("test query", List.of(), 5);
        assertThat(result).isEmpty();
    }
}
```

- [ ] **Step 2: 运行测试**

Run: `mvn test -Dtest=CrossEncoderRerankerTest -DfailIfNoTests=false`
Expected: 3 tests PASS

- [ ] **Step 3: Commit**

```bash
git add src/test/java/me/maxt/rag/web/service/vector/rerank/CrossEncoderRerankerTest.java
git commit -m "test: CrossEncoderReranker 降级和空候选集测试"
```

---

### Task 8: MultiRecallRouter 集成 expansionFactor

**Files:**
- Modify: `src/main/java/me/maxt/rag/web/service/vector/recall/MultiRecallRouter.java`

**改动：** `recall()` 方法中 `perStrategyTopK` 的计算，乘以 `expansionFactor` 多拉候选，供后续精排使用。

- [ ] **Step 1: 修改 recall 方法中的 perStrategyTopK**

将第34行：
```java
int perStrategyTopK = config.getRecallTopK() * 2;
```

改为使用 `RerankConfig.getRerankExpansionFactor()`：
```java
int expansionFactor = 3;
if (config instanceof me.maxt.rag.web.config.RerankConfig rc) {
    expansionFactor = rc.getRerankExpansionFactor();
}
int perStrategyTopK = config.getRecallTopK() * expansionFactor;
```

- [ ] **Step 2: 编译验证**

Run: `mvn compile -q`
Expected: 编译成功

- [ ] **Step 3: 运行已有 MultiRecallRouterTest 确认不破坏现有行为**

Run: `mvn test -Dtest=MultiRecallRouterTest -DfailIfNoTests=false`
Expected: 4 tests PASS

- [ ] **Step 4: Commit**

```bash
git add src/main/java/me/maxt/rag/web/service/vector/recall/MultiRecallRouter.java
git commit -m "feat: MultiRecallRouter 召回数量 × expansionFactor 为精排预留候选"
```

---

### Task 9: RAGService 集成 Reranker

**Files:**
- Modify: `src/main/java/me/maxt/rag/web/service/RAGService.java`

**改动：**
1. 添加 `Reranker` 字段和构造函数参数
2. 在多路召回路径（第145行后）和查询增强路径（第177行后）插入 `reranker.rerank()` 调用
3. 重排序后过滤分数为 0 的结果（精排返回 0 说明完全不相关）

- [ ] **Step 1: 添加 import**

在第18行 import 区域添加：
```java
import me.maxt.rag.web.service.vector.rerank.Reranker;
```

- [ ] **Step 2: 添加 Reranker 字段（第49行后）**

```java
    private final Reranker reranker;
```

- [ ] **Step 3: 添加全参数构造函数**

新建一个最全参数的构造函数，在现有最后一个构造函数之后（第95行后）添加：

```java
    public RAGService(RetrievalConfig config, EmbeddingStoreManager storeManager,
                      EmbeddingModel embeddingModel, ChatModel chatModel,
                      QueryEnhancementRouter enhancementRouter,
                      QueryEnhancementConfig enhancementConfig,
                      MultiRecallRouter multiRecallRouter, RecallConfig recallConfig,
                      Reranker reranker) {
        this.config = config;
        this.storeManager = storeManager;
        this.embeddingModel = embeddingModel;
        this.chatModel = chatModel;
        this.enhancementRouter = enhancementRouter;
        this.enhancementConfig = enhancementConfig;
        this.multiRecallRouter = multiRecallRouter;
        this.recallConfig = recallConfig;
        this.reranker = reranker;

        this.contentRetriever = storeManager.createContentRetriever(
                embeddingModel, config.getMaxResults(), config.getMinScore());

        this.assistant = AiServices.builder(Assistant.class)
                .chatModel(chatModel)
                .contentRetriever(contentRetriever)
                .chatMemory(MessageWindowChatMemory.withMaxMessages(config.getMemorySize()))
                .build();
    }
```

注意：需要将 body 中第65-68行和第73-95行的构造函数改为委托 chain 到新构造函数，避免重复代码。将第60-62行的3参构造函数委托到4参；将第65-71行的6参构造函数改为委托到9参；将第73-95行的8参构造函数改为委托到9参：

```java
    public RAGService(RetrievalConfig config, EmbeddingStoreManager storeManager,
                      EmbeddingModel embeddingModel, ChatModel chatModel) {
        this(config, storeManager, embeddingModel, chatModel, null, null);
    }

    public RAGService(RetrievalConfig config, EmbeddingStoreManager storeManager,
                      EmbeddingModel embeddingModel, ChatModel chatModel,
                      QueryEnhancementRouter enhancementRouter,
                      QueryEnhancementConfig enhancementConfig) {
        this(config, storeManager, embeddingModel, chatModel,
                enhancementRouter, enhancementConfig, null, null);
    }

    public RAGService(RetrievalConfig config, EmbeddingStoreManager storeManager,
                      EmbeddingModel embeddingModel, ChatModel chatModel,
                      QueryEnhancementRouter enhancementRouter,
                      QueryEnhancementConfig enhancementConfig,
                      MultiRecallRouter multiRecallRouter, RecallConfig recallConfig) {
        this(config, storeManager, embeddingModel, chatModel,
                enhancementRouter, enhancementConfig,
                multiRecallRouter, recallConfig, null);
    }
```

- [ ] **Step 4: 在多路召回路径中插入重排序**

在 `answerWithSources` 方法中，第146行 `sources = matches.stream()` 之前插入：

```java
            if (reranker != null && reranker.isAvailable()) {
                int rerankTopK = (config instanceof me.maxt.rag.web.config.RerankConfig rc)
                        ? rc.getRerankTopK() : recallConfig.getRecallTopK();
                matches = reranker.rerank(query, matches, rerankTopK);
            }
```

- [ ] **Step 5: 在查询增强 RRF 融合后插入重排序**

在第177行 `sources = fused.stream()` 之前插入同样的代码：

```java
            if (reranker != null && reranker.isAvailable()) {
                int rerankTopK = (config instanceof me.maxt.rag.web.config.RerankConfig rc)
                        ? rc.getRerankTopK() : config.getMaxResults();
                fused = reranker.rerank(query, fused, rerankTopK);
            }
```

- [ ] **Step 6: 编译验证**

Run: `mvn compile -q`
Expected: 编译成功

- [ ] **Step 7: Commit**

```bash
git add src/main/java/me/maxt/rag/web/service/RAGService.java
git commit -m "feat: RAGService 集成 Reranker，召回后精排筛选"
```

---

### Task 10: RAGServiceTest 新增重排序集成测试

**Files:**
- Modify: `src/test/java/me/maxt/rag/web/service/RAGServiceTest.java`

- [ ] **Step 1: 先确认现有测试结构**

Read the RAGServiceTest file.

- [ ] **Step 2: 添加重排序相关测试用例**

```java
    @Test
    @SuppressWarnings("unchecked")
    void shouldCallRerankerWhenAvailable() {
        // Given: reranker 可用
        Reranker reranker = mock(Reranker.class);
        when(reranker.isAvailable()).thenReturn(true);
        when(reranker.name()).thenReturn("test-reranker");

        TextSegment seg = TextSegment.from("reranked result");
        EmbeddingMatch<TextSegment> rerankedMatch = mock(EmbeddingMatch.class);
        when(rerankedMatch.embedded()).thenReturn(seg);
        when(rerankedMatch.score()).thenReturn(0.95);
        when(reranker.rerank(anyString(), anyList(), anyInt()))
                .thenReturn(List.of(rerankedMatch));

        // 注意：此处依赖实际 EmbeddingModel 和 ChatModel，
        // 需要按现有 RAGServiceTest 的 setup 模式构造 service
        // （具体实现需读取 RAGServiceTest 现有代码后适配）
    }

    @Test
    void shouldSkipRerankWhenNotAvailable() {
        Reranker reranker = mock(Reranker.class);
        when(reranker.isAvailable()).thenReturn(false);

        // reranker 不可用时不应调用 rerank()
        // verifyNoInteractions 或确认 rerank 从未被调用
    }
```

注意：`RAGServiceTest` 现有测试构造 `RAGService` 时可能使用旧构造函数，需要在 Task 实施时读取具体代码，确保新增的 9 参构造函数能正确适配测试中的 mock 组装。

- [ ] **Step 3: 运行测试**

Run: `mvn test -Dtest=RAGServiceTest -DfailIfNoTests=false`
Expected: 所有测试 PASS

- [ ] **Step 4: Commit**

```bash
git add src/test/java/me/maxt/rag/web/service/RAGServiceTest.java
git commit -m "test: RAGService 重排序集成测试"
```

---

### Task 11: WebApplication 组装 Reranker

**Files:**
- Modify: `src/main/java/me/maxt/rag/web/WebApplication.java`

**改动：** 在构造函数中创建 `CrossEncoderReranker`，注入 `RAGService` 的新 9 参构造函数。

- [ ] **Step 1: 添加 import**

```java
import me.maxt.rag.web.service.vector.rerank.CrossEncoderReranker;
import me.maxt.rag.web.service.vector.rerank.Reranker;
```

- [ ] **Step 2: 添加 Reranker 字段（第62行后）**

```java
    private final Reranker reranker;
```

- [ ] **Step 3: 在构造函数中创建 CrossEncoderReranker**

在 `this.multiRecallRouter = multiRecallRouter;` 行（第135行）之后添加：

```java
        this.reranker = new CrossEncoderReranker(config);
```

- [ ] **Step 4: 修改 RAGService 构造函数调用**

将第139-140行：
```java
        this.ragService = new RAGService(config, storeManager, embeddingModel, chatModel,
                enhancementRouter, config, multiRecallRouter, config);
```
改为：
```java
        this.ragService = new RAGService(config, storeManager, embeddingModel, chatModel,
                enhancementRouter, config, multiRecallRouter, config, reranker);
```

- [ ] **Step 5: 添加 getter（第215行后）**

```java
    public Reranker getReranker() { return reranker; }
```

- [ ] **Step 6: 编译验证**

Run: `mvn compile -q`
Expected: 编译成功

- [ ] **Step 7: Commit**

```bash
git add src/main/java/me/maxt/rag/web/WebApplication.java
git commit -m "feat: WebApplication 组装 CrossEncoderReranker 并注入 RAGService"
```

---

### Task 12: 端到端验证

**验证内容：**
1. `mvn test` 全部通过
2. `mvn test jacoco:report` 覆盖率不低于之前（排除新增的 ONNX 推理代码）
3. 编译打包 `mvn clean package` 成功

- [ ] **Step 1: 运行全部单元测试**

Run: `mvn test`
Expected: 100 个测试全部 PASS（或更多，含新增测试）

- [ ] **Step 2: 覆盖率检查**

Run: `mvn test jacoco:report`
Check: `target/site/jacoco/index.html` — 覆盖率与之前持平或提升

- [ ] **Step 3: 打包验证**

Run: `mvn clean package`
Expected: BUILD SUCCESS

- [ ] **Step 4: Commit（如有遗漏文件）**

```bash
git status
# 确认没有遗漏的未提交文件
```

---

## 实施顺序

```
Task 1 (RerankConfig 接口)
  → Task 2 (AppConfig 实现 RerankConfig)
    → Task 3 (AppConfigTest)
Task 4 (Reranker 接口)
Task 5 (依赖添加)
  → Task 6 (CrossEncoderReranker 实现)
    → Task 7 (CrossEncoderRerankerTest)
Task 8 (MultiRecallRouter expansionFactor)
  → Task 9 (RAGService 集成)
    → Task 10 (RAGServiceTest)
Task 11 (WebApplication 组装)
  → Task 12 (端到端验证)
```

Task 1-4 可部分并行（纯接口，无实现依赖）。Task 5 与 Task 1-4 无依赖可并行。Task 6 依赖 Task 4+5。Task 9 依赖 Task 6+8。
