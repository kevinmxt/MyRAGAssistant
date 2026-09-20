# 候选 4 ConfigBinder 配置绑定 — 验证报告

日期：2026-09-20
分支：`config-binder`（eca70f8→beb2f8d，6 提交）
设计：`docs/superpowers/specs/2026-09-20-config-binder-design.md`；选型：`docs/adr/0003-annotation-driven-config-binder.md`

## 规模对比

| 项 | 迁移前 | 迁移后 |
|---|---|---|
| AppConfig | 643 行（49 键手写五处） | 128 行（纯容器：load + 11 节访问器） |
| 每加一键 | 5 处（字段/默认值/file 行/env 行/getter） | **1 处**（@Key 注解的 record 构造参数） |
| 绑定逻辑 | 分散在 applyFileConfig/applyEnvOverrides | 集中 ConfigBinder 157 行 |
| 配置节 | — | 11 个 record（15~40 行/个，直接 implements Config 接口） |
| Config 接口 | 12 个 | 12 个，**签名零改动**（diff 无接口文件） |

## 行为等价性证据

- `mvn test` 179/179 全绿（170 迁移前 + 新增 ConfigBinderTest 6 + AppConfigTest 净增 3；原有默认值断言语义全部保持，仅取值路径改经节访问器）
- 应用冒烟 2 次（试点后、收缩后）：Javalin 启动、`/api/health` 200、Milvus DEGRADED 与 LightRAG 降级路径与迁移前一致
- AppConfigTest 默认值断言全量保留（shouldHaveSensibleDefaults 等 12 测试）；instanceof 断言迁至节 record（sectionsShouldImplementConfigInterfaces）
- `milvus.collectionName` 双节绑定恒等有专门测试（shouldBindSameCollectionNameInMilvusAndRecallSections）

## 顺带修正的三处既有坑（设计已决）

1. `recallModes` config.json 层兼容逗号分隔串（与其他两个 List 键对齐，测试锁定）
2. env 数值非法时 warn 日志后回退默认值（原为静默回退）
3. `load(Path)` 路径可注入——原 `shouldLoadFromConfigJson` 名不副实（注释自认没测到 file 路径），现已做实为真集成测试

## 声明性偏差登记（双轴评审发现，均源于 spec 内部张力）

1. **App 三处取值路径变化**：spec 写「App 零改动」但同句要求「AppConfig 不再实现任何接口」——卸接口后 `getPort()` 必然消失，实现取最小化解：`config.server().getPort()` ×3。行为不变。
2. **两个测试改传节**：EvaluationTest/MultiRecallRouterIT 此前直接依赖 AppConfig 具体类型（spec「仅 App/WebApplication 依赖具体类型」的论断对测试不成立），改为 `appConfig.evaluation()`/`.recall()`。组件自身与其测试零改动。
3. **超出 spec 的两个小增项**：`load(Path, Function envLookup)` 双参重载（宿主机真实 `RAG_LLM_API_KEY` 会污染测试，密闭性必需）；`LlmSettings.toString` 覆盖（record 自动 toString 会把 apiKey 打进日志）。
4. **logback.xml DEBUG→INFO 混入提交 fcddac7**：这是用户会话前留在工作区的本地改动，被批量 `git add -A src/` 卷入。变更本身是用户有意的，保留；如不欲发布可单独 revert。

## config.example.json 核对

逐键对照 @Key 声明：十节全部一致；修正三处——补 `document.chunking` 块（代码支持但示例从未展示）、补 `store.filePath`（同因）、删 `milvus.consistencyLevel`（新旧代码均不读取的死键）。

## 流程记录

Matt 流（grill-with-docs 复刻 → to-spec → to-tickets 本地票据 → implement/TDD 红绿循环 → 双轴 code-review）。票据在 `.scratch/config-binder/issues/`（已 gitignore）。评审结论：Standards 0 硬违规 + 4 判断题（不阻塞）；Spec 0 语义回归 + 上述声明性偏差。
