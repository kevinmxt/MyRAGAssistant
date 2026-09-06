# 架构深化待办

源自 [2026-08-20 架构评审](architecture-review-20260820.html)（基于最近 40 次提交热点分析）。
更早的 2026-07-28 评审五个候选（依赖注入、EmbeddingStoreManager 接口收敛、AppConfig 拆接口、App 工厂化、DocumentController 薄层化）已全部完成，被本轮评审取代。

| # | 候选 | 强度 | 状态 | 要点 |
|---|------|------|------|------|
| 1 | MilvusSession 向量库会话 | Strong | ✅ 完成（ba48d1e→ab9d1f1，已合并 main） | 连接生命周期收敛为深模块，消费者每次拉取引用；计划见 `docs/superpowers/plans/2026-08-20-milvus-session.md`，术语见 `CONTEXT.md`，推迟项见 ADR-0001 |
| 2 | ModelRepository 模型分发 | Strong | ✅ 完成（c1fbc53→a51a80f 五任务系列，含组装根接线；计划 `docs/superpowers/plans/2026-09-02-model-repository.md` 全部落地） | 下载基建收敛为 `service/model` 深模块（镜像回退、原子落盘、状态记账）；精排/嵌入两消费者经组装根接线；环境检测一键安装补齐模型一环；术语见 `CONTEXT.md`，推迟项 ADR-0002 |
| 3 | RetrievalPipeline 检索编排 | Worth exploring | ❌ 待办 | `RAGService.java:223` instanceof RerankConfig 泄漏；answerWithSources 三分支重复"检索→融合→精排→映射"骨架；4 个望远镜构造函数 |
| 4 | ConfigBinder 配置绑定 | Worth exploring | ❌ 待办 | AppConfig 637 行、约 60 键，每加一键改 5 处；方案为每节 record + 通用绑定器，10 个 Config 接口接缝不动 |
| 5 | SseHub SSE 广播 | Speculative | ❌ 待办 | `EnvironmentChecker.java:68,81` 手拼协议串与 JSON；`WebApplication.java:78` sseClients 只写不读；等环境检测再长新事件时做最划算 |

## 推进顺序

评审时的 Top recommendation（候选 1）与候选 2（模型分发）均已完成。下一个按评审优先级是**候选 3（RetrievalPipeline 检索编排）**。

## 被跟踪的接受：下载读超时/watchdog（候选 2 遗留）

终审接受的健壮性缺口，转为被跟踪项：

- **现象**：`HttpModelRepository` 的响应体读取无超时——`HttpRequest.timeout(300s)` 只覆盖到响应头到达，`Files.copy` 读流无界；相对旧 `HttpURLConnection.setReadTimeout(300s)` 是回退。
- **后果**：触发罕见（服务端僵死/网络半开时挂流），但悬挂线程持有同 key 锁，只能重启恢复。
- **为何不当期修**：真修复需 watchdog 关闭挂起的流，与"仓库同步执行、不自管线程"的决议（`CONTEXT.md` 术语、ADR-0002 线程归属）存在设计张力，需专门设计讨论后再动。
- **Task 6 实测补充（2026-09-06）**：同族健壮性缺口——启动下载线程一次性执行、镜像全失败后不重试即永久降级（实测本机 hf-mirror 短时全拒 + huggingface.co 被墙，两制品启动期双双 FAILED）；镜像链含重复项（用户默认镜像与兜底 hf-mirror 相同，每文件多试一轮）。恢复路径（一键安装/重启）已实测可用。重试与镜像去重可与此项一并设计。

## 跟进项：一键安装后自动加载精排（候选 2 遗留，Task 6 发现）

- **现象**：`ModelFileChecker.autoInstall` 只 `ensurePresent` 补文件，无人触发 `CrossEncoderReranker.loadIfPresent()`——一键安装完成后文件齐、env 页 OK，但精排保持降级**直到应用重启**。
- **为何是缝隙**：计划 Task 4 闭环了"安装补文件"，Task 5 的加载触发只接在启动下载线程上；两任务视距内都没出现"装完要加载"的交接。实现与计划一致，属计划缝隙而非实现偏差。
- **修法方向（供设计讨论）**：EnvironmentChecker 安装成功后回调消费者加载（注入 Runnable 钩子），或组装根给 reranker 加轮询/文件监听。涉及跨模块交接，宜与读超时/watchdog 一起过一轮设计再动。
- **出处**：`docs/reviews/model-repository-final-review-20260903.md` Task 6 发现 1；实测恢复路径（重启即加载）已验证。

## 维护约定

- 完成一个候选：状态改 ✅ 并附提交号
- 新一轮评审：新增报告文件 + 在本文件追加章节，不覆盖旧报告
- 报告与待办都随仓库走（`docs/reviews/`），不写到 /tmp 或 tmp/（tmp/ 被 gitignore）
