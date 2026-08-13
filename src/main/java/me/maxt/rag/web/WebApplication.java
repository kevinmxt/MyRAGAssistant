package me.maxt.rag.web;

import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.embedding.onnx.bgesmallzhv15q.BgeSmallZhV15QuantizedEmbeddingModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.store.embedding.milvus.MilvusEmbeddingStore;
import io.javalin.Javalin;
import io.javalin.http.staticfiles.Location;
import io.milvus.common.clientenum.ConsistencyLevelEnum;
import io.milvus.v2.client.ConnectConfig;
import io.milvus.v2.client.MilvusClientV2;
import me.maxt.rag.web.config.AppConfig;
import me.maxt.rag.web.controller.ChatController;
import me.maxt.rag.web.controller.DocumentController;
import me.maxt.rag.web.controller.EnvironmentController;
import me.maxt.rag.web.controller.KnowledgeGraphController;
import me.maxt.rag.web.service.DocumentService;
import me.maxt.rag.web.service.EmbeddingStoreManager;
import me.maxt.rag.web.service.KnowledgeGraphService;
import me.maxt.rag.web.service.RAGService;
import me.maxt.rag.web.service.chunking.ChunkingPipeline;
import me.maxt.rag.web.service.chunking.analyzer.StructureAnalyzer;
import me.maxt.rag.web.service.chunking.classifier.SplitClassifier;
import me.maxt.rag.web.service.chunking.converter.MarkdownConverter;
import me.maxt.rag.web.service.chunking.evaluator.ChunkEvaluator;
import me.maxt.rag.web.service.chunking.splitter.AgentRefiner;
import me.maxt.rag.web.service.chunking.splitter.SemanticSplitter;
import me.maxt.rag.web.service.chunking.splitter.StructureSplitter;
import me.maxt.rag.web.service.vector.ContextualEnricher;
import me.maxt.rag.web.service.vector.HyDEGenerator;
import me.maxt.rag.web.service.vector.QueryEnhancementRouter;
import me.maxt.rag.web.service.vector.QueryRewriter;
import me.maxt.rag.web.service.vector.recall.DenseRecallStrategy;
import me.maxt.rag.web.service.vector.recall.GraphRecallStrategy;
import me.maxt.rag.web.service.vector.recall.LightRagBridge;
import me.maxt.rag.web.service.vector.recall.MultiRecallRouter;
import me.maxt.rag.web.service.vector.recall.RecallStrategy;
import me.maxt.rag.web.service.vector.recall.SparseRecallStrategy;
import me.maxt.rag.web.service.vector.rerank.CrossEncoderReranker;
import me.maxt.rag.web.service.vector.rerank.Reranker;
import me.maxt.rag.web.service.environment.*;

import java.io.File;
import java.io.PrintWriter;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * Web 应用组装工厂，负责创建共享依赖、实例化 service 和 controller、配置路由。
 * App.main() 只保留薄胶水层。
 */
public class WebApplication {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(WebApplication.class);

    private final AppConfig config;
    private final EmbeddingModel embeddingModel;
    private final ChatModel chatModel;
    private final EmbeddingStoreManager storeManager;
    private final RAGService ragService;
    private final DocumentService documentService;
    private final ChatController chatController;
    private final DocumentController documentController;

    // 多路召回组件（仅 config.isMultiRecallEnabled() 时非 null）
    private final MultiRecallRouter multiRecallRouter;
    private final KnowledgeGraphService kgService;
    private final KnowledgeGraphController kgController;

    private final Reranker reranker;

    // 环境检测与 SSE 连接管理
    private final EnvironmentChecker environmentChecker;
    private final EnvironmentController environmentController;
    private final Map<String, PrintWriter> sseClients = new ConcurrentHashMap<>();

    // Milvus 原生客户端（重连后更新；null 表示当前不可用）
    private volatile MilvusClientV2 milvusClientV2;

