# ModelRepository 分支最终评审报告（2026-09-03）

- 分支：`worktree-model-repository`（fe13299 → ddc440a，6 提交，已合回 main）
- 计划：`docs/superpowers/plans/2026-09-02-model-repository.md`（架构评审候选 2）
- 评审方式：每任务规格+质量双审 → 全分支终审（含 mvn test 实跑复核、HF 制品清单 API 独立核对、旧配置键 grep 复查）
- 终审结论：**Ready to merge (With fixes)** → 修复波 ddc440a 后复审 4/4 ADDRESSED、无新破坏

## 终审发现与处置

| 级别 | 发现 | 处置 |
|------|------|------|
| Important | 制品注册只在 ensurePresent（autoDownload 分支）发生：autoDownload=false 手动预置模型时环境页永久误报缺失；默认配置下 env-check 先于下载线程有注册竞态 | 已修（ddc440a）：构造重载 `(List, ModelArtifact...)` 预填 + 组装顺序对调 + 锁定测试 |
| Important | autoInstall 失败不回流 install-log，与"请检查日志"文案矛盾 | 已修（ddc440a）：catch 内 `log.accept("下载失败: …")`，测试断言加强 |
| 接受+跟踪 | body 读取无超时（`HttpRequest.timeout` 只到响应头；相对旧 `setReadTimeout(300s)` 是健壮性回退；悬挂线程持同 key 锁需重启） | backlog 已登记"被跟踪的接受：下载读超时/watchdog"，Task 6 端到端验证时留意 |
| 观察 | FAILED 记账粘性：下载失败后手动放置文件，state() 仍报 FAILED 直到重启 | 延后，可并入 backlog 候选 2 遗留项一并考虑 |

## 延后 minor 清单（合并后可择机处理）

1. 大小比较分支对 JDK HttpClient 截断不可达（代理改写场景的纵深防御，建议加注释说明）
2. 空镜像列表时异常消息含 "null"（仅测试可构造）
3. InterruptedException 路径 `.part` 残留（不变式成立：目标文件永不被半截创建）
4. 多文件部分失败重试语义（只补下缺失文件）无测试锁定
5. `shouldNotStartDownloadThreadOnConstruct` 与 `shouldDegradeWhenModelNotFound` 断言等价（计划明示的行为等价形状）
6. 测试间 expansionFactor/topK stub 风格不一致（纯外观）
7. README 表序与 config.example.json 节序不一致（纯观感）
8. `shouldImplementAllConfigInterfaces` 从未断言 EvaluationConfig（先于本分支存在）
9. check() MISSING 分支不展示 state.detail()（缺哪个目录不可见）
10. 测试 fake 的 failOnEnsure 类型为 RuntimeException，偏宽
11. DOWNLOADING 映射到 MISSING 而非 UI 已有的 INSTALLING 态（靠文案区分）
12. User-Agent 从 `MyAIDemo2/1.0` 变为 HttpClient 默认（未声明的行为变化）
13. 镜像列表可能重复（默认配置镜像与兜底重复，404 时每个文件多试一轮）
14. EnvironmentChecker.install() 与 checkAll() 共享监视器，GB 级安装期间手动重检静默排队（既有模式被放大）
15. 旧 `rerank.autoDownload=false` 用户升级后静默落到新默认 true（README 升级说明已覆盖；将来 ConfigBinder 可加未知键告警）

## 执行期裁定记录

- **F1**：计划称"AppConfigTest 未引用被删键"系事实错误，Task 3 同步迁移测试断言到 model.* 新键
- **F2**：Task 4 改 ModelFileChecker 构造器允许 WebApplication 过渡性内联适配，Task 5 吸收消除
- **F3**：Task 2 保留旧 stub（裸 mock 无 strict-stub 风险，删读后无害）
- **T1-1**：读超时缺口接受并登记跟踪（见上表）
- **FAILED→ERROR**：ModelFileChecker 状态映射裁量（终审核实与编排器语义兼容，分开计数、都告警）

## Task 6 端到端验证清单（待执行，需网络/Docker）

