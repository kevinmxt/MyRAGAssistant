# ModelRepository 模型分发深化 — 实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 把困在 CrossEncoderReranker 里的 HTTP 下载基建（镜像回退、302、权重清单）抽成模型仓库深模块 `ModelRepository`，让精排模型与 LightRAG 嵌入模型两个消费者共用，并接上环境检测的一键安装闭环。

**Architecture:** `ModelRepository`（`service.model` 新包）拥有模型制品的本地存在性：清单、镜像回退链、原子落盘、状态记账。`ensurePresent(artifact)` 同步幂等（齐全跳过、缺失补下、失败抛 `ModelDownloadException`）；`state(key)` 供环境检测查询真实状态。仓库不自管线程——消费者在组装根起 daemon thread。生产实现 `HttpModelRepository` 接受可注入的镜像基址列表（测试用本地 HttpServer，不碰外网）。

**Tech Stack:** Java 21、JUnit 5、AssertJ、`com.sun.net.httpserver.HttpServer`（本地文件服务测试）、`java.net.http.HttpClient`（自动重定向）。

**Spec:** 2026-09-02 grilling 决议（本计划 Global Constraints 即决议全录）。术语见 `CONTEXT.md`（模型仓库），推迟项见 `docs/adr/0002-model-download-deferred.md`。来源：架构评审候选 2（`docs/reviews/architecture-review-20260820.html`）。

## Global Constraints

- 接口不设 progress 百分比参数；仅允许 `ensurePresent(artifact, Consumer<String> log)` 重载（逐文件日志行，非进度）——ADR-0002
- 不做 Range 断点续传——ADR-0002
- `.part` 临时文件 + 原子 rename：半截文件永远不能被当成已下载；Content-Length >0 时校验大小
- 镜像回退链归仓库（默认：用户镜像 → https://hf-mirror.com → https://huggingface.co），通过构造器注入基址列表以便测试
- 配置全局化：新增 `model.downloadMirror` / `model.autoDownload`（env：`RAG_MODEL_MIRROR` / `RAG_MODEL_AUTO_DOWNLOAD`）；**直接删** `rerank.autoDownload` / `rerank.downloadMirror` 旧键，不留双轨
- 仓库同步、无线程池；同 key 并发 `ensurePresent` 用锁对象表互斥，进入后复查状态
- CrossEncoderReranker 构造器不再起下载线程；加载触发权在组装根（`loadIfPresent()` 幂等）
- bge-small-zh-v1.5 文件清单以 HF 仓库实际为准，实现 Task 5 时核对
- 术语：模型仓库（ModelRepository），见 `CONTEXT.md`；commit message 用中文，格式如 `新增: xxx` / `重构: xxx`
- 每个 Task 结束时 `mvn test -q` 全绿（evaluation profile 与 IT 除外）

## 文件结构总览

| 文件 | 动作 | 职责 |
|------|------|------|
| `src/main/java/me/maxt/rag/web/service/model/ModelArtifact.java` | 新建 | record：key、repo、文件清单（本地名→仓库路径）、目标目录 |
| `src/main/java/me/maxt/rag/web/service/model/DownloadState.java` | 新建 | record：Status（MISSING/DOWNLOADING/PRESENT/FAILED）+ detail |
| `src/main/java/me/maxt/rag/web/service/model/ModelDownloadException.java` | 新建 | RuntimeException，message 列出失败文件 |
| `src/main/java/me/maxt/rag/web/service/model/ModelRepository.java` | 新建 | 接口：ensurePresent ×2 重载 + state |
| `src/main/java/me/maxt/rag/web/service/model/HttpModelRepository.java` | 新建 | 生产实现：HttpClient、镜像回退、.part+rename、校验、状态记账 |
| `src/main/java/me/maxt/rag/web/service/vector/rerank/CrossEncoderReranker.java` | 修改 | 删下载块与线程，加 `loadIfPresent()` |
| `src/main/java/me/maxt/rag/web/config/ModelConfig.java` | 新建 | 全局下载配置接口（2 个 getter） |
| `src/main/java/me/maxt/rag/web/config/AppConfig.java` | 修改 | 增 2 键删 2 键，实现 ModelConfig |
| `src/main/java/me/maxt/rag/web/config/RerankConfig.java` | 修改 | 删 isRerankAutoDownload/getRerankDownloadMirror |
| `src/main/java/me/maxt/rag/web/service/environment/ModelFileChecker.java` | 修改 | check() 委托 state()；canAutoInstall=true 委托 ensurePresent |
| `src/main/java/me/maxt/rag/web/WebApplication.java` | 修改 | 组装 repo + 两个 artifact；下载线程；lightrag-init 前置 ensurePresent |
| `src/test/java/me/maxt/rag/web/service/model/HttpModelRepositoryTest.java` | 新建 | 本地 HttpServer 全链路测试 |
| `src/test/java/me/maxt/rag/web/service/environment/ModelFileCheckerTest.java` | 新建 | fake repo 状态映射 + autoInstall 委托 |
| `src/test/java/me/maxt/rag/web/service/vector/rerank/CrossEncoderRerankerTest.java` | 修改 | stub config 随接口瘦身；补 loadIfPresent 语义 |
| `config.example.json` / `README.md` / `CLAUDE.md` / `docs/modules.md` | 修改 | 配置键与关键入口同步 |

