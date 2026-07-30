# 向量化增强 — 实现计划

> **For agentic workers:** 使用 superpowers:subagent-driven-development 逐任务执行。

**Goal:** 替换为中文嵌入模型 + 附加上下文增强（写入侧）+ 查询增强（读取侧）

**Architecture:** 在现有 DocumentService/RAGService 管线上叠加四个组件：模型替换（pom + import）、ContextualEnricher（写入侧，chunk 文本前缀）、QueryEnhancementRouter（读取侧，LLM 分类 → Rewriter/HyDE/RRF）。改动集中在 service 层和 config 层，不重构核心管线。

**Tech Stack:** JDK 17, LangChain4j, BgeSmallZhV15QuantizedEmbeddingModel, JUnit 5 + Mockito + AssertJ

## Global Constraints

- 测试只 mock 系统边界（EmbeddingModel、ChatModel），不 mock 自己的模块
- Service 层覆盖率 >88%
- 用中文回复、commit message 写中文
- 遵循现有包结构：config 接口放在 `config/`，service 实现放在 `service/`，新增 `service/vector/` 子包
- 向量维度从 384 → 512，旧 store 数据不兼容，启动时检测并自动清空

---

### Task 1: 依赖替换 + 模型切换

**Files:**
- Modify: `pom.xml:74-77`
- Modify: `src/main/java/me/maxt/rag/web/WebApplication.java:5,47`

**Interfaces:**
- Produces: `EmbeddingModel` 实例现在是 `BgeSmallZhV15QuantizedEmbeddingModel`（512 维），所有依赖方（DocumentService、RAGService、SemanticSplitter）通过接口注入无需改动

- [ ] **Step 1: 替换 pom.xml 中 embedding 依赖**

```xml
<!-- 删除 -->
<dependency>
  <groupId>dev.langchain4j</groupId>
  <artifactId>langchain4j-embeddings-bge-small-en-v15-q</artifactId>
  <version>1.12.1-beta21</version>
</dependency>

<!-- 替换为 -->
<dependency>
  <groupId>dev.langchain4j</groupId>
  <artifactId>langchain4j-embeddings-bge-small-zh-v15-q</artifactId>
  <version>1.12.1-beta21</version>
</dependency>
```

如果 `1.12.1-beta21` 版本不存在该 artifact，则尝试 `1.18.0`。若与核心 `langchain4j:1.12.1` 不兼容（编译错误），升级所有 langchain4j 依赖到同一版本。

- [ ] **Step 2: 编译验证依赖解析**

```bash
mvn compile
```
Expected: BUILD SUCCESS，依赖下载成功。

- [ ] **Step 3: 修改 WebApplication.java import 和实例化**

```java
// 旧
import dev.langchain4j.model.embedding.onnx.bgesmallenv15q.BgeSmallEnV15QuantizedEmbeddingModel;
this.embeddingModel = new BgeSmallEnV15QuantizedEmbeddingModel();

// 新
import dev.langchain4j.model.embedding.onnx.bgesmallzhv15q.BgeSmallZhV15QuantizedEmbeddingModel;
this.embeddingModel = new BgeSmallZhV15QuantizedEmbeddingModel();
```

- [ ] **Step 4: 编译验证**

```bash
mvn compile
```
Expected: BUILD SUCCESS。

- [ ] **Step 5: 运行现有测试确认破坏范围**

```bash
mvn test
```
预期部分测试失败（因为 store 数据维度不匹配的测试；语义分块相关测试因模型输出维度变化）。

- [ ] **Step 6: Commit**

```bash
git add pom.xml src/main/java/me/maxt/rag/web/WebApplication.java
git commit -m "feat: 替换为 BgeSmallZhV15QuantizedEmbeddingModel（中文嵌入模型）"
```

---

### Task 2: QueryEnhancementConfig 接口 + AppConfig 实现

**Files:**
- Create: `src/main/java/me/maxt/rag/web/config/QueryEnhancementConfig.java`
- Modify: `src/main/java/me/maxt/rag/web/config/AppConfig.java` — 实现新接口 + 新增字段和 getter
- Modify: `src/test/java/me/maxt/rag/web/config/AppConfigTest.java` — 新增配置项测试

**Interfaces:**
- Produces: `QueryEnhancementConfig` 接口供 `QueryEnhancementRouter` 构造函数使用

- [ ] **Step 1: 创建 QueryEnhancementConfig 接口**

```java
package me.maxt.rag.web.config;

/**
 * 查询增强相关配置接口。
 */
public interface QueryEnhancementConfig {
    /** 是否启用查询增强 */
    boolean isQueryEnhancementEnabled();
    /** 默认增强模式：auto | rewrite | hyde | both | none */
    String getDefaultEnhancementMode();
    /** RRF 融合参数 k */
    int getRrfK();
    /** HyDE 生成文本最大 token 数 */
    int getHydeMaxTokens();
}
```

文件路径: `src/main/java/me/maxt/rag/web/config/QueryEnhancementConfig.java`

- [ ] **Step 2: 修改 AppConfig 实现新接口**

`AppConfig` 类声明修改：`public class AppConfig implements LlmConfig, RetrievalConfig, DocumentConfig, ServerConfig, QueryEnhancementConfig`

新增字段（加在 Chunking 参数区之后）：

```java
// ========== 查询增强参数 ==========

/** 是否启用查询增强，可通过环境变量 RAG_QUERY_ENHANCEMENT_ENABLED 覆盖 */
private boolean queryEnhancementEnabled;

/** 默认增强模式，可通过环境变量 RAG_QUERY_ENHANCEMENT_MODE 覆盖 */
private String defaultEnhancementMode;

/** RRF 融合参数，可通过环境变量 RAG_QUERY_ENHANCEMENT_RRF_K 覆盖 */
private int rrfK;

/** HyDE 生成最大 Token 数，可通过环境变量 RAG_QUERY_ENHANCEMENT_HYDE_MAX_TOKENS 覆盖 */
private int hydeMaxTokens;
```

默认值（构造函数中新增）：
```java
this.queryEnhancementEnabled = true;
this.defaultEnhancementMode = "auto";
this.rrfK = 60;
this.hydeMaxTokens = 200;
```