    public WebApplication(AppConfig config) {
        this.config = config;

        // 环境检测（非阻塞后台启动，SSE 推送结果）
        List<DependencyChecker> checkers = List.of(
                new PythonChecker(config, config.getLightRagPythonPath()),
                new PipPackageChecker(config, config.getLightRagPythonPath()),
                new MilvusChecker(config, config.getMilvusHost(), config.getMilvusPort()),
                new PandocChecker(config),
                new TesseractChecker(config),
                new ModelFileChecker(config, config.getRerankModelPath(), config.getLightRagEmbeddingModelPath()));
        this.environmentChecker = new EnvironmentChecker(config, checkers);
        this.environmentController = new EnvironmentController(environmentChecker);
        environmentChecker.run();

        // 共享依赖
        this.embeddingModel = new BgeSmallZhV15QuantizedEmbeddingModel();
        this.chatModel = OpenAiChatModel.builder()
                .baseUrl(config.getBaseUrl())
                .apiKey(config.getApiKey())
                .modelName(config.getModelName())
                .temperature(config.getTemperature())
                .maxTokens(config.getMaxTokens())
                .timeout(Duration.ofSeconds(config.getTimeoutSeconds()))
                .build();

        // Milvus 向量存储（连接失败降级到内存存储，不阻塞启动）
        MilvusEmbeddingStore milvusStore;
        try {
            milvusStore = buildMilvusStore();
        } catch (Exception e) {
            log.warn("Milvus 不可用，降级到内存存储（数据不持久化）: {}", e.getMessage());
            milvusStore = null;
        }

        if (milvusStore == null) {
            this.storeManager = new EmbeddingStoreManager(new dev.langchain4j.store.embedding.inmemory.InMemoryEmbeddingStore<>());
        } else {
            this.storeManager = new EmbeddingStoreManager(milvusStore);
        }

        // Milvus v2 原生客户端（用于索引重建、稀疏检索、知识图谱等）
        // 连接失败不崩溃，降级处理
        if (milvusStore != null) {
            try {
                this.milvusClientV2 = buildMilvusClient();
                // 从 Milvus 重建文档索引（重启后恢复）
                storeManager.rebuildIndexFromMilvus(this.milvusClientV2, config.getMilvusCollectionName());
            } catch (Exception e) {
                log.warn("Milvus 连接失败，底层功能将降级: {}", e.getMessage());
                this.milvusClientV2 = null;
            }
        }

        // Chunking 管线
        MarkdownConverter markdownConverter = new MarkdownConverter();
        StructureAnalyzer structureAnalyzer = new StructureAnalyzer();
        SplitClassifier splitClassifier = new SplitClassifier();
        StructureSplitter structureSplitter = new StructureSplitter();
        SemanticSplitter semanticSplitter = new SemanticSplitter(embeddingModel, config.getSemanticThreshold());
        AgentRefiner agentRefiner = config.isAgentRefinerEnabled() ? new AgentRefiner(chatModel) : null;
        ChunkEvaluator chunkEvaluator = new ChunkEvaluator();

        ChunkingPipeline chunkingPipeline = new ChunkingPipeline(
                markdownConverter, structureAnalyzer, splitClassifier,
                structureSplitter, semanticSplitter, agentRefiner, chunkEvaluator);

        QueryRewriter queryRewriter = new QueryRewriter(chatModel, 100);
        HyDEGenerator hydeGenerator = new HyDEGenerator(chatModel, config.getHydeMaxTokens());
        QueryEnhancementRouter enhancementRouter = new QueryEnhancementRouter(
                queryRewriter, hydeGenerator, chatModel, config);

        // 多路召回组件组装（条件启用）
        MultiRecallRouter multiRecallRouter = null;

        // 知识图谱 — 独立于多路召回，始终初始化（非阻塞，失败降级不影响主流程）
        LightRagBridge lightRagBridge = new LightRagBridge(
                config.getLightRagPythonPath(), config.getLightRagWorkingDir(),
                config.getLightRagEmbeddingModelPath(), config.getLightRagQueryMode(),
                config.getApiKey(), config.getBaseUrl(), config.getModelName());
        Thread lightragInit = new Thread(() -> {
            lightRagBridge.init();
            log.info("LightRAG 后台初始化完成: initialized={}", lightRagBridge.isInitialized());
        }, "lightrag-init");
        lightragInit.setDaemon(true);
        lightragInit.start();
        KnowledgeGraphService kgService = new KnowledgeGraphService(config, storeManager, milvusClientV2, lightRagBridge);
        KnowledgeGraphController kgController = new KnowledgeGraphController(kgService);

        if (config.isMultiRecallEnabled()) {
            Map<String, RecallStrategy> registry = new LinkedHashMap<>();
            registry.put("dense", new DenseRecallStrategy(storeManager, embeddingModel));
            if (milvusClientV2 != null) {
                registry.put("sparse", new SparseRecallStrategy(
                        milvusClientV2, config.getMilvusCollectionName()));
            }
            registry.put("graph", new GraphRecallStrategy(kgService, lightRagBridge,
                    config.getLightRagQueryMode()));
            multiRecallRouter = new MultiRecallRouter(config, registry);
        }

        this.multiRecallRouter = multiRecallRouter;
        this.kgService = kgService;
        this.kgController = kgController;

        this.reranker = new CrossEncoderReranker(config);

        this.ragService = new RAGService(config, storeManager, embeddingModel, chatModel,
                enhancementRouter, config, multiRecallRouter, config, reranker);

        // ContextualEnricher：嵌入前用 LLM 为每个 chunk 添加上下文
        ContextualEnricher contextualEnricher = new ContextualEnricher();

        this.documentService = new DocumentService(
                storeManager, embeddingModel,
                config.getChunkSize(), config.getChunkOverlap(),
                config.getSupportedFileExtensions(),
                chunkingPipeline, contextualEnricher);

        // 控制器
        this.chatController = new ChatController(ragService);
        this.documentController = new DocumentController(documentService, storeManager);
    }