---

### Task 1: 领域类型 + ModelRepository 接口 + HttpModelRepository

**Files:**
- Create: `service/model/ModelArtifact.java`、`DownloadState.java`、`ModelDownloadException.java`、`ModelRepository.java`、`HttpModelRepository.java`
- Test: `src/test/java/me/maxt/rag/web/service/model/HttpModelRepositoryTest.java`

**Interfaces:**
- Produces: `ModelRepository { void ensurePresent(ModelArtifact a); void ensurePresent(ModelArtifact a, Consumer<String> log); DownloadState state(String key); }`；`record ModelArtifact(String key, String repo, Map<String,String> files, Path targetDir)`；`record DownloadState(Status status, String detail)` + `enum Status { MISSING, DOWNLOADING, PRESENT, FAILED }`。Task 4/5 依赖。

- [ ] **Step 1: 写失败测试**（本地 `HttpServer`，`@TempDir` 目标目录）

```java
class HttpModelRepositoryTest {
    // 工具：startFileServer(8080内容映射或404) 返回基址 "http://localhost:<port>"
    // 镜像列表 = [serverA(404), serverB(200)]，验证回退

    @Test void shouldReportMissingWhenFilesAbsent()          // state(key).status() == MISSING
    @Test void shouldDownloadWithMirrorFallback()            // A 404 → B 200，文件落盘、PRESENT
    @Test void shouldSkipNetworkWhenAllFilesPresent()        // 预放文件 → server 请求计数 == 0
    @Test void shouldFailWhenAllMirrorsUnavailable()         // 全 404 → ModelDownloadException + FAILED + 目标无残留完整文件
    @Test void shouldFailOnSizeMismatch()                    // server 谎报 Content-Length → 失败、不 rename
    @Test void shouldStreamLogLinesToConsumer()              // log consumer 收到含文件名的行
    @Test void shouldNotThrowWhenReensureAfterPresent()      // 二次 ensurePresent 直接返回
}
```

- [ ] **Step 2: 实现类型与 HttpModelRepository**
  - 构造器 `HttpModelRepository(List<String> mirrorBaseUrls)`；URL 拼接：`baseUrl + "/" + repo + "/resolve/main/" + repoPath`
  - `HttpClient.newBuilder().followRedirects(Redirect.NORMAL).connectTimeout(10s)`；单文件读超时 300s（Request timeout）
  - 下载流程：目标存在且（跳过校验）→ 下一个文件；否则 `GET → .part → Files.copy → size 校验 → Files.move(ATOMIC_MOVE, REPLACE_EXISTING)`
  - 状态记账：`ConcurrentHashMap<String, DownloadState>`；入口置 DOWNLOADING（当前文件名），成功置 PRESENT（摘要），失败置 FAILED（原因）；`state(key)` 无记录时按文件存在性现算 MISSING/PRESENT
  - 同 key 互斥：`ConcurrentHashMap<String, Object> locks`，`computeIfAbsent` 锁对象 + synchronized；进入后复查 state，PRESENT 即返回
  - 无 log 参数版本内部传 `line -> log.info(...)`（slf4j）
