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