`applyFileConfig` 中新增解析：
```java
Map<String, Object> queryEnhancement = (Map<String, Object>) fileConfig.get("queryEnhancement");
if (queryEnhancement != null) {
    config.queryEnhancementEnabled = getBoolean(queryEnhancement, "enabled", config.queryEnhancementEnabled);
    config.defaultEnhancementMode = getString(queryEnhancement, "defaultMode", config.defaultEnhancementMode);
    config.rrfK = getInt(queryEnhancement, "rrfK", config.rrfK);
    config.hydeMaxTokens = getInt(queryEnhancement, "hydeMaxTokens", config.hydeMaxTokens);
}
```

`applyEnvOverrides` 中新增：
```java
config.queryEnhancementEnabled = envBool("RAG_QUERY_ENHANCEMENT_ENABLED", config.queryEnhancementEnabled);
config.defaultEnhancementMode = env("RAG_QUERY_ENHANCEMENT_MODE", config.defaultEnhancementMode);
config.rrfK = envInt("RAG_QUERY_ENHANCEMENT_RRF_K", config.rrfK);
config.hydeMaxTokens = envInt("RAG_QUERY_ENHANCEMENT_HYDE_MAX_TOKENS", config.hydeMaxTokens);
```

Getter 方法：
```java
public boolean isQueryEnhancementEnabled() { return queryEnhancementEnabled; }
public String getDefaultEnhancementMode() { return defaultEnhancementMode; }
public int getRrfK() { return rrfK; }
public int getHydeMaxTokens() { return hydeMaxTokens; }
```

- [ ] **Step 3: 编译验证**

```bash
mvn compile
```

- [ ] **Step 4: 更新 AppConfigTest.java**

AppConfig 测试位于 `src/test/java/me/maxt/rag/web/config/AppConfigTest.java`，检查是否存在该文件：
```bash
ls src/test/java/me/maxt/rag/web/config/AppConfigTest.java
```

若存在，新增测试：
```java
@Test
void shouldHaveQueryEnhancementDefaults() {
    AppConfig config = new AppConfig();
    assertThat(config.isQueryEnhancementEnabled()).isTrue();
    assertThat(config.getDefaultEnhancementMode()).isEqualTo("auto");
    assertThat(config.getRrfK()).isEqualTo(60);
    assertThat(config.getHydeMaxTokens()).isEqualTo(200);
}
```

若不存在该测试文件，创建并包含上述测试。

- [ ] **Step 5: 运行测试**

```bash
mvn test -Dtest=AppConfigTest
```
Expected: PASS

- [ ] **Step 6: Commit**

```bash
git add src/main/java/me/maxt/rag/web/config/QueryEnhancementConfig.java src/main/java/me/maxt/rag/web/config/AppConfig.java src/test/java/me/maxt/rag/web/config/AppConfigTest.java
git commit -m "feat: 新增 QueryEnhancementConfig 接口及 AppConfig 实现"
```

---

### Task 3: ContextualEnricher（TDD）

**Files:**
- Create: `src/main/java/me/maxt/rag/web/service/vector/ContextualEnricher.java`
- Create: `src/test/java/me/maxt/rag/web/service/vector/ContextualEnricherTest.java`

**Interfaces:**
- Produces: `ContextualEnricher.enrich(List<TextSegment>, String fileName) -> List<TextSegment>` — DocumentService 调用

- [ ] **Step 1: 编写失败的测试**

```java
package me.maxt.rag.web.service.vector;

import dev.langchain4j.data.segment.TextSegment;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ContextualEnricherTest {

    @Test
    void shouldPrependFileNamePrefix() {
        ContextualEnricher enricher = new ContextualEnricher();
        TextSegment seg = TextSegment.from("系统启动时需要加载配置文件");
        List<TextSegment> result = enricher.enrich(List.of(seg), "产品手册");

        assertThat(result).hasSize(1);
        assertThat(result.get(0).text())
                .startsWith("[产品手册]")
                .contains("系统启动时需要加载配置文件");
    }

    @Test
    void shouldPrependHeadingPathWhenAvailable() {
        ContextualEnricher enricher = new ContextualEnricher();
        TextSegment seg = TextSegment.from("配置项说明");
        seg.metadata().put("heading_path", "第三章 > 配置说明");

        List<TextSegment> result = enricher.enrich(List.of(seg), "产品手册");

        assertThat(result.get(0).text())
                .startsWith("[产品手册] 第三章 > 配置说明\n配置项说明");
    }

    @Test
    void shouldSkipHeadingPathWhenNotAvailable() {
        ContextualEnricher enricher = new ContextualEnricher();
        TextSegment seg = TextSegment.from("plain text");

        List<TextSegment> result = enricher.enrich(List.of(seg), "readme.md");

        assertThat(result.get(0).text())
                .isEqualTo("[readme.md]\nplain text");
    }

    @Test
    void shouldPreserveOriginalMetadata() {
        ContextualEnricher enricher = new ContextualEnricher();
        TextSegment seg = TextSegment.from("content");
        seg.metadata().put("file_name", "doc.txt");
        seg.metadata().put("file_type", "TXT");

        List<TextSegment> result = enricher.enrich(List.of(seg), "doc.txt");

        assertThat(result.get(0).metadata().getString("file_name")).isEqualTo("doc.txt");
        assertThat(result.get(0).metadata().getString("file_type")).isEqualTo("TXT");
    }

    @Test
    void shouldHandleEmptySegmentsList() {
        ContextualEnricher enricher = new ContextualEnricher();
        List<TextSegment> result = enricher.enrich(List.of(), "empty.md");
        assertThat(result).isEmpty();
    }
}
```

- [ ] **Step 2: 运行测试验证失败**

```bash
mvn test -Dtest=ContextualEnricherTest
```
Expected: FAIL (编译错误：ContextualEnricher 不存在)

- [ ] **Step 3: 实现 ContextualEnricher**

```java
package me.maxt.rag.web.service.vector;

import dev.langchain4j.data.segment.TextSegment;

import java.util.ArrayList;
import java.util.List;

/**
 * 上下文增强器，在嵌入前为每个 chunk 文本附加上下文前缀，
 * 让嵌入向量感知文档来源和章节位置。
 */
public class ContextualEnricher {

    public List<TextSegment> enrich(List<TextSegment> segments, String fileName) {
        List<TextSegment> enriched = new ArrayList<>();
        for (TextSegment segment : segments) {
            String headingPath = segment.metadata().getString("heading_path");

            StringBuilder prefix = new StringBuilder();
            prefix.append("[").append(fileName).append("]");
            if (headingPath != null && !headingPath.isEmpty()) {
                prefix.append(" ").append(headingPath);
            }
            String enrichedText = prefix.append("\n").append(segment.text()).toString();

            enriched.add(TextSegment.from(enrichedText, segment.metadata()));
        }
        return enriched;
    }
}
```