- [ ] **Step 3: 全部测试红→绿，`mvn test -q` 全绿，commit `新增: 模型仓库 HttpModelRepository——镜像回退、原子落盘、状态记账`**

---

### Task 2: CrossEncoderReranker 去下载化

**Files:**
- Modify: `service/vector/rerank/CrossEncoderReranker.java`
- Modify: `src/test/java/me/maxt/rag/web/service/vector/rerank/CrossEncoderRerankerTest.java`

**Interfaces:**
- Consumes: 现有 `RerankConfig`（Task 3 才删两方法，本任务先不读它们）
- Produces: `CrossEncoderReranker.loadIfPresent()`（幂等：模型文件存在则加载置 available，否则安静返回 false）

- [ ] **Step 1: 测试先行**
  - 现有 3 测语义保持（模型缺失 → 构造即降级、不可用透传、空候选）——stub config 不需要改（本任务不动接口）
  - 新增：`shouldNotStartDownloadThreadOnConstruct()`（构造缺失模型后不产生下载副作用——行为等价断言：构造后 `isAvailable()==false` 且无异常即可，线程数不断言）；`shouldLoadWhenFilesAppear()`（`loadIfPresent()` 在无模型目录返回 false；有 tokenizer/model.onnx 的目录返回 true——`@TempDir` 放一个微型合法 onnx？加载真模型成本高，允许只测 false 分支 + true 分支标 @Disabled 或用最小 onnx fixture，实现时裁量）
- [ ] **Step 2: 改造**
  - 删：`MODEL_FILES`、`MODEL_REPO`、`downloadModel()`、构造器中的后台线程分支、HttpURLConnection 相关 import
  - 构造器：onnx 存在 → `loadModel`；不存在 → warn 降级（不读 autoDownload）
  - 新增 `public synchronized boolean loadIfPresent()`：已 available → true；onnx 存在 → loadModel → available；否则 false
- [ ] **Step 3: `mvn test -q` 全绿，commit `重构: 精排器剥离下载职责，加载触发权交给组装根`**

---

### Task 3: 配置全局化——ModelConfig + AppConfig 增删键

**Files:**
- Create: `config/ModelConfig.java`
- Modify: `config/AppConfig.java`、`config/RerankConfig.java`、`config.example.json`、`README.md`

**Interfaces:**
- Produces: `ModelConfig { String getDownloadMirror(); boolean isAutoDownload(); }`，AppConfig 实现之。Task 5 组装根依赖。

- [ ] **Step 1: AppConfig 增删**
  - 增：`modelAutoDownload`（默认 true，file `model.autoDownload`，env `RAG_MODEL_AUTO_DOWNLOAD`）、`modelDownloadMirror`（默认 `https://hf-mirror.com`，file `model.downloadMirror`，env `RAG_MODEL_MIRROR`）——各 5 处编辑（字段/默认/file/env/getter）
  - 删：`rerankAutoDownload`、`rerankDownloadMirror` 全部 5 处（引用者已在 Task 2 消失）
  - `RerankConfig` 删两方法
- [ ] **Step 2: 文档同步**：`config.example.json`（rerank 节删 2 键、新增 model 节）、README 配置表同步
- [ ] **Step 3: `mvn test -q` 全绿（AppConfigTest 未引用被删键，预期无破坏），commit `重构: 模型下载配置全局化，删 rerank 专属旧键`**

---

### Task 4: ModelFileChecker 委托状态 + 一键安装

**Files:**
- Modify: `service/environment/ModelFileChecker.java`
- Test: `src/test/java/me/maxt/rag/web/service/environment/ModelFileCheckerTest.java`（新建）

**Interfaces:**
- Consumes: Task 1 的 `ModelRepository`
- Produces: `ModelFileChecker(ModelRepository repo, ModelArtifact... artifacts)`——check() 逐 artifact 查 state；`canAutoInstall()==true`；`autoInstall(log)` 对非 PRESENT 的 artifact 逐个 `ensurePresent(a, log)`