    /**
     * 创建并配置 Javalin 实例（不启动）。
     * @return 已配置路由和静态文件但未启动的 Javalin 实例
     */
    public Javalin createJavalin() {
        Javalin app = Javalin.create(jc -> {
            jc.staticFiles.add(staticFileConfig -> {
                staticFileConfig.directory = "webapp";
                staticFileConfig.location = Location.CLASSPATH;
            });
        });

        app.get("/api/health", ctx -> {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("status", "ok");
            body.put("environment", environmentChecker.getSummary());
            ctx.json(body);
        });

        // 环境管理路由
        app.get("/api/env/status", environmentController::handleStatus);
        app.post("/api/env/check", environmentController::handleCheck);
        app.post("/api/env/install", environmentController::handleInstall);
        app.post("/api/env/reconnect-milvus", ctx -> {
            String error = reconnectMilvus();
            if (error == null) {
                ctx.json(Map.of("success", true, "message", "已切换到 Milvus"));
            } else {
                ctx.status(503).json(Map.of("success", false, "error", error));
            }
        });

        // SSE 端点 — 推送环境状态变更和安装进度
        app.get("/api/env/stream", ctx -> {
            ctx.contentType("text/event-stream; charset=utf-8");
            ctx.header("Cache-Control", "no-cache");
            ctx.header("Connection", "keep-alive");

            PrintWriter writer = new PrintWriter(ctx.res().getOutputStream(), true);
            String clientId = java.util.UUID.randomUUID().toString();
            sseClients.put(clientId, writer);

            @SuppressWarnings("unchecked")
            Consumer<String>[] holder = new Consumer[1];
            holder[0] = formatted -> {
                try {
                    writer.print(formatted);
                    writer.flush();
                } catch (Exception e) {
                    sseClients.remove(clientId);
                    environmentChecker.removeSseListener(holder[0]);
                }
            };
            environmentChecker.addSseListener(holder[0]);

            // 心跳保持连接
            try {
                while (!writer.checkError()) {
                    writer.print(": heartbeat\n\n");
                    writer.flush();
                    Thread.sleep(15000);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                sseClients.remove(clientId);
                environmentChecker.removeSseListener(holder[0]);
                writer.close();
            }
        });

        app.post("/api/chat", chatController::handleChat);
        app.post("/api/ingest", documentController::handleIngest);
        app.get("/api/documents", documentController::handleListDocuments);
        app.post("/api/browse", documentController::handleBrowse);

        // 知识图谱路由
        app.post("/api/kg/build", kgController::handleBuildForDirectory);
        app.post("/api/kg/build/{docId}", kgController::handleBuildForDocument);
        app.get("/api/kg/status", kgController::handleGetStatus);

        app.exception(Exception.class, (e, ctx) -> {
            org.slf4j.LoggerFactory.getLogger(WebApplication.class)
                    .error("Unhandled exception", e);
            ctx.status(500).json(Map.of("error", "Internal server error: " + e.getMessage()));
        });

        return app;
    }

    private MilvusEmbeddingStore buildMilvusStore() {
        return MilvusEmbeddingStore.builder()
                .host(config.getMilvusHost())
                .port(config.getMilvusPort())
                .collectionName(config.getMilvusCollectionName())
                .dimension(config.getMilvusDimension())
                .consistencyLevel(ConsistencyLevelEnum.STRONG)
                .build();
    }

    private MilvusClientV2 buildMilvusClient() {
        return new MilvusClientV2(ConnectConfig.builder()
                .uri("http://" + config.getMilvusHost() + ":" + config.getMilvusPort())
                .build());
    }

    /**
     * 尝试重连 Milvus：先用 TCP 探针快速确认进程可达，再把底层存储从内存切换到 Milvus 并重建索引。
     * @return null 表示成功；否则返回错误描述
     */
    public synchronized String reconnectMilvus() {
        // TCP 探针快速确认可达性（与 MilvusChecker 一致的检测方式，2 秒超时）
        try (java.net.Socket socket = new java.net.Socket()) {
            socket.connect(new java.net.InetSocketAddress(config.getMilvusHost(), config.getMilvusPort()), 2000);
        } catch (Exception e) {
            return "Milvus 不可达 (" + config.getMilvusHost() + ":" + config.getMilvusPort() + ") → 请先 docker compose up -d";
        }

        try {
            MilvusEmbeddingStore milvusStore = buildMilvusStore();
            MilvusClientV2 client = buildMilvusClient();
            storeManager.swapStore(milvusStore);
            this.milvusClientV2 = client;
            storeManager.rebuildIndexFromMilvus(client, config.getMilvusCollectionName());
            log.info("Milvus 重连成功，向量存储已切换到 Milvus ({}:{})",
                    config.getMilvusHost(), config.getMilvusPort());
            // 触发环境重检（SSE 广播更新前端状态）
            Thread t = new Thread(environmentChecker::checkAll, "env-check-after-reconnect");
            t.setDaemon(true);
            t.start();
            return null;
        } catch (Exception e) {
            log.warn("Milvus 存储切换失败: {}", e.getMessage());
            return "Milvus 可达但存储切换失败: " + e.getMessage();
        }
    }

    /** 启动后自动摄入默认文档目录（如目录存在）。 */
    public void autoIngestIfNeeded() {
        File defaultDocDir = new File(config.getDocumentDir());
        if (defaultDocDir.exists() && defaultDocDir.isDirectory()) {
            documentService.ingestDirectory(config.getDocumentDir());
        }
    }

    public AppConfig getConfig() { return config; }
    public EmbeddingStoreManager getStoreManager() { return storeManager; }
    public RAGService getRagService() { return ragService; }
    public DocumentService getDocumentService() { return documentService; }
    public Reranker getReranker() { return reranker; }
    public EnvironmentChecker getEnvironmentChecker() { return environmentChecker; }
}