- [ ] **Step 4: 运行测试验证通过**

```bash
mvn test -Dtest=ContextualEnricherTest
```
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add src/main/java/me/maxt/rag/web/service/vector/ContextualEnricher.java src/test/java/me/maxt/rag/web/service/vector/ContextualEnricherTest.java
git commit -m "feat: 实现 ContextualEnricher 上下文增强器"
```

---

### Task 4: 集成 ContextualEnricher 到 DocumentService

**Files:**
- Modify: `src/main/java/me/maxt/rag/web/WebApplication.java` — 创建 ContextualEnricher 并注入
- Modify: `src/main/java/me/maxt/rag/web/service/DocumentService.java` — 构造函数接收 ContextualEnricher，在 ingestDirectory 中调用
- Modify: `src/test/java/me/maxt/rag/web/service/DocumentServiceTest.java` — 适配新构造函数

**Interfaces:**
- Consumes: `ContextualEnricher.enrich(segments, fileName) -> List<TextSegment>`
- Produces: 修改后的 DocumentService 构造函数签名（兼容旧版，ContextualEnricher 可为 null）

- [ ] **Step 1: 修改 DocumentService 构造函数**

新增字段：
```java
private final ContextualEnricher contextualEnricher;
```

新增构造函数（保持旧版兼容）：
```java
public DocumentService(EmbeddingStoreManager storeManager, EmbeddingModel embeddingModel,
                       int chunkSize, int chunkOverlap, List<String> supportedExtensions,
                       ChunkingPipeline chunkingPipeline) {
    this(storeManager, embeddingModel, chunkSize, chunkOverlap, supportedExtensions, chunkingPipeline, null);
}

public DocumentService(EmbeddingStoreManager storeManager, EmbeddingModel embeddingModel,
                       int chunkSize, int chunkOverlap, List<String> supportedExtensions,
                       ChunkingPipeline chunkingPipeline, ContextualEnricher contextualEnricher) {
    this.storeManager = storeManager;
    this.embeddingModel = embeddingModel;
    this.chunkSize = chunkSize;
    this.chunkOverlap = chunkOverlap;
    this.supportedExtensions = supportedExtensions;
    this.chunkingPipeline = chunkingPipeline;
    this.contextualEnricher = contextualEnricher;
}
```

- [ ] **Step 2: 在 ingestDirectory 中调用 enrich**

在 `ingestDirectory()` 的 segments 循环中，附件 metadata 之后、`embedAll` 之前插入：

```java
// 现有代码：附加 file_name / file_type metadata
for (TextSegment segment : segments) {
    segment.metadata().put("file_name", fileName);
    segment.metadata().put("file_type", fileType);
}

// 新增：上下文增强
if (contextualEnricher != null) {
    segments = contextualEnricher.enrich(segments, fileName);
}

// 现有代码：向量化 + 存储
List<Embedding> embeddings = embeddingModel.embedAll(segments).content();
storeManager.addAll(embeddings, segments);
```

添加 import：
```java
import me.maxt.rag.web.service.vector.ContextualEnricher;
```

- [ ] **Step 3: 修改 WebApplication 创建 ContextualEnricher 并注入**

```java
import me.maxt.rag.web.service.vector.ContextualEnricher;

// 在 DocumentService 创建之前：
ContextualEnricher contextualEnricher = new ContextualEnricher();

this.documentService = new DocumentService(
        storeManager, embeddingModel,
        config.getChunkSize(), config.getChunkOverlap(),
        config.getSupportedFileExtensions(),
        chunkingPipeline, contextualEnricher);
```

- [ ] **Step 4: 运行 DocumentService 测试**

```bash
mvn test -Dtest=DocumentServiceTest
```
测试可能失败——原因是旧测试通过 4 参数旧构造函数创建 service，不走 enrich 路径，应仍然通过。若调用的是含 chunkingPipeline 的 6 参数版，mock 的 embeddingModel 行为可能需更新。

- [ ] **Step 5: 修复失败的测试**

检查测试失败原因：
- 若 `shouldIngestMatchingFiles` 失败：确认 `embeddingModel.embedAll()` mock 的返回值维度是否正确（Enricher 修改的是文本，不影响维度，应不受影响）
- 若因 TextSegment 不可变性导致：检查 ContextualEnricher 创建的 TextSegment 是否正确保留 metadata

- [ ] **Step 6: Commit**

```bash
git add src/main/java/me/maxt/rag/web/service/DocumentService.java src/main/java/me/maxt/rag/web/WebApplication.java src/test/java/me/maxt/rag/web/service/DocumentServiceTest.java
git commit -m "feat: 集成 ContextualEnricher 到 DocumentService"
```

---

### Task 5: QueryEnhancer 接口 + QueryRewriter（TDD）

**Files:**
- Create: `src/main/java/me/maxt/rag/web/service/vector/QueryEnhancer.java`
- Create: `src/main/java/me/maxt/rag/web/service/vector/QueryRewriter.java`
- Create: `src/test/java/me/maxt/rag/web/service/vector/QueryRewriterTest.java`

**Interfaces:**
- Produces: `QueryEnhancer.enhance(String query) -> List<String>` 接口
- Produces: `QueryRewriter(ChatModel, int maxTokens)` 实现，调用 LLM 改写查询

- [ ] **Step 1: 创建 QueryEnhancer 接口**

```java
package me.maxt.rag.web.service.vector;

import java.util.List;

/** 查询增强器接口，返回增强后的查询变体列表 */
public interface QueryEnhancer {
    List<String> enhance(String query);
}
```

- [ ] **Step 2: 编写 QueryRewriter 测试**

```java
package me.maxt.rag.web.service.vector;

