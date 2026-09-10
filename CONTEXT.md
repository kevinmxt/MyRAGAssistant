# CONTEXT — 领域词汇表

本仓库的领域术语。工程技能的输出（重构提案、issue 标题、测试名）应使用此处定义的术语，避免同义词漂移。新术语由 `/domain-modeling` 惰性创建。

## 术语

### 向量库会话（MilvusSession）

拥有向量存储**连接生命周期**的模块：当前活跃的 `EmbeddingStore`、其原生 `MilvusClientV2` 客户端、以及 DEGRADED（内存降级）↔ CONNECTED（Milvus）之间的**原子切换**（换 store + 重建文档索引一步完成，不变式："docIndex 永远匹配活跃 store"）。

- 消费者每次使用时**拉取**当前引用（`nativeClient()`），不缓存——重连后永不过期。
- `connect()` 是唯一的建连路径（探针快速失败 → 连接 → 原子切换），启动初始化与重连共用。
- `probe()` 是纯读探测（可达性 + 版本），环境检测的 Milvus 检测项委托于此。

*避免叫*：MilvusManager（"Manager"不传达时态语义）、VectorStoreSession（接口含 Milvus 专有类型，名不副实）。

### 模型仓库（ModelRepository）

拥有模型制品**本地存在性**的模块：制品清单（repo + 文件集 + 目标目录）、镜像回退链（用户镜像 → hf-mirror.com → huggingface.co）、原子落盘（.part 临时文件 + rename）、逐制品状态记账（MISSING / DOWNLOADING / PRESENT / FAILED）。

- `ensurePresent(artifact)` 幂等：文件齐全跳过、缺失补下，失败抛 `ModelDownloadException`
- 仓库本身**同步**，线程调度归消费者（组装根 daemon thread），不自管线程池
- 环境检测的模型检测项（ModelFileChecker）委托 `state()` 查询真实状态，并经 `autoInstall` 接一键安装

*避免叫*：ModelDownloader（只表达下载，丢失"存在性 + 状态"语义）、ModelManager（"Manager"空泛）。

### 检索管线（RetrievalPipeline）

系统**唯一检索事实源**：对外单一入口 `retrieve(query, overrides)`，内部按覆盖参数选通道——P（朴素稠密检索）、E（查询增强，单变体单路 / 多变体 RRF 融合）、M（多路召回）——三通道殊途同归到**统一后处理**（精排可用时精排取 rerankTopK，不可用时截断到 maxResults）。

- 通道是**内部接缝**：调用方（对话门面 RAGService、评估管线）只传 `RetrievalOverrides`（enhancement / recall），不感知通道实现与选择逻辑。
- 候选池**仅在精排可用时扩展**（×expansionFactor）：不可用时 ×1 直接截断——稠密检索天然有序，扩了不裁等于白搜。
- LLM 上下文与前端展示的 sources 恒等：`RAGService` 卸下 contentRetriever，answer 由 `composePrompt(query, sources)` 显式携带参考资料生成，无双轨检索。

*避免叫*：RetrievalService（不表达"编排"）、SearchOrchestrator（丢"检索增强"语义）。

## 相关决策

- `docs/adr/0001-sparse-recall-no-late-registration.md` — 重连后 sparse 召回不自动恢复（推迟）
- `docs/adr/0002-model-download-deferred.md` — 模型下载不做断点续传与进度广播（推迟）
