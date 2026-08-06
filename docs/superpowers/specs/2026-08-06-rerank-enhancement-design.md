# 重排序增强（Re-rank）设计

2026-08-06

## 目标

在多路召回 + RRF 融合之后，插入 Cross-Encoder 精排层，提升最终送给 LLM 的 Top-K 片段相关性。

## 架构

```
MultiRecallRouter → [Dense/Sparse/Graph] 并行召回(多拿3倍)
                 → RRF 融合
                 → CrossEncoderReranker 精排
                 → LLM
```

## 接口

```java
public interface Reranker {
    String name();
    boolean isAvailable();
    List<EmbeddingMatch<TextSegment>> rerank(String query, List<EmbeddingMatch<TextSegment>> candidates, int topK);
}
```

`isAvailable()` 由启动时模型检测决定；不可用时 `rerank()` 原样返回 candidates 并切片到 topK。

## CrossEncoderReranker

- 模型：`bge-reranker-v2-m3` ONNX 格式，与现有 BgeSmallZhV15 同系列
- 输入：tokenize(query, passage) 对，batch 打包一次推理
- 输出：logits → sigmoid → 0~1 相关性分数
- 按分数降序取 topK

**降级策略：**
- 启动时自动检测 `rerankModelPath`，模型文件存在则加载 ONNX 模型，标记 available=true
- 文件不存在 → `log.warn("精排模型未找到，重排序已降级跳过")`，available=false
- 无独立 enable/disable 开关，始终生效（有模型就精排，没有就跳过）

## 配置

新增 `RerankConfig` 接口，`AppConfig` 实现：

| 参数 | 默认值 | 说明 |
|------|--------|------|
| `rerankModelPath` | `models/bge-reranker-v2-m3` | ONNX 精排模型路径 |
| `rerankExpansionFactor` | 3 | 粗召回扩展倍数，召回数量 = topK × 此值 |
| `rerankTopK` | 5 | 精排后最终返回数 |

`config.json` 格式：
```json
{
  "rerank": {
    "modelPath": "models/bge-reranker-v2-m3",
    "expansionFactor": 3,
    "topK": 5
  }
}
```

环境变量：
- `RAG_RERANK_MODEL_PATH`
- `RAG_RERANK_EXPANSION_FACTOR`
- `RAG_RERANK_TOP_K`

## 数据流集成

`RAGService.answerWithSources` 两个路径均插入重排序：

1. **多路召回路径：** `multiRecallRouter.recall()` 返回 topK × 3 → `reranker.rerank()` 精排到 topK
2. **查询增强路径：** RRF 融合后 → `reranker.rerank()` 精排

`MultiRecallRouter.recall()` 内部自动乘 expansionFactor 多拉候选。

## 文件清单

| 操作 | 文件 | 职责 |
|------|------|------|
| 新增 | `service/vector/rerank/Reranker.java` | 重排序接口 |
| 新增 | `service/vector/rerank/CrossEncoderReranker.java` | ONNX Cross-Encoder 实现 |
| 新增 | `config/RerankConfig.java` | 配置接口 |
| 修改 | `config/AppConfig.java` | 实现 RerankConfig + 解析 |
| 修改 | `service/RAGService.java` | 集成 Reranker 调用 |
| 修改 | `service/vector/recall/MultiRecallRouter.java` | recallTopK × expansionFactor |
| 修改 | `web/WebApplication.java` | 组装 Reranker 依赖 |

## 测试

- `CrossEncoderRerankerTest`：mock ONNX 推理，验证排序和降级
- `RAGServiceTest`：验证 reranker 集成、null 安全
- 集成测试：端到端召回 → 重排序 → 验证结果质量提升
