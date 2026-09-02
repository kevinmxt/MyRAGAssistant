# ADR-0002: 模型下载不做断点续传与进度广播

日期：2026-09-02
状态：已接受（含推迟项）

## 背景

架构评审候选 2（`docs/reviews/architecture-review-20260820.html`）将模型下载从 CrossEncoderReranker 抽成 ModelRepository（模型仓库），精排模型与 LightRAG 嵌入模型两个消费者共用。评审 After 图提到"断点/失败恢复"与进度回调。

## 决策

本期实现：`.part` 临时文件 + 原子 rename、Content-Length 大小校验、`java.net.http.HttpClient` 自动重定向、`ensurePresent` 幂等可重试。

**推迟两项：**

1. **Range 断点续传** — 需处理 206/416 响应与 ETag 变化，实现与测试成本高；中断后重试 = 整文件重下，配合镜像回退链与手动重试（一键安装按钮）可接受。
2. **下载进度 SSE 广播** — 接口不设 progress 参数；逐文件的开始/完成日志行经 ModelFileChecker.autoInstall 流入现有 install-log SSE。百分比级进度等架构候选 5（SseHub）落地后以领域事件广播，避免接口背上只有一个消费者的参数。

## 后果

GB 级权重（bge-reranker-v2-m3 的 model.onnx_data）中断后重试流量大；install-log 只有文件粒度日志，无实时百分比。将来加回时：续传在 HttpModelRepository 内部实现，接口不变；进度经 SseHub 广播，消费者订阅。
