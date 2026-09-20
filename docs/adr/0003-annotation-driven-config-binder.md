# ADR-0003: 注解驱动配置绑定（@Key + 反射绑定器）

日期：2026-09-20
状态：已接受

## 背景

架构评审候选 4（`docs/reviews/architecture-backlog.md`）：AppConfig 643 行、49 个配置键，每加一键要改五处（字段声明、构造器默认值、`applyFileConfig` 一行、`applyEnvOverrides` 一行、getter），且 json 路径、环境变量名、字段名三套命名全靠手写对齐（如 `lightRagPythonPath` ↔ `multiRecall.lightrag.pythonPath` ↔ `RAG_LIGHTRAG_PYTHON`）。既有实现还有三处隐藏坑：`recallModes` 的 json 层只认数组（另两个 List 键数组和逗号串都认）；env 数值非法时静默回退默认值；`load()` 文件路径写死不可注入，file 加载路径无测试覆盖。

约束：12 个 Config 接口是消费者接缝，必须不动（方法签名零变化）；`App` 与 `WebApplication` 是仅有的两个依赖 AppConfig 具体类型的类。

## 决策

三个联动选型，共同目标"每键一处"：

1. **`@Key` 注解 + 反射通用绑定器（ConfigBinder）**：每个键在 record 构造参数上声明 json 点路径、env 名、字符串默认值，绑定器统一实现优先级链（默认 → config.json → env）与类型转换（类型从构造参数反射取得）。否决的替代：每节显式工厂（每键约三处，压缩不足）；Jackson databind + env 覆盖层（json 层零样板但 env 层仍手写，两套机制割裂）。
2. **节 record 组件用 getter 风格命名**（`record LlmSettings(@Key(...) String getApiKey, …) implements LlmConfig`）：record 访问器名随组件名走，组件名就叫 `getApiKey` 则访问器即接口方法，实现零桥接。代价是组件名长得像方法（第一眼会愣），换来 49 键省 49 个桥接样板；命名怪癖封闭在 config 包内。
3. **AppConfig 容器化**：保留类名与 `load()`（App 零改动），内部变 11 个 final record 字段 + `llm()/retrieval()/…` 节访问器，不再 implements 任何接口。WebApplication 构造签名不变，内部传参点改传节 record（组件收窄接口，编译器兜底）。否决的替代：保留 implements 12 接口的委托门面（每键多一行委托，巨类只砍一半）；彻底删除 AppConfig 让 App 自己组装（入口从 5 行胶水变 15 行）。

顺带修正三处既有坑：List 键 json 层统一兼容数组与逗号串；env 数值非法时打 warn 再回退默认；`load(Path)` 可注入补测试。

## 后果

加一键从五处降到一处（带 `@Key` 的 record 构造参数）；AppConfig 643 行消失，替换为绑定器（约 150 行，一次性成本）+ 11 个节 record + 约 60 行容器。反射构造 record 的成本在启动期一次性发生，可忽略。默认值以字符串形式存在于注解中，失去编译期类型检查——由 AppConfigTest 的全量默认值断言护栏兜底。未来若新增复杂类型（Map、嵌套节），绑定器需扩展类型转换器，注解声明形态不变。
