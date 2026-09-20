# 候选 4：ConfigBinder 配置绑定 — 设计 Spec

日期：2026-09-20
状态：已确认（grilling 两轮八项决策）
出处：`docs/reviews/architecture-backlog.md` 候选 4；选型论证见 `docs/adr/0003-annotation-driven-config-binder.md`；术语见 `CONTEXT.md`「配置绑定」

## Problem Statement

开发者每新增一个配置键，都要在 AppConfig 的五个位置同步修改（字段声明、构造器默认值、config.json 解析行、环境变量覆盖行、getter）。49 个键挤在一个 643 行的巨类里，json 路径、环境变量名、Java 字段名三套命名全靠人肉对齐，漏改或对不齐不会有任何编译期提示。此外现有加载逻辑存在三处隐藏坑：`recallModes` 的 config.json 层只认数组（另两个 List 键数组和逗号串都认，行为不一致）；环境变量数值非法时静默回退默认值（无日志）；`load()` 的配置文件路径写死不可注入，文件加载路径没有任何测试覆盖。

## Solution

引入注解驱动的通用配置绑定器（ConfigBinder）：每个配置键在"配置节"record（如 LlmSettings）的构造参数上以一个 `@Key` 注解声明 json 点路径、环境变量名与字符串默认值——**每键一处**。绑定器按优先级链（代码默认 → config.json → 环境变量）统一取值与类型转换，把每个节装配成不可变 record，节 record 直接实现对应的 Config 接口（12 个消费者接缝零变化）。AppConfig 从 643 行实现巨类退化为轻容器：`load()` 装配 11 个节并暴露节访问器。顺带修正三处既有坑（List 键统一兼容数组与逗号串、非法 env 值 warn 日志、load 路径可注入）。

## User Stories

1. 作为开发者，我想新增一个配置键只在一处写注解声明（json 路径 + env 名 + 默认值），这样五处同步修改和人肉对齐三套命名的错误类别彻底消失。
2. 作为开发者，我想每个配置节是不可变的 record（一次装配终身不变），这样配置对象可以放心地在组件间共享。
3. 作为开发者，我想节 record 直接实现既有 Config 接口（LlmConfig、MilvusConfig 等），这样所有消费者组件零改动。
4. 作为组件（如 CrossEncoderReranker、EnvironmentChecker），我继续按窄接口收配置，不感知配置是从 config.json 还是环境变量来的。
5. 作为运维者，我想"环境变量覆盖 config.json、config.json 覆盖代码默认值"的优先级链保持现状，这样既有部署方式不变。
6. 作为运维者，我在 config.json 里写 List 型键时数组和逗号分隔串都能被接受（三个 List 键行为一致），这样不用记哪个键有哪种限制。
7. 作为运维者，我写错环境变量数值（如 `RAG_SERVER_PORT=abc`）时应用日志给出 warn 而不是静默用默认值，这样配置错误可被发现。
8. 作为应用入口（App），我只调用 `AppConfig.load()` 一行拿到全部配置，不感知内部分节。
9. 作为组装根（WebApplication），我通过 AppConfig 容器的节访问器把各节传给组件，构造签名与流程不变。
10. 作为测试编写者，我能给 `load()` 注入配置文件路径，这样文件加载路径可以被真正的测试覆盖。
11. 作为测试编写者，我有绑定器的单元测试矩阵（五种类型 × 三层数据源 × 非法值回退），这样绑定器的类型转换逻辑可信。
12. 作为测试编写者，既有 AppConfigTest 的默认值断言全部保持通过，这样迁移没有改变对外可观测的配置语义。
13. 作为维护者，我想 LLM 节先端到端打通（绑定器 + record + 容器 + 组装根 + 测试全链），模式验证后再机械铺开其余十节，这样试点期间有中途叫停的检查点。
14. 作为维护者，我过目试点 diff 并点头后才开始铺开，这样方向偏差不会扩散到全部十一节。
15. 作为未来读者，配置键的声明（路径/环境变量/默认值）在节 record 里一眼可见，不用在 643 行文件里上下翻找对齐。
16. 作为维护者，chunking 相关的四个键与文档节平铺在同一个节 record 里（json 路径 `document.chunking.*`），这样不为没有独立消费者的接口单建一节。
17. 作为维护者，跨节键（`chat.memorySize` 归检索节、`store.filePath` 归服务器节）由注解声明实际 json 路径，这样节切分跟接口走而不是跟 json 节名走。
18. 作为维护者，`milvus.collectionName` 在 Milvus 节与 Recall 节各自绑定同一 json 键，两处取值恒等，这样两个接口的既有声明不动。

## Implementation Decisions

- **绑定机制**：`@Key` 注解（json 点路径、env 名、def 字符串默认值）+ 反射通用绑定器；类型由 record 构造参数反射决定，支持 String/int/double/boolean/List<String>。
- **结构**：11 个配置节 record 一一对应 11 个 Config 接口并直接实现；record 组件命名用 getter 风格（如 `String getApiKey`），访问器即接口方法，零桥接样板；每键一处声明。
- **AppConfig 容器化**：保留类名与 `load()`，内部 11 个 final record 字段 + 节访问器（`llm()/retrieval()/...`），不再实现任何 Config 接口；App 零改动，WebApplication 构造签名不变、内部传参点改传节。
- **chunking 四键**平铺进文档节（json 路径 `document.chunking.*`），不建独立节。
- **语义修正三项**：List 键 json 层统一兼容数组与逗号串；env 数值非法 warn 后回退默认；`load()` 支持注入配置文件路径。
- **切分映射**：`chat.memorySize`→检索节、`store.filePath`→服务器节；`milvus.collectionName` 双节各自绑定同键；LightRAG 四键留在召回节（九键，不拆）。
- **迁移**：LLM 节试点端到端先行，验收（测试全绿 + 冒烟 + 用户过目 diff）后机械铺开。
- 选型论证与替代方案否决理由见 ADR-0003；术语（配置节/绑定器/优先级链）见 CONTEXT.md。

## Testing Decisions

- 只测外部行为：绑定器测"三层数据源到 record 值"的映射，不测反射内部机制。
- **单元接缝** = ConfigBinder（三层数据源 → record 值的映射矩阵：五种类型 × 三层 × 缺失回退默认/非法值 warn 回退/List 数组与逗号串）。
- **集成接缝** = `AppConfig.load()`（优先级链端到端；经注入路径用临时 config.json 文件覆盖加载路径，补上真实测试）。
- AppConfigTest 语义保持：默认值断言经节访问器继续全部通过；instanceof 断言迁移到"节 record 实现对应接口"。
- 组件 Config 接口零新增接缝（消费者测试零改动）。
- 参考既有：AppConfigTest 的默认值断言风格、EvaluationPipelineTest 的构造注入风格。
- 端到端冒烟：应用启动（DEGRADED 路径）+ llm 配置生效。

## Out of Scope

- 未知 json 键的 warn（保持静默忽略）
- config.example.json 的机器生成（手工核对一次）
- 新配置类型支持（Map、嵌套节 record）——需要时再扩展绑定器类型转换器
- ChunkingConfig 接口独立化或任何 Config 接口签名调整
- 配置热重载 / 运行时变更

## Further Notes

- 行为变化清单：无破坏性变化。三项修正均为放宽（逗号串）、加日志（warn）、测试口（路径注入）。
- 规模预期：AppConfig 643 行 → 绑定器约 150 行 + 11 个节 record（每个 15~40 行）+ 容器约 60 行。