- [ ] **Step 1: 写失败测试**（fake ModelRepository：可编程 state + 记录 ensurePresent 调用）
  - `shouldReportOkWhenAllPresent`
  - `shouldReportDownloadingWithDetail`（DOWNLOADING → MISSING 状态文案带"下载中"）
  - `shouldReportFailedWithReason`
  - `shouldReportMissingAndInstallable`（MISSING → 文案含可安装提示）
  - `shouldAutoInstallOnlyMissingArtifacts`（PRESENT 的不再 ensurePresent；log 行透传）
  - `shouldReturnFalseWhenDownloadFails`（fake 抛 ModelDownloadException → false）
- [ ] **Step 2: 改造 ModelFileChecker**：删两个路径字符串字段与 File 存在性检查，换 state() 映射；autoInstall 委托 ensurePresent（log 直通 install-log SSE）
- [ ] **Step 3: `mvn test -q` 全绿，commit `新增: 模型检测接模型仓库真实状态，接入一键安装`**

---

### Task 5: 组装根接线 + 文档收尾

**Files:**
- Modify: `WebApplication.java`
- Modify: `CLAUDE.md`、`docs/modules.md`、`docs/reviews/architecture-backlog.md`

- [ ] **Step 1: 组装**
  - `ModelRepository modelRepo = new HttpModelRepository(List.of(config.getDownloadMirror(), "https://hf-mirror.com", "https://huggingface.co"))`
  - 两个 artifact：reranker（repo `onnx-community/bge-reranker-v2-m3-ONNX`，三件套清单迁自旧 MODEL_FILES，目录 = `config.getRerankModelPath()`）；embedding（repo `BAAI/bge-small-zh-v1.5`，清单**核对 HF 仓库实际文件**后定，目录 = `config.getLightRagEmbeddingModelPath()`）
  - reranker 线程：`config.isAutoDownload()` 时起 daemon thread `model-download-reranker`：`ensurePresent(rerankArt)` → `reranker.loadIfPresent()`；false 时直接 `loadIfPresent()`（现状 inline 加载等价）
  - lightrag-init 线程开头：`isAutoDownload()` 时 `ensurePresent(embeddingArt)`（失败 catch 降级，LightRAG init 继续按现状失败降级）
  - `ModelFileChecker` 构造换新签名
- [ ] **Step 2: 文档**：CLAUDE.md 关键入口加 `ModelRepository` 行；modules.md 加 service.model 节；backlog 候选 2 状态改 ✅ 附提交号
- [ ] **Step 3: `mvn test -q` 全绿 + `mvn compile` 复核，commit `重构: 组装根接线模型仓库，两消费者与一键安装闭环`**

---

### Task 6: 端到端验证（手动，需网络/Docker）

- [x] `mvn clean package` → 起应用（模型目录清空）：日志按序出现 reranker 下载 → 加载；env 页 model-files 显示"下载中/完成"；下载中重复点安装按钮被防重（2026-09-06 实测：冷启动两线程按设计启动；本机网络瞬断致启动下载失败→一键安装恢复→重启加载，全路径走通；409 防重、INSTALLING 状态、install-log SSE 均实证）
- [x] 精排链路冒烟：上传文档 → 提问 → 响应含精排（或降级路径日志干净）（实测：降级期透明无噪音；重启激活后 top source 命中新摄取文档）
- [x] LightRAG 冒烟：kg 初始化成功（嵌入模型目录由仓库补齐）（部分：嵌入 10 文件含 1_Pooling 由仓库补齐 ✅；kg init 被 Python 3.14 无 numpy/lightrag 轮子阻挡，属环境限制，仓库侧职责已完成）
- [x] backlog 候选 2 终态更新；如行为与计划偏差，回填本计划备注（已回填，详见下）

**Task 6 执行备注（偏差回填，2026-09-06）**

1. **一键安装不触发精排加载**：计划缝隙——Task 4 闭环"安装补文件"，Task 5 的 loadIfPresent 只挂在启动下载线程；装完需重启才生效。已登记 backlog 跟进项。
2. **启动下载失败无重试**：一次性线程 + 镜像链含重复项，网络瞬断即双双永久降级（本机实测）；恢复靠一键安装/重启（均实测可用）。已并入 backlog"读超时/watchdog"跟踪条目。
3. 完整验证记录与遗留物清单见 `docs/reviews/model-repository-final-review-20260903.md`。
