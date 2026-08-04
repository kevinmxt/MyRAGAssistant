# 文档切割策略增强 — 设计文档

2026-07-30 | 方案 B：分类器 + 策略工厂

## 目标

将现有单一递归字符切分替换为自适应混合切分策略：根据文档类型、大小、结构特征，自动选择最优切分方式（结构/语义/智能体精炼），提升 RAG 检索片段的边界合理性和内容连贯性。

## 技术选型

| 组件 | 选型 | 说明 |
|------|------|------|
| Markdown 转换 | Pandoc（主）+ Apache Tika（fallback） | Pandoc 可选安装，不可用时降级 Tika |
| 结构分析 | flexmark-java | Markdown AST 解析，提取标题/段落/代码块 |
| 语义切分 | 自研 | LangChain4j 无此功能，约 100 行 |
| 智能体精炼 | 自研 + 已有 ChatModel | Prompt 驱动，约 80 行 |

## 模块划分

新增 `service/chunking/` 子包：

```
service/
├── chunking/
│   ├── ChunkingPipeline.java      # 切分入口，编排全流程
│   ├── converter/
│   │   └── MarkdownConverter.java # PDF/DOCX → Markdown（Pandoc + Tika fallback）
│   ├── analyzer/
│   │   └── StructureAnalyzer.java # flexmark AST → DocStructure
│   ├── classifier/
│   │   └── SplitClassifier.java   # 规则驱动，输出 SplitPlan
│   ├── splitter/
│   │   ├── SplitStrategy.java     # 策略接口
│   │   ├── StructureSplitter.java # 按标题/段落结构切分
│   │   ├── SemanticSplitter.java  # 按 embedding 相似度断点切分
│   │   └── AgentRefiner.java      # 智能体后置精炼（可选）
│   └── evaluator/
│       └── ChunkEvaluator.java    # 切分质量评分
```

## 数据流

```
原始文档 (PDF/DOCX/其他)
  │
  ▼
MarkdownConverter ─── 输出 Markdown 文本
  │
  ▼
StructureAnalyzer ─── 输出 DocStructure（标题树、段落列表、代码块位置、表格位置）
  │
  ▼
SplitClassifier ─── 输入 DocStructure + 文件元信息 → 输出 SplitPlan
  │
  ▼
Splitter Chain ─── 按 Plan 顺序执行切分 → List<TextSegment>
  │
  ▼
AgentRefiner ─── 仅在 Plan 标记需要时调用
  │
  ▼
ChunkEvaluator ─── 输出质量评分
  │
  ▼
Embedding + Store（现有流程）
```

## 核心接口

### 数据结构

```java
record HeadingNode(int level, String title, int startLine, int endLine) {}

record DocStructure(
    List<HeadingNode> headingTree,
    List<Range> paragraphRanges,
    List<Range> codeBlockRanges,
    List<Range> tableRanges,
    int totalLength
) {
    boolean hasClearHeadingHierarchy(); // h2/h3 数量 >= 2
    double codeBlockRatio();
    int maxSectionDepth();
}

record Range(int start, int end) {}

record SplitPlan(
    List<StrategyEntry> strategies,
    boolean needsAgentRefinement,
    int targetChunkSize
) {}

record StrategyEntry(SplitStrategy strategy, Map<String, Object> params) {}
```

### 接口

```java
interface SplitStrategy {
    String name();
    List<TextSegment> split(String markdownContent, DocStructure structure, int targetChunkSize);
}

interface MarkdownConverter {
    String convert(Path filePath) throws ConversionException;
    boolean isAvailable(); // Pandoc 是否可用
}

interface StructureAnalyzer {
    DocStructure analyze(String markdownContent);
}

interface SplitClassifier {
    SplitPlan classify(DocStructure structure, String fileType, String fileName);
}

interface ChunkEvaluator {
    double evaluate(List<TextSegment> chunks, DocStructure structure);
}
```

### 编排入口

```java
class ChunkingPipeline {
    List<TextSegment> execute(Path filePath, String fileType);
}
```

## 策略选择规则

| 条件 | 策略 | 参数 |
|------|------|------|
| 有明确标题层级（h2/h3 >= 2） | 结构切分优先 | 以 h2/h3 为分段边界 |
| Markdown 代码块占比 > 30% | 代码块保持完整，不切割 | — |
| 文档 < 5000 字符 | 仅结构切分 | — |
| 文档 > 20000 字符、结构平坦 | 结构 + 语义混合 | 语义断点阈值 0.6 |
| 结构切分与语义切分歧异大 | 启用 AgentRefiner | — |

分类器返回 `SplitPlan`，规则表硬编码，后续可演进为配置驱动。

## 切分器设计

**StructureSplitter**：遍历 DocStructure，在 h2/h3 边界切分；代码块和表格作为原子单元不切割；单个 section 超长时回退段落级切分；每个分段附加标题路径元数据（如 `## 安装 > ### 依赖配置`）。

**SemanticSplitter**：按段落拆成候选片段 → 逐对计算相邻段落 Embedding 余弦相似度 → 相似度低于阈值处标记断点 → 合并断点间段落，超长则回退句子级。

**AgentRefiner**：包装器，非独立策略。仅在分类器判定差异大时调用。输入两版分段对比 + 原始 Markdown，让 DeepSeek 判断哪个版本更合理或合并优点。默认关闭，通过配置开启。

## 错误处理与降级

三级降级，任何环节失败不阻断摄入：

```
Pandoc 不可用/失败 → 降级 Tika
结构分析失败 → 段落边界平替，标题树为空
语义切分失败 → 降级结构切分 → 兜底递归字符切分（现有行为）
AgentRefiner 失败 → 跳过精炼，记录 warn 日志
```

## 测试策略

| 层级 | 内容 | 工具 |
|------|------|------|
| 单元 | SplitClassifier 决策规则 | JUnit 5 + AssertJ |
| 单元 | StructureAnalyzer 结构提取 | JUnit 5 + flexmark |
| 单元 | SemanticSplitter 相似度断点 | JUnit 5 + Mock EmbeddingModel |
| 集成 | ChunkingPipeline 端到端 | JUnit 5 + 真实组件 |
| 回归 | DocumentServiceTest 原有行为 | 已有测试 |

测试文档放在 `src/test/resources/documents/`。

## 配置变更

`config.json` `document` 段新增 `chunking` 子配置：

```json
"chunking": {
    "mode": "auto",
    "semanticThreshold": 0.6,
    "enableAgentRefiner": false,
    "maxChunkSize": 2000
}
```

- `mode`: `auto`（分类器决策）| `structure` | `semantic` | `recursive`（legacy 向后兼容）
- `enableAgentRefiner`: 智能体精炼开关，默认 false

## DocumentService 变更

`ingestDirectory()` 中原两行：

```java
DocumentSplitter splitter = DocumentSplitters.recursive(chunkSize, chunkOverlap);
List<TextSegment> segments = splitter.split(document);
```

替换为：

```java
List<TextSegment> segments = chunkingPipeline.execute(file.toPath(), detectFileType(file.getName()));
```

其余逻辑不变。
