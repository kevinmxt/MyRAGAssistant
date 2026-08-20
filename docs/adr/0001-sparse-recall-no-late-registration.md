# ADR-0001: 重连后 sparse 召回不自动恢复（推迟）

- 状态：已接受
- 日期：2026-08-20

## 背景

MilvusSession 深化（架构评审候选 1）修复了"重连后消费者持有过期 `MilvusClientV2` 引用"的问题：常见路径（启动时 Milvus 正常、之后重连）由拉模型 + 状态监听覆盖。

但仍有两个残留缺口，都位于"启动时 Milvus 不可用、之后重连"这条路径上：

1. 组装根的召回注册表在构造时静态注册 sparse 策略（`if (milvusClientV2 != null)`）——启动时 Milvus 挂了，重连后 sparse 策略不在注册表里，直到重启。
2. `SparseRecallStrategy.detectSparseField` 在构造时对当时的连接派生 `sparseAvailable`——Milvus 重启且 schema 变化时该事实过期。

触发完整路径需要同时满足：`multiRecallEnabled=true`（默认 false）+ `recallModes` 含 `sparse`（默认仅 dense）+ 启动时 Milvus 不可用 + 之后重连。

## 决定

推迟修复。不为一个默认关闭的功能给 `MultiRecallRouter` 的注册表引入动态注册（可变 Map / Supplier 化 / 并发考量）。理由：按"一个适配器 = 假设性接缝"原则，现在预建注册表可变性是在为不存在的第二适配者付费。

届时也不预建状态监听器：当前无生产使用者（环境重检由重连端点自行编排），为假设的使用者保留无主接口成员正是要避免的浪费。

## 后果

- 该路径下的用户需要重启应用才能获得 sparse 召回。
- 未来真需求出现时：给 MilvusSession 加状态监听（形状已知：`Consumer<State>`，广播 CONNECTED/DEGRADED 变化），`SparseRecallStrategy` 据此重算可用性，并让注册表支持会话感知的注册，改动是局部的。
- 未来架构评审不应把本缺口当作新发现重新提出——接此 ADR 即可。
