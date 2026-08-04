## Agent Brief

**Category:** bug
**Summary:** PDF 文档语义切分过度碎片化，导致检索到的 chunk 几乎只有标题，智能体无法读取 PDF 正文内容

**Current behavior:**
PDF 文档通过 Apache Tika 解析为 Markdown 后，表格被拆成大量单行/单格的短文本节点（无标题结构）。`SplitClassifier` 因缺少 H2/H3 标题将其路由到 `SemanticSplitter`。量化 BGE 嵌入下，短片段间的余弦相似度频繁低于 `semanticThreshold`（默认 0.6），导致几乎每个片段都成为独立 chunk。随后 `ContextualEnricher` 在每个 chunk 前加上 `[文件名.pdf]\n` 前缀，使标题文字支配了这些小 chunk 的存储文本和嵌入向量。检索时返回的就是这些标题碎片，智能体只能读到标题。

实证：`embedding-store.json` 历史数据显示 PDF chunk 中位数长度仅 30-40 字符。实际存储示例：`[2021体检报告.pdf]\n正常`、`[用友劳动合同2023.pdf]\n九、争议解决条款`。

**Desired behavior:**
PDF 文档摄入后产生的 chunk 应包含有意义的正文内容。一个 chunk 通常包含几百个字符的连贯文本，即使 PDF 内部是表格数据。检索命中的 chunk 能让 LLM 获取足够的信息来回答问题。

**Key interfaces:**
- `SemanticSplitter` — 需要在合并相邻短段落时施加最小 chunk 大小约束，或在语义切分后再做一次合并
- `ContextualEnricher` — 可考虑仅对长度超过某阈值的 chunk 添加文件名前缀，避免短 chunk 被前缀支配
- `SplitClassifier` — 可对 PDF 文件类型调整分类策略（如让 PDF 也走 StructureSplitter 的按段落合并路径）
- `SplitPlan` 的 `targetChunkSize` — 已存在但 `SemanticSplitter` 未将其作为最小 chunk 约束（仅用作最大值触发再切分）
- `AppConfig` — `semanticThreshold`（0.6）、`maxResults`（3）、`minScore`（0.5）是可能需要调整的配置项

**Acceptance criteria:**
- [ ] PDF 文档摄入后，各 chunk 的长度中位数从 ~30-40 字符提升到至少 200 字符以上
- [ ] 对 PDF 文档提问时，检索返回的 chunk 包含 PDF 的正文内容（而非仅标题）
- [ ] 智能体对 PDF 内容的问答质量明显改善
- [ ] Markdown/纯文本文档的分块质量不受影响（回归）

**Out of scope:**
- 调整 Milvus 存储层——存储和检索管道本身没有问题
- 修改 PDF 解析器（Tika）——问题在分块策略，不在解析
- 改变文档摄入的整体架构