import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.chat.response.ChatResponseMetadata;
import dev.langchain4j.model.output.TokenUsage;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class QueryRewriterTest {

    @Test
    void shouldRewriteQuery() {
        ChatModel chatModel = mock(ChatModel.class);
        ChatResponse response = ChatResponse.builder()
                .aiMessage(AiMessage.from("向量数据库 传统数据库 区别 对比"))
                .metadata(ChatResponseMetadata.builder()
                        .tokenUsage(new TokenUsage(10, 5))
                        .build())
                .build();
        when(chatModel.chat(any(ChatRequest.class))).thenReturn(response);

        QueryRewriter rewriter = new QueryRewriter(chatModel, 100);
        List<String> result = rewriter.enhance("向量数据库和传统数据库有什么区别？");

        assertThat(result).hasSize(1);
        assertThat(result.get(0)).isEqualTo("向量数据库 传统数据库 区别 对比");
    }

    @Test
    void shouldReturnOriginalQueryOnLLMFailure() {
        ChatModel chatModel = mock(ChatModel.class);
        when(chatModel.chat(any(ChatRequest.class))).thenThrow(new RuntimeException("LLM timeout"));

        QueryRewriter rewriter = new QueryRewriter(chatModel, 100);
        List<String> result = rewriter.enhance("怎么装这个软件？");

        assertThat(result).hasSize(1);
        assertThat(result.get(0)).isEqualTo("怎么装这个软件？");
    }
}
```

- [ ] **Step 3: 运行测试验证失败**

```bash
mvn test -Dtest=QueryRewriterTest
```
Expected: FAIL

- [ ] **Step 4: 实现 QueryRewriter**

```java
package me.maxt.rag.web.service.vector;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * 查询改写器，通过 LLM 将口语化查询改写为关键词丰富的检索查询。
 */
public class QueryRewriter implements QueryEnhancer {

    private static final Logger log = LoggerFactory.getLogger(QueryRewriter.class);
    private static final String SYSTEM_PROMPT =
            "将用户口语化问题改写为一个信息检索查询（keywords-rich, 简洁, 不用完整句子）。";

    private final ChatModel chatModel;
    private final int maxTokens;

    public QueryRewriter(ChatModel chatModel, int maxTokens) {
        this.chatModel = chatModel;
        this.maxTokens = maxTokens;
    }

    @Override
    public List<String> enhance(String query) {
        try {
            ChatRequest request = ChatRequest.builder()
                    .messages(List.of(
                            SystemMessage.from(SYSTEM_PROMPT),
                            UserMessage.from("用户问题：" + query + "\n检索查询：")
                    ))
                    .build();
            String rewritten = chatModel.chat(request).aiMessage().text().trim();
            if (rewritten.isEmpty()) return List.of(query);
            log.debug("Query rewritten: {} -> {}", query, rewritten);
            return List.of(rewritten);
        } catch (Exception e) {
            log.warn("Query rewriting failed, using original query", e);
            return List.of(query);
        }
    }
}
```

- [ ] **Step 5: 运行测试验证通过**

```bash
mvn test -Dtest=QueryRewriterTest
```
Expected: PASS

- [ ] **Step 6: Commit**

```bash
git add src/main/java/me/maxt/rag/web/service/vector/QueryEnhancer.java src/main/java/me/maxt/rag/web/service/vector/QueryRewriter.java src/test/java/me/maxt/rag/web/service/vector/QueryRewriterTest.java
git commit -m "feat: 实现 QueryEnhancer 接口和 QueryRewriter"
```

---

### Task 6: HyDEGenerator（TDD）

**Files:**
- Create: `src/main/java/me/maxt/rag/web/service/vector/HyDEGenerator.java`
- Create: `src/test/java/me/maxt/rag/web/service/vector/HyDEGeneratorTest.java`

**Interfaces:**
- Produces: `HyDEGenerator(ChatModel, int maxTokens)` 实现 `QueryEnhancer`，生成假设文档

- [ ] **Step 1: 编写 HyDEGenerator 测试**

```java
package me.maxt.rag.web.service.vector;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.ChatResponseMetadata;
import dev.langchain4j.model.output.TokenUsage;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class HyDEGeneratorTest {

    @Test
    void shouldGenerateHypotheticalDocument() {
        ChatModel chatModel = mock(ChatModel.class);
        ChatResponse response = ChatResponse.builder()
                .aiMessage(AiMessage.from("向量数据库通过将数据表示为高维向量进行相似度检索，而传统数据库使用精确匹配。"))
                .metadata(ChatResponseMetadata.builder()
                        .tokenUsage(new TokenUsage(30, 20))
                        .build())
                .build();
        when(chatModel.chat(any(ChatRequest.class))).thenReturn(response);

        HyDEGenerator generator = new HyDEGenerator(chatModel, 200);
        List<String> result = generator.enhance("向量数据库和传统数据库有什么区别？");

        assertThat(result).hasSize(1);
        assertThat(result.get(0)).contains("向量数据库");
    }

    @Test
    void shouldReturnEmptyListOnLLMFailure() {
        ChatModel chatModel = mock(ChatModel.class);
        when(chatModel.chat(any(ChatRequest.class))).thenThrow(new RuntimeException("LLM error"));

        HyDEGenerator generator = new HyDEGenerator(chatModel, 200);
        List<String> result = generator.enhance("什么是向量检索？");

        assertThat(result).isEmpty();
    }
}
```

- [ ] **Step 2: 运行测试验证失败**

```bash
mvn test -Dtest=HyDEGeneratorTest
```

- [ ] **Step 3: 实现 HyDEGenerator**

```java
package me.maxt.rag.web.service.vector;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collections;
import java.util.List;

/**
 * 假设文档生成器，通过 LLM 生成一段假设的文档内容来回答用户问题，
 * 利用假设文档与真实文档的嵌入向量更接近的特性提升检索召回率。
 */
public class HyDEGenerator implements QueryEnhancer {

    private static final Logger log = LoggerFactory.getLogger(HyDEGenerator.class);
    private static final String SYSTEM_PROMPT =
            "根据用户问题，生成一段假设的文档内容来回答这个问题（不超过%d字）。";

    private final ChatModel chatModel;
    private final int maxTokens;

    public HyDEGenerator(ChatModel chatModel, int maxTokens) {
        this.chatModel = chatModel;
        this.maxTokens = maxTokens;
    }

