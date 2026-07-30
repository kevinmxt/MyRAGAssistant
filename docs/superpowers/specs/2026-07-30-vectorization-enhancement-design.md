# 向量化增强 — 设计方案

2026-07-30

## 背景

当前向量化链路存在四个短板：

1. **模型语言不匹配**：BGE Small EN v1.5（英文优化，384 维）嵌入中文文档，检索质量差
2. **chunk 无上下文**：嵌入时只看到自身文本，不知来自哪个文档的哪个章节
3. **查询原始嵌入**：用户口语化问题直接 embed，与文档的正式表述分布不一致
4. **无查询增强**：短模糊问题、复杂问题没有做任何查询侧优化

## 目标

在现有架构上叠加四个增强组件，不改变核心管线结构。

## 设计

### 一、模型替换：BGE Small EN → BGE Small ZH v1.5

```
BgeSmallEnV15QuantizedEmbeddingModel (384 维, 英文)
  → BgeSmallZhV15QuantizedEmbeddingModel (512 维, 中文)
```

**Maven 变更**（pom.xml）：
```xml
<!-- 替换 -->
<dependency>
  <groupId>dev.langchain4j</groupId>
  <artifactId>langchain4j-embeddings-bge-small-en-v15-q</artifactId>
  <version>1.12.1-beta21</version>  <!-- 删 -->
</dependency>
<dependency>
  <groupId>dev.langchain4j</groupId>
  <artifactId>langchain4j-embeddings-bge-small-zh-v15-q</artifactId>
  <version>1.18.0</version>  <!-- 增 -->
</dependency>
```

**代码变更**（WebApplication.java:47）：
```java
// old
import dev.langchain4j.model.embedding.onnx.bgesmallenv15q.BgeSmallEnV15QuantizedEmbeddingModel;
this.embeddingModel = new BgeSmallEnV15QuantizedEmbeddingModel();

// new
import dev.langchain4j.model.embedding.onnx.bgesmallzhv15q.BgeSmallZhV15QuantizedEmbeddingModel;
this.embeddingModel = new BgeSmallZhV15QuantizedEmbeddingModel();
```

**兼容性影响**：向量维度从 384 → 512，旧 `embedding-store.json` 不兼容，需在启动时检测并自动清空重建。

**其他 langchain4j 依赖**：核心 `langchain4j` 保持在 1.12.1，只需升级 embedding 子模块。如 1.18.0 的 embedding 模块与 1.12.1 核心不兼容，则统一升级所有 langchain4j 依赖到同一版本。

---

### 二、ContextualEnricher（上下文增强器）

**接口**：

```java
public class ContextualEnricher {
    /**
     * 为每个 segment 的文本附加上下文前缀，让嵌入向量感知文档来源和章节位置。
     * 格式：[文档名] 章节路径\n原始文本
     */
    public List<TextSegment> enrich(List<TextSegment> segments, String fileName);
}
```

**实现逻辑**：
1. 遍历每个 segment
2. 从 metadata 读取 `heading_path`（StructureSplitter 已设置，如 `"产品手册 > 第三章 > 配置说明"`）
3. 拼接前缀 `[fileName] heading_path\n`
4. 将前缀 + 原文本设回 `segment`（修改 segment 文本内容是安全的，embedding 在之后执行）

**集成点**：`DocumentService.ingestDirectory()`，在 chunking 之后、`embedAll()` 之前调用。

---

### 三、查询增强

#### 3.1 整体数据流

```
用户问题 + enhancement mode
      │
      v
QueryEnhancementRouter
  ├── mode="none"   → 直接检索
  ├── mode="rewrite" → QueryRewriter
  ├── mode="hyde"   → HyDEGenerator
  ├── mode="both"   → QueryRewriter + HyDEGenerator → RRF 融合
  └── mode="auto"   → LLM 分类 → 选择上述路径
```

#### 3.2 QueryEnhancer 接口

```java
public interface QueryEnhancer {
    /** 返回增强后的查询变体（1~N个），每个独立 embed 检索 */
    List<String> enhance(String query);
}
```

两个实现：

| 实现 | 行为 | LLM 耗时 |
|------|------|---------|
| `QueryRewriter` | 将口语化问题改写为关键词丰富的检索查询 | ~0.5s |
| `HyDEGenerator` | 生成假设文档片段 | ~1-2s |

#### 3.3 QueryEnhancementRouter

```java
public class QueryEnhancementRouter {
    private final QueryRewriter rewriter;
    private final HyDEGenerator hydeGenerator;
    private final ChatModel chatModel;  // 用于 LLM 分类

    /**
     * @param query  用户问题
     * @param mode   auto | rewrite | hyde | both | none
     * @return 增强后的查询变体列表
     */
    public List<String> route(String query, String mode);
}
```

**LLM 分类 Prompt**（mode="auto" 时）：

```
分析用户问题，只返回以下一个标签（不要解释）：
- REWRITE: 问题简短口语化、模糊不清
- HYDE: 事实性、定义性问题
- BOTH: 复杂、开放性问题
- NONE: 问题已经足够具体清晰

问题：{query}
标签：
```

#### 3.4 QueryRewriter

**Prompt**：
```
将用户口语化问题改写为一个信息检索查询（keywords-rich, 简洁, 不用完整句子）。
用户问题：{query}
检索查询：
```