- `mvn clean package` → 清空模型目录起应用：日志按序出现 reranker 下载 → 加载；env 页 model-files 显示"下载中/完成"；下载中重复点安装被防重
- 精排链路冒烟：上传文档 → 提问 → 响应含精排（或降级路径日志干净）
- LightRAG 冒烟：kg 初始化成功（嵌入模型目录由仓库补齐，10 文件清单已按 HF API 核实）
- 留意悬挂场景：下载中网络停滞时状态是否长期停留 DOWNLOADING（T1-1 跟踪项）
- 行为与计划有偏差时回填计划备注；backlog 候选 2 终态确认

## Task 6 验证结果（2026-09-06 执行，结论 PASS）

实测环境：清空模型目录冷启动 → hf-mirror 真实下载（reranker 2.29GB 三件套 + embedding 10 文件）→ Docker/Milvus 接入 → 摄取/问答 → 重启。

### 通过项

- 清空目录冷启动：`model-download-reranker` 与 `lightrag-init` 两线程按设计启动，env 页报"缺失 → 可一键安装"（构造期注册生效，无"未注册制品"）
- 失败路径干净：本机网络瞬断导致两制品各 3 次连接超时后置 FAILED 降级（reranker 降级、LightRAG 交自身降级），**无 .part 残留**、无半截文件
- 一键安装恢复：`POST /api/env/install` → 异步重下**仅缺失文件**（启动期已完成的 5 个小文件未重下）→ 原子落盘（.part → rename 实测）→ install-done → 重检 `OK (reranker 3 个文件就绪, embedding 10 个文件就绪)`
- 下载中可见性：env 接口 `INSTALLING/正在安装` + `installInProgress=true`，install-log SSE 逐文件流式（"开始下载/已下载 N 字节"）
- 防重：下载中重复 POST install → **HTTP 409 "已有安装任务进行中"**
- 嵌套路径：`1_Pooling/config.json` 子目录正确落盘
- 重启路径：文件齐备时启动**零网络请求**（无"开始下载"日志），构造器即加载精排（"精排模型已加载"），env 检查在任何 ensurePresent 前现算"文件齐全"
- 精排冒烟：摄取 modelrepo.txt → 提问 → **top source 命中新文档**（旧垃圾块不再霸榜），精排激活参与链路；降级期间链路透明无噪音
- Milvus 断连 → 重连（`/api/env/reconnect-milvus` 200 → 状态 OK）

### 发现（按重要度）

1. **⚠️ 一键安装完成后精排不自动加载**（实现与计划一致，属计划缝隙）：安装路径只 `ensurePresent`，无人触发 `loadIfPresent`——装完文件齐、env 页 OK，但精排保持降级**直到重启**。恢复路径已实测（重启即加载）。后续项已登记 backlog。
2. **启动失败无自动重试**：一次性 daemon 线程，镜像全超时即永久降级（本机实测 hf-mirror 短时全拒绝连接，huggingface.co 被墙不可达）；恢复靠一键安装或重启。镜像链含重复项（默认镜像与兜底相同，每文件多试一轮）。与读超时同属健壮性家族，并入 backlog 跟踪条目。
3. **LightRAG kg 冒烟受阻（环境）**：Python 3.14.5 无 numpy/lightrag 兼容轮子，`pip install lightrag` metadata-generation-failed；安装编排本身工作正常（事件流/防重/状态正确）。嵌入模型目录 10 文件已由仓库补齐，桥接失败点在 `import lightrag`，先于嵌入加载——仓库侧职责已完成。
4. **既有问题（非本分支引入）**：Milvus 集合残留旧 PDF 垃圾块污染 top-3（三段同分 0.858）；LLM 调用间歇失败（answer 偶发 null，QueryRewriter 栈迹）。
5. T1-1 悬挂场景未在真实网络复现（连接层超时正常触发 10s；body 级停滞未发生），维持跟踪。

### 遗留物

- 验证日志：`app-task6.log`、`app-task6-restart.log`、`sse-install.log`、`tmp-docs-verify/`（未提交，可删）
- 旧模型备份：`../model-backup-20260906/bge-reranker-v2-m3`（2.2GB，新下载已验证可用，确认后可删）
- Milvus 集合新增 1 个测试 segment（modelrepo.txt）