    @Override
    public List<String> enhance(String query) {
        try {
            String prompt = String.format(SYSTEM_PROMPT, maxTokens);
            ChatRequest request = ChatRequest.builder()
                    .messages(List.of(
                            SystemMessage.from(prompt),
                            UserMessage.from("用户问题：" + query + "\n假设文档内容：")
                    ))
                    .build();
            String hypothetical = chatModel.chat(request).aiMessage().text().trim();
            if (hypothetical.isEmpty()) return Collections.emptyList();
            log.debug("HyDE generated: {}...", hypothetical.substring(0, Math.min(50, hypothetical.length())));
            return List.of(hypothetical);
        } catch (Exception e) {
            log.warn("HyDE generation failed", e);
            return Collections.emptyList();
        }
    }
}
```

- [ ] **Step 4: 运行测试验证通过**

```bash
mvn test -Dtest=HyDEGeneratorTest
```

- [ ] **Step 5: Commit**

```bash
git add src/main/java/me/maxt/rag/web/service/vector/HyDEGenerator.java src/test/java/me/maxt/rag/web/service/vector/HyDEGeneratorTest.java
git commit -m "feat: 实现 HyDEGenerator 假设文档生成器"
```

---

### Task 7: QueryEnhancementRouter（TDD）

**Files:**
- Create: `src/main/java/me/maxt/rag/web/service/vector/QueryEnhancementRouter.java`
- Create: `src/test/java/me/maxt/rag/web/service/vector/QueryEnhancementRouterTest.java`

**Interfaces:**
- Consumes: `QueryEnhancer` 接口，`QueryEnhancementConfig`，`ChatModel`（分类用）
- Produces: `QueryEnhancementRouter.route(String query, String mode) -> List<String>` 返回增强后的查询变体
- Produces: `QueryEnhancementRouter.fuse(List<EmbeddingMatch<TextSegment>>, List<EmbeddingMatch<TextSegment>>, int topK, int k) -> List<EmbeddingMatch<TextSegment>>` RRF 融合

- [ ] **Step 1: 编写 Router 测试**

```java
package me.maxt.rag.web.service.vector;

import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.ChatResponseMetadata;
import dev.langchain4j.model.output.TokenUsage;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import dev.langchain4j.store.embedding.EmbeddingSearchResult;
import me.maxt.rag.web.config.QueryEnhancementConfig;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class QueryEnhancementRouterTest {

    private final QueryEnhancer rewriter = mock(QueryRewriter.class);

    @Test
    void shouldRouteToRewriterWhenModeIsRewrite() {
        QueryEnhancementRouter router = new QueryEnhancementRouter(rewriter, null, null, null);
        when(rewriter.enhance("query")).thenReturn(List.of("rewritten query"));

        List<String> result = router.route("query", "rewrite");

        assertThat(result).containsExactly("rewritten query");
    }

    @Test
    void shouldRouteToHydeWhenModeIsHyde() {
        QueryEnhancer hyde = mock(HyDEGenerator.class);
        when(hyde.enhance("query")).thenReturn(List.of("hypothetical doc"));

        QueryEnhancementRouter router = new QueryEnhancementRouter(null, hyde, null, null);
        List<String> result = router.route("query", "hyde");

        assertThat(result).containsExactly("hypothetical doc");
    }

    @Test
    void shouldRouteToBothWhenModeIsBoth() {
        when(rewriter.enhance("query")).thenReturn(List.of("rewritten"));
        QueryEnhancer hyde = mock(HyDEGenerator.class);
        when(hyde.enhance("query")).thenReturn(List.of("hyde"));

        QueryEnhancementRouter router = new QueryEnhancementRouter(rewriter, hyde, null, null);
        List<String> result = router.route("query", "both");

        assertThat(result).containsExactly("rewritten", "hyde");
    }

    @Test
    void shouldSkipEnhancementWhenModeIsNone() {
        QueryEnhancementRouter router = new QueryEnhancementRouter(null, null, null, null);
        List<String> result = router.route("direct query", "none");

        assertThat(result).containsExactly("direct query");
    }

    @Test
    void shouldAutoClassifyViaLLM() {
        ChatModel chatModel = mock(ChatModel.class);
        ChatResponse response = ChatResponse.builder()
                .aiMessage(AiMessage.from("REWRITE"))
                .metadata(ChatResponseMetadata.builder()
                        .tokenUsage(new TokenUsage(5, 1))
                        .build())
                .build();
        when(chatModel.chat(any(ChatRequest.class))).thenReturn(response);
        when(rewriter.enhance("怎么装")).thenReturn(List.of("安装教程"));

        QueryEnhancementConfig config = mock(QueryEnhancementConfig.class);
        QueryEnhancementRouter router = new QueryEnhancementRouter(rewriter, null, chatModel, config);
        List<String> result = router.route("怎么装", "auto");

        assertThat(result).containsExactly("安装教程");
    }

    @Test
    void shouldFallbackToRewriteOnClassificationFailure() {
        ChatModel chatModel = mock(ChatModel.class);
        when(chatModel.chat(any(ChatRequest.class))).thenThrow(new RuntimeException("timeout"));
        when(rewriter.enhance("test")).thenReturn(List.of("rewritten"));

        QueryEnhancementConfig config = mock(QueryEnhancementConfig.class);
        QueryEnhancementRouter router = new QueryEnhancementRouter(rewriter, null, chatModel, config);
        List<String> result = router.route("test", "auto");

        assertThat(result).containsExactly("rewritten");
    }

    @Test
    void shouldFuseTwoResultSetsWithRRF() {
        QueryEnhancementRouter router = new QueryEnhancementRouter(null, null, null, null);

        TextSegment sA = TextSegment.from("doc A");
        TextSegment sB = TextSegment.from("doc B");
        TextSegment sC = TextSegment.from("doc C");

        EmbeddingMatch<TextSegment> matchA1 = new EmbeddingMatch<>(0.9, "a1", Embedding.from(new float[]{0.1f}), sA);
        EmbeddingMatch<TextSegment> matchB1 = new EmbeddingMatch<>(0.8, "b1", Embedding.from(new float[]{0.2f}), sB);

        EmbeddingMatch<TextSegment> matchA2 = new EmbeddingMatch<>(0.7, "a2", Embedding.from(new float[]{0.1f}), sA);
        EmbeddingMatch<TextSegment> matchC1 = new EmbeddingMatch<>(0.6, "c1", Embedding.from(new float[]{0.3f}), sC);

        List<EmbeddingMatch<TextSegment>> result = router.fuse(
                List.of(matchA1, matchB1),
                List.of(matchA2, matchC1),
                3, 60);

        assertThat(result).hasSize(3);
        // doc A appears in both lists — RRF should rank it higher
        assertThat(result.get(0).embedded().text()).isEqualTo("doc A");
    }
}
```

- [ ] **Step 2: 运行测试验证失败**

```bash
mvn test -Dtest=QueryEnhancementRouterTest
```

- [ ] **Step 3: 实现 QueryEnhancementRouter**

```java
package me.maxt.rag.web.service.vector;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import me.maxt.rag.web.config.QueryEnhancementConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

