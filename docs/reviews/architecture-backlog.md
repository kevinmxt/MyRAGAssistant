# 架构深化待办

源自 [2026-08-20 架构评审](architecture-review-20260820.html)（基于最近 40 次提交热点分析）。
更早的 2026-07-28 评审五个候选（依赖注入、EmbeddingStoreManager 接口收敛、AppConfig 拆接口、App 工厂化、DocumentController 薄层化）已全部完成，被本轮评审取代。

| # | 候选 | 强度 | 状态 | 要点 |
|---|------|------|------|------|
| 1 | MilvusSession 向量库会话 | Strong | ✅ 完成（ba48d1e→ab9d1f1，已合并 main） | 连接生命周期收敛为深模块，消费者每次拉取引用；计划见 `docs/superpowers/plans/2026-08-20-milvus-session.md`，术语见 `CONTEXT.md`，推迟项见 ADR-0001 |
| 2 | ModelRepository 模型分发 | Strong | ✅ 完成（c1fbc53→a51a80f 五任务系列，含组装根接线；计划 `docs/superpowers/plans/2026-09-02-model-repository.md` 全部落地） | 下载基建收敛为 `service/model` 深模块（镜像回退、原子落盘、状态记账）；精排/嵌入两消费者经组装根接线；环境检测一键安装补齐模型一环；术语见 `CONTEXT.md`，推迟项 ADR-0002 |
| 3 | RetrievalPipeline 检索编排 | Worth exploring | ✅ 完成（f4936e0..文档收尾提交，七任务系列：三通道 P/E/M 深模块 + RAGService 瘦身为对话门面 + 评估接入 retrieve 入口；测试 158→170） | 计划 `docs/superpowers/plans/2026-09-10-retrieval-pipeline.md` 全部落地；术语见 `CONTEXT.md`；验证与基线重建见 `retrieval-pipeline-verification-20260910.md` |
| 4 | ConfigBinder 配置绑定 | Worth exploring | ✅ 完成（eca70f8→44b6342 六提交系列：@Key+ConfigBinder 引擎、llm 试点、两批铺开、AppConfig 收缩为纯容器；测试 170→179） | 设计 `docs/superpowers/specs/2026-09-20-config-binder-design.md` 全部落地；AppConfig 643→128 行，每键 5 处→1 处；术语见 `CONTEXT.md`，选型见 ADR-0003；验证报告见 `config-binder-verification-20260920.md` |
| 5 | SseHub SSE 广播 | Speculative | ❌ 待办 | `EnvironmentChecker.java:68,81` 手拼协议串与 JSON；`WebApplication.java:78` sseClients 只写不读；等环境检测再长新事件时做最划算 |

## 推进顺序

评审时的 Top recommendation（候选 1）、候选 2（模型分发）、候选 3（检索编排）与候选 4（配置绑定）均已完成。下一个按评审优先级是**候选 5（SseHub SSE 广播，Speculative——等环境检测再长新事件时做最划算）**。

## 被跟踪的接受：下载读超时/watchdog（候选 2 遗留）

终审接受的健壮性缺口，转为被跟踪项：

- **现象**：`HttpModelRepository` 的响应体读取无超时——`HttpRequest.timeout(300s)` 只覆盖到响应头到达，`Files.copy` 读流无界；相对旧 `HttpURLConnection.setReadTimeout(300s)` 是回退。
- **后果**：触发罕见（服务端僵死/网络半开时挂流），但悬挂线程持有同 key 锁，只能重启恢复。
- **为何不当期修**：真修复需 watchdog 关闭挂起的流，与"仓库同步执行、不自管线程"的决议（`CONTEXT.md` 术语、ADR-0002 线程归属）存在设计张力，需专门设计讨论后再动。
- **Task 6 实测补充（2026-09-06）**：同族健壮性缺口——启动下载线程一次性执行、镜像全失败后不重试即永久降级（实测本机 hf-mirror 短时全拒 + huggingface.co 被墙，两制品启动期双双 FAILED）；镜像链含重复项（用户默认镜像与兜底 hf-mirror 相同，每文件多试一轮）。恢复路径（一键安装/重启）已实测可用。重试与镜像去重可与此项一并设计。

## 跟进项：一键安装后自动加载精排（候选 2 遗留，Task 6 发现）——✅ 已修（add642c）

- **现象**：`ModelFileChecker.autoInstall` 只 `ensurePresent` 补文件，无人触发 `CrossEncoderReranker.loadIfPresent()`——一键安装完成后文件齐、env 页 OK，但精排保持降级**直到应用重启**。
- **修法（落地）**：`ModelFileChecker.setOnInstalled(Runnable)` 安装成功钩子，组装根注入 `crossEncoderReranker::loadIfPresent`；TDD 两测锁定（成功触发/失败不触发）。
- **实证（2026-09-06）**：autoDownload=false + 抽走 model.onnx 起应用 → 降级且无下载线程 → 一键安装 → 同一安装线程内日志"精排模型已加载"，免重启生效，终态 OK。
- **出处**：`docs/reviews/model-repository-final-review-20260903.md` Task 6 发现 1。

## 维护约定

- 完成一个候选：状态改 ✅ 并附提交号
- 新一轮评审：新增报告文件 + 在本文件追加章节，不覆盖旧报告
- 报告与待办都随仓库走（`docs/reviews/`），不写到 /tmp 或 tmp/（tmp/ 被 gitignore）