#### 3.5 HyDEGenerator

**Prompt**：
```
根据用户问题，生成一段假设的文档内容来回答这个问题（不超过200字）。
用户问题：{query}
假设文档内容：
```

#### 3.6 RRF 融合（Reciprocal Rank Fusion）

```java
// 多组检索结果去重排序
// RRF_score(d) = Σ 1/(k + rank_i(d))  , k=60
public List<EmbeddingMatch<TextSegment>> fuse(
    List<EmbeddingMatch<TextSegment>> resultA,
    List<EmbeddingMatch<TextSegment>> resultB,
    int topK, int k);
```

#### 3.7 集成点

`RAGService.answerWithSources()` 中，在 `embeddingModel.embed(query)` 之前插入 Router。

---

### 四、API 变更

**POST /api/chat** 增加 `enhancement` 字段：

```json
{
  "query": "向量数据库和传统数据库有什么区别？",
  "enhancement": "auto"
}
```

| 值 | 含义 |
|----|------|
| `auto`（默认） | LLM 自动判断策略 |
| `rewrite` | 强制查询改写 |
| `hyde` | 强制 HyDE |
| `both` | 改写 + HyDE + RRF 融合 |
| `none` | 跳过增强，直接检索 |

ChatController 解析 `enhancement` 字段并传递给 RAGService。

---

### 五、配置变更

#### 5.1 config.json 新增字段

```json
{
  "queryEnhancement": {
    "enabled": true,
    "defaultMode": "auto",
    "rrfK": 60,
    "hydeMaxTokens": 200
  }
}
```

#### 5.2 AppConfig 新增

新增 `QueryEnhancementConfig` 接口：

```java
public interface QueryEnhancementConfig {
    boolean isQueryEnhancementEnabled();
    String getDefaultEnhancementMode();
    int getRrfK();
    int getHydeMaxTokens();
}
```

`AppConfig` 实现该接口，支持环境变量覆盖：
| 属性 | 环境变量 | 默认值 |
|------|---------|--------|
| `enabled` | `RAG_QUERY_ENHANCEMENT_ENABLED` | `true` |
| `defaultMode` | `RAG_QUERY_ENHANCEMENT_MODE` | `auto` |
| `rrfK` | `RAG_QUERY_ENHANCEMENT_RRF_K` | `60` |
| `hydeMaxTokens` | `RAG_QUERY_ENHANCEMENT_HYDE_MAX_TOKENS` | `200` |

#### 5.3 config.example.json 同步更新

---

### 六、文件变更清单

| 文件 | 操作 | 说明 |
|------|------|------|
| `pom.xml` | 修改 | 替换 embedding 依赖 |
| `WebApplication.java` | 修改 | 替换模型 import + 注入新组件 |
| `DocumentService.java` | 修改 | 集成 ContextualEnricher |
| `RAGService.java` | 修改 | 集成 QueryEnhancementRouter |
| `ChatController.java` | 修改 | 解析 enhancement 字段 |
| `AppConfig.java` | 修改 | 新增 QueryEnhancementConfig 实现 |
| `config.example.json` | 修改 | 新增 queryEnhancement 配置段 |
| **新增** | | |
| `ContextualEnricher.java` | 新增 | 上下文增强器 |
| `QueryEnhancer.java` | 新增 | 接口 |
| `QueryRewriter.java` | 新增 | LLM 查询改写 |
| `HyDEGenerator.java` | 新增 | 假设文档生成 |
| `QueryEnhancementRouter.java` | 新增 | 策略路由 + RRF 融合 |
| `QueryEnhancementConfig.java` | 新增 | 配置接口 |
| `ContextualEnricherTest.java` | 新增 | 单元测试 |
| `QueryEnhancementRouterTest.java` | 新增 | 单元测试 |
| `DocumentServiceTest.java` | 修改 | 适配 ContextualEnricher |
| `RAGServiceTest.java` | 修改 | 适配 QueryEnhancementRouter |

---

### 七、测试策略

- `ContextualEnricher`：纯文本逻辑，无需 mock，直接验证前缀拼接和 heading_path 读取
- `QueryRewriter` / `HyDEGenerator`：mock ChatModel，验证 Prompt 构建和返回值处理
- `QueryEnhancementRouter`：mock ChatModel（分类）+ mock Enricher 两个实现，验证四种 mode 的路由分发和 RRF 融合
- 集成测试：保留现有 DocumentServiceTest / RAGServiceTest 结构，新增 mock 验证调用链
- 服务层覆盖率目标：>88%（与项目约定一致）

---

### 八、降级策略

| 场景 | 降级行为 |
|------|---------|
| LLM 分类超时/失败 | 默认使用 `rewrite`，不改写失败则直接检索 |
| QueryRewriter LLM 失败 | 返回原始 query |
| HyDE LLM 失败 | 跳过 HyDE，只用 Rewrite（或直接检索） |
| Both 时一方失败 | 退化为单方结果 |
| ContextualEnricher NPE | heading_path 为空时只用文件名前缀 |
| 新模型文件下载失败 | 日志警告，尝试用旧 EN 模型（如有） |
| 旧 store 数据与新模型维度不匹配 | 自动清空 store 并重新摄入 |