/**
 * 查询增强路由器，根据模式（auto/rewrite/hyde/both/none）分发到对应的增强策略。
 * auto 模式通过 LLM 分类选择策略。
 */
public class QueryEnhancementRouter {

    private static final Logger log = LoggerFactory.getLogger(QueryEnhancementRouter.class);
    private static final String CLASSIFY_PROMPT =
            "分析用户问题，只返回以下一个标签（不要解释）：\n"
                    + "- REWRITE: 问题简短口语化、模糊不清\n"
                    + "- HYDE: 事实性、定义性问题\n"
                    + "- BOTH: 复杂、开放性问题\n"
                    + "- NONE: 问题已经足够具体清晰\n"
                    + "\n问题：";

    private final QueryRewriter rewriter;
    private final HyDEGenerator hydeGenerator;
    private final ChatModel chatModel;
    private final QueryEnhancementConfig config;

    public QueryEnhancementRouter(QueryRewriter rewriter, HyDEGenerator hydeGenerator,
                                   ChatModel chatModel, QueryEnhancementConfig config) {
        this.rewriter = rewriter;
        this.hydeGenerator = hydeGenerator;
        this.chatModel = chatModel;
        this.config = config;
    }

    /**
     * 根据模式和查询内容路由到对应增强策略。
     *
     * @param query 用户原始问题
     * @param mode  auto | rewrite | hyde | both | none
     * @return 增强后的查询变体列表（可能包含 1-N 个）
     */
    public List<String> route(String query, String mode) {
        String effectiveMode = resolveMode(query, mode);
        log.debug("Query enhancement mode: {} (requested: {})", effectiveMode, mode);

        return switch (effectiveMode) {
            case "rewrite" -> safeEnhance(rewriter, query);
            case "hyde" -> safeEnhance(hydeGenerator, query);
            case "both" -> both(query);
            default -> List.of(query);
        };
    }

    private String resolveMode(String query, String mode) {
        if (!"auto".equals(mode)) return mode;

        try {
            ChatRequest request = ChatRequest.builder()
                    .messages(List.of(
                            SystemMessage.from(CLASSIFY_PROMPT),
                            UserMessage.from(query + "\n标签：")
                    ))
                    .build();
            String label = chatModel.chat(request).aiMessage().text().trim().toUpperCase();
            if (label.contains("REWRITE")) return "rewrite";
            if (label.contains("HYDE")) return "hyde";
            if (label.contains("BOTH")) return "both";
            if (label.contains("NONE")) return "none";
            return "rewrite";
        } catch (Exception e) {
            log.warn("LLM classification failed, falling back to rewrite", e);
            return "rewrite";
        }
    }

    private List<String> both(String query) {
        List<String> results = new ArrayList<>();
        results.addAll(safeEnhance(rewriter, query));
        results.addAll(safeEnhance(hydeGenerator, query));
        return results;
    }

    private List<String> safeEnhance(QueryEnhancer enhancer, String query) {
        try {
            if (enhancer == null) return List.of(query);
            List<String> result = enhancer.enhance(query);
            return result.isEmpty() ? List.of(query) : result;
        } catch (Exception e) {
            log.warn("Enhancer failed for query", e);
            return List.of(query);
        }
    }

    /**
     * RRF (Reciprocal Rank Fusion) 融合两组检索结果。
     */
    public List<EmbeddingMatch<TextSegment>> fuse(
            List<EmbeddingMatch<TextSegment>> resultA,
            List<EmbeddingMatch<TextSegment>> resultB,
            int topK, int k) {

        Map<String, Double> rrfScores = new LinkedHashMap<>();
        Map<String, EmbeddingMatch<TextSegment>> matchMap = new HashMap<>();

        accumulateRrf(resultA, rrfScores, matchMap, k);
        accumulateRrf(resultB, rrfScores, matchMap, k);

        return rrfScores.entrySet().stream()
                .sorted(Map.Entry.<String, Double>comparingByValue().reversed())
                .limit(topK)
                .map(e -> matchMap.get(e.getKey()))
                .toList();
    }

    private void accumulateRrf(List<EmbeddingMatch<TextSegment>> results,
                                Map<String, Double> scores,
                                Map<String, EmbeddingMatch<TextSegment>> matchMap,
                                int k) {
        for (int i = 0; i < results.size(); i++) {
            EmbeddingMatch<TextSegment> match = results.get(i);
            String key = match.embedded().text();
            scores.merge(key, 1.0 / (k + i + 1), Double::sum);
            matchMap.putIfAbsent(key, match);
        }
    }
}
```

- [ ] **Step 4: 运行测试验证通过**

```bash
mvn test -Dtest=QueryEnhancementRouterTest
```

- [ ] **Step 5: Commit**

```bash
git add src/main/java/me/maxt/rag/web/service/vector/QueryEnhancementRouter.java src/test/java/me/maxt/rag/web/service/vector/QueryEnhancementRouterTest.java
git commit -m "feat: 实现 QueryEnhancementRouter 路由器和 RRF 融合"
```

---

### Task 8: 集成 QueryEnhancementRouter 到 RAGService

**Files:**
- Modify: `src/main/java/me/maxt/rag/web/WebApplication.java` — 创建 Router 并注入 RAGService
- Modify: `src/main/java/me/maxt/rag/web/service/RAGService.java` — 构造函数接收 Router，answerWithSources 支持 enhancementMode
- Modify: `src/test/java/me/maxt/rag/web/service/RAGServiceTest.java` — 适配新签名

**Interfaces:**
- Consumes: `QueryEnhancementRouter.route(String query, String mode) -> List<String>`
- Consumes: `QueryEnhancementRouter.fuse(...)` for "both" mode
- Produces: `RAGService.answerWithSources(String query, String enhancementMode)` 新方法签名

- [ ] **Step 1: 修改 RAGService 构造函数和 answerWithSources**

添加字段和 import：
```java
import me.maxt.rag.web.config.QueryEnhancementConfig;
import me.maxt.rag.web.service.vector.QueryEnhancementRouter;
import dev.langchain4j.store.embedding.EmbeddingMatch;

