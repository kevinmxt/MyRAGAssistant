# CONTEXT — 领域词汇表

本仓库的领域术语。工程技能的输出（重构提案、issue 标题、测试名）应使用此处定义的术语，避免同义词漂移。新术语由 `/domain-modeling` 惰性创建。

## 术语

### 向量库会话（MilvusSession）

拥有向量存储**连接生命周期**的模块：当前活跃的 `EmbeddingStore`、其原生 `MilvusClientV2` 客户端、以及 DEGRADED（内存降级）↔ CONNECTED（Milvus）之间的**原子切换**（换 store + 重建文档索引一步完成，不变式："docIndex 永远匹配活跃 store"）。

- 消费者每次使用时**拉取**当前引用（`nativeClient()`），不缓存——重连后永不过期。
- `connect()` 是唯一的建连路径（探针快速失败 → 连接 → 原子切换），启动初始化与重连共用。
- `probe()` 是纯读探测（可达性 + 版本），环境检测的 Milvus 检测项委托于此。

*避免叫*：MilvusManager（"Manager"不传达时态语义）、VectorStoreSession（接口含 Milvus 专有类型，名不副实）。

## 相关决策

- `docs/adr/0001-sparse-recall-no-late-registration.md` — 重连后 sparse 召回不自动恢复（推迟）