// 新增字段
private final QueryEnhancementRouter enhancementRouter;
private final QueryEnhancementConfig enhancementConfig;
```

修改构造函数（保持旧版兼容）：
```java
public RAGService(RetrievalConfig config, EmbeddingStoreManager storeManager,
                  EmbeddingModel embeddingModel, ChatModel chatModel) {
    this(config, storeManager, embeddingModel, chatModel, null, null);
}

public RAGService(RetrievalConfig config, EmbeddingStoreManager storeManager,
                  EmbeddingModel embeddingModel, ChatModel chatModel,
                  QueryEnhancementRouter enhancementRouter,
                  QueryEnhancementConfig enhancementConfig) {
    // ... 现有初始化代码 ...
    this.enhancementRouter = enhancementRouter;
    this.enhancementConfig = enhancementConfig;
}
```

修改 `answerWithSources` 已有方法为无参调用：
```java
public AnswerWithSources answerWithSources(String query) {
    return answerWithSources(query, null);
}
```

新增 `answerWithSources(String query, String enhancementMode)`:
```java
public AnswerWithSources answerWithSources(String query, String enhancementMode) {
    // 解析 enhancement mode
    String mode = enhancementMode;
    if (mode == null && enhancementConfig != null) {
        mode = enhancementConfig.getDefaultEnhancementMode();
    }
    if (mode == null) mode = "none";

    List<Source> sources = new ArrayList<>();

    if (enhancementRouter != null && enhancementConfig != null && enhancementConfig.isQueryEnhancementEnabled()) {
        List<String> queryVariants = enhancementRouter.route(query, mode);

        if (queryVariants.size() == 1) {
            // 单查询变体：直接检索
            sources = searchAndCollect(queryVariants.get(0));
        } else {
            // 多查询变体：分别检索 + RRF 融合
            List<EmbeddingMatch<TextSegment>> allMatches = new ArrayList<>();
            for (int i = 0; i < queryVariants.size(); i++) {
                Embedding qEmbedding = embeddingModel.embed(queryVariants.get(i)).content();
                EmbeddingSearchResult<TextSegment> result = storeManager.search(
                        EmbeddingSearchRequest.builder()
                                .queryEmbedding(qEmbedding)
                                .maxResults(config.getMaxResults() * 2)
                                .minScore(config.getMinScore())
                                .build());
                allMatches.addAll(result.matches());
            }
            // Group matches by variant index for RRF-like fusion
            // For simplicity: deduplicate by text content, keep highest score
            Map<String, EmbeddingMatch<TextSegment>> dedup = new LinkedHashMap<>();
            for (EmbeddingMatch<TextSegment> m : allMatches) {
                String key = m.embedded().text();
                EmbeddingMatch<TextSegment> existing = dedup.get(key);
                if (existing == null || m.score() > existing.score()) {
                    dedup.put(key, m);
                }
            }
            sources = dedup.values().stream()
                    .sorted((a, b) -> Double.compare(b.score(), a.score()))
                    .limit(config.getMaxResults())
                    .map(this::toSource)
                    .toList();
        }
    } else {
        // 无增强：保持原有逻辑
        sources = searchAndCollect(query);
    }

    String answer = assistant.answer(query);
    return new AnswerWithSources(answer, sources);
}

private List<Source> searchAndCollect(String queryText) {
    List<Source> sources = new ArrayList<>();
    Embedding queryEmbedding = embeddingModel.embed(queryText).content();
    EmbeddingSearchResult<TextSegment> result = storeManager.search(
            EmbeddingSearchRequest.builder()
                    .queryEmbedding(queryEmbedding)
                    .maxResults(config.getMaxResults())
                    .minScore(config.getMinScore())
                    .build());
    for (EmbeddingMatch<TextSegment> match : result.matches()) {
        sources.add(toSource(match));
    }
    return sources;
}

private Source toSource(EmbeddingMatch<TextSegment> match) {
    String fileName = match.embedded().metadata().getString("absolute_directory_path");
    if (fileName == null) {
        fileName = match.embedded().metadata().getString("file_name");
    } else {
        fileName = fileName + "/" + match.embedded().metadata().getString("file_name");
    }
    if (fileName == null) fileName = "unknown";
    return new Source(fileName, match.embedded().text(), match.score());
}
```

- [ ] **Step 2: 修改 WebApplication 创建 Router 并注入**

```java
import me.maxt.rag.web.service.vector.QueryRewriter;
import me.maxt.rag.web.service.vector.HyDEGenerator;
import me.maxt.rag.web.service.vector.QueryEnhancementRouter;

// 在 RAGService 创建之前：
QueryRewriter queryRewriter = new QueryRewriter(chatModel, 100);
HyDEGenerator hydeGenerator = new HyDEGenerator(chatModel, config.getHydeMaxTokens());
QueryEnhancementRouter enhancementRouter = new QueryEnhancementRouter(
        queryRewriter, hydeGenerator, chatModel, config);

this.ragService = new RAGService(config, storeManager, embeddingModel, chatModel,
        enhancementRouter, config);
```

- [ ] **Step 3: 运行 RAGService 测试**

```bash
mvn test -Dtest=RAGServiceTest
```
旧测试应通过（走旧 4 参数构造函数，enhancementRouter 为 null，降级到原有逻辑）。

- [ ] **Step 4: 新增 Router 集成测试**

在 RAGServiceTest 中新增：
```java
@Test
@SuppressWarnings("unchecked")
void shouldUseQueryEnhancementWhenEnabled() {
    // Add document
    float[] v1 = {0.5f, 0.5f, 0.5f, 0.5f};
    TextSegment s1 = TextSegment.from("安装教程：下载后解压运行");
    s1.metadata().put("file_name", "guide.txt");
    storeManager.add(Embedding.from(v1), s1);

    // Mock embedding model
    EmbeddingModel embeddingModel = mock(EmbeddingModel.class);
    Response<Embedding> embedResp = mock(Response.class);
    when(embedResp.content()).thenReturn(Embedding.from(v1));
    when(embeddingModel.embed(anyString())).thenReturn(embedResp);

    // Mock Router — return a rewritten query
    QueryEnhancementRouter mockRouter = mock(QueryEnhancementRouter.class);
    when(mockRouter.route("怎么装", "rewrite")).thenReturn(List.of("安装教程"));

    QueryEnhancementConfig mockEnhConfig = mock(QueryEnhancementConfig.class);
    when(mockEnhConfig.isQueryEnhancementEnabled()).thenReturn(true);
    when(mockEnhConfig.getDefaultEnhancementMode()).thenReturn("rewrite");

    RAGService service = new RAGService(config, storeManager, embeddingModel, chatModel,
            mockRouter, mockEnhConfig);
    RAGService.AnswerWithSources result = service.answerWithSources("怎么装", "rewrite");

    assertThat(result.sources).hasSize(1);
    assertThat(result.sources.get(0).text).isEqualTo("安装教程：下载后解压运行");
}
```

- [ ] **Step 5: Commit**

```bash
git add src/main/java/me/maxt/rag/web/RAGService.java src/main/java/me/maxt/rag/web/WebApplication.java src/test/java/me/maxt/rag/web/service/RAGServiceTest.java
git commit -m "feat: 集成 QueryEnhancementRouter 到 RAGService"
```

---

### Task 9: ChatController + API enhancement 参数

**Files:**
- Modify: `src/main/java/me/maxt/rag/web/controller/ChatController.java`

**Interfaces:**
- Consumes: `RAGService.answerWithSources(String query, String enhancementMode)`

- [ ] **Step 1: 修改 ChatController.handleChat**

```java
public void handleChat(Context ctx) {
    try {
        @SuppressWarnings("unchecked")
        Map<String, Object> body = ctx.bodyAsClass(Map.class);
        String query = (String) body.get("query");

        if (query == null || query.trim().isEmpty()) {
            ctx.status(400).json(Map.of("error", "Query is required"));
            return;
        }

        // 解析 enhancement 参数（可选，默认 null → RAGService 内部用 config 默认值）
        String enhancement = (String) body.getOrDefault("enhancement", null);

        log.info("Chat query: {} (enhancement: {})", query, enhancement);
        RAGService.AnswerWithSources result = ragService.answerWithSources(query.trim(), enhancement);

        ctx.json(result);
    } catch (Exception e) {
        log.error("Chat error", e);
        ctx.status(500).json(Map.of("error", "Failed to process query: " + e.getMessage()));
    }
}
```

- [ ] **Step 2: 编译验证**

```bash
mvn compile
```

- [ ] **Step 3: Commit**

```bash
git add src/main/java/me/maxt/rag/web/controller/ChatController.java
git commit -m "feat: ChatController 支持 enhancement 参数"
```

---

### Task 10: 配置文件和降级逻辑

**Files:**
- Modify: `config.example.json` — 新增 queryEnhancement 配置段
- Modify: `src/main/java/me/maxt/rag/web/service/EmbeddingStoreManager.java` — 增加 `clear()` 方法
- Modify: `src/main/java/me/maxt/rag/web/WebApplication.java` — 启动时检测 vector 维度不兼容并自动清空

**Interfaces:**
- Consumes: `EmbeddingStoreManager.clear()` 新增方法

- [ ] **Step 1: 更新 config.example.json**

在 `store` 段之后增加：
```json
  "queryEnhancement": {
    "enabled": true,
    "defaultMode": "auto",
    "rrfK": 60,
    "hydeMaxTokens": 200
  }
```

- [ ] **Step 2: 为 EmbeddingStoreManager 增加 clear() 方法**

```java
/**
 * 清空向量存储（包括内存索引和持久化文件）。
 * 供维度不兼容或手动重置场景使用。
 */
public synchronized void clear() {
    entries.clear();
    // 重新创建 InMemoryEmbeddingStore（langchain4j 不支持直接清空）
    persist();
    log.info("Embedding store cleared.");
}
```

- [ ] **Step 3: 在 WebApplication 中增加维度不兼容检测**

在构造函数中，创建 embeddingModel 和 storeManager 之后：

```java
// 检测向量维度不兼容：旧 EN 模型 384 → 新 ZH 模型 512
// 如果 store 中有旧数据且维度不匹配，自动清空
if (storeManager.getEntryCount() > 0) {
    int newDim = embeddingModel.embed("test").content().dimension();
    EmbeddingStoreManager.StoredEntry firstEntry = storeManager.listDocuments().get(0);
    int oldDim = firstEntry.getEmbedding().length;
    if (oldDim != newDim) {
        log.warn("Vector dimension mismatch: old={}, new={}. Clearing store...", oldDim, newDim);
        storeManager.clear();
    }
}
```

- [ ] **Step 4: 编译 + 运行测试**

```bash
mvn compile
mvn test
```

- [ ] **Step 5: Commit**

```bash
git add config.example.json src/main/java/me/maxt/rag/web/service/EmbeddingStoreManager.java src/main/java/me/maxt/rag/web/WebApplication.java
git commit -m "feat: 增加 queryEnhancement 配置段和向量维度不兼容自动清空"
```

---

### Task 11: 全量测试 + 覆盖率验证

- [ ] **Step 1: 运行全量测试**

```bash
mvn test
```

- [ ] **Step 2: 修复所有失败测试**

逐一检查失败原因并修复。常见问题：
- Mock 的 EmbeddingModel 返回向量维度需要与 512 匹配
- ContextualEnricher 修改文本后 metadata 是否保留
- 新增 Test 中 Mock 配置是否正确

- [ ] **Step 3: 生成覆盖率报告**

```bash
mvn test jacoco:report
```

查看 `target/site/jacoco/index.html`，确认 Service 层覆盖率 >88%。

- [ ] **Step 4: 运行应用验证**

```bash
mvn clean package
java -jar target/MyAIDemo2-1.0-SNAPSHOT.jar
```

验证：
1. `curl http://localhost:8080/api/health` → `{"status":"ok"}`
2. 中文 embedding 模型加载成功（日志无报错）
3. `curl -X POST http://localhost:8080/api/chat -H "Content-Type: application/json" -d '{"query":"你好","enhancement":"none"}'` → 返回回答

- [ ] **Step 5: Commit 最终修复**

```bash
git add -A
git commit -m "test: 全量测试修复，覆盖率达成 >88%"
```

---

### 完成标准

- [ ] 60+ 单元测试全部通过
- [ ] Service 层覆盖率 >88%
- [ ] `/api/chat` 支持 `enhancement` 参数（5 个模式均可工作）
- [ ] 中文文档摄入后检索正常
- [ ] config.example.json 包含 queryEnhancement 配置
- [ ] 旧 EN 模型 store 数据维度不匹配时自动清空
- [ ] 运行时不报错，应用正常监听 8080
