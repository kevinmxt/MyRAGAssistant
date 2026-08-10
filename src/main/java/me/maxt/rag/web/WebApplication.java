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

import java.io.File;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Web 应用组装工厂，负责创建共享依赖、实例化 service 和 controller、配置路由。
 * App.main() 只保留薄胶水层。
 */
public class WebApplication {

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

    public WebApplication(AppConfig config) {
        this.config = config;

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

        // Milvus 向量存储（STRONG 一致性保证写入后立即可查）
        MilvusEmbeddingStore milvusStore = MilvusEmbeddingStore.builder()
                .host(config.getMilvusHost())
                .port(config.getMilvusPort())
                .collectionName(config.getMilvusCollectionName())
                .dimension(config.getMilvusDimension())
                .consistencyLevel(ConsistencyLevelEnum.STRONG)
                .build();

        this.storeManager = new EmbeddingStoreManager(milvusStore);

        // Milvus v2 原生客户端（用于索引重建、稀疏检索、知识图谱等）
        MilvusClientV2 milvusClientV2 = new MilvusClientV2(ConnectConfig.builder()
                .uri("http://" + config.getMilvusHost() + ":" + config.getMilvusPort())
                .build());

        // 从 Milvus 重建文档索引（重启后恢复）
        storeManager.rebuildIndexFromMilvus(milvusClientV2, config.getMilvusCollectionName());

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

        // 多路召回组件组装（条件启用，默认 disabled 时全部为 null，行为与原来完全一致）
        MultiRecallRouter multiRecallRouter = null;
        KnowledgeGraphService kgService = null;
        KnowledgeGraphController kgController = null;

        if (config.isMultiRecallEnabled()) {
            // 召回策略注册表（复用上面创建的 milvusClientV2）
            Map<String, RecallStrategy> registry = new LinkedHashMap<>();
            registry.put("dense", new DenseRecallStrategy(storeManager, embeddingModel));
            registry.put("sparse", new SparseRecallStrategy(
                    milvusClientV2, config.getMilvusCollectionName()));

            // LightRAG 知识图谱（JPype 桥接，复用 LLM 配置）
            LightRagBridge lightRagBridge = new LightRagBridge(
                    config.getLightRagPythonPath(), config.getLightRagWorkingDir(),
                    config.getLightRagEmbeddingModelPath(), config.getLightRagQueryMode(),
                    config.getApiKey(), config.getBaseUrl(), config.getModelName());
            lightRagBridge.init();
            kgService = new KnowledgeGraphService(config, storeManager, milvusClientV2, lightRagBridge);
            registry.put("graph", new GraphRecallStrategy(kgService, lightRagBridge,
                    config.getLightRagQueryMode()));

            multiRecallRouter = new MultiRecallRouter(config, registry);
            kgController = new KnowledgeGraphController(kgService);
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

        app.get("/api/health", ctx -> ctx.json(Map.of("status", "ok")));
        app.post("/api/chat", chatController::handleChat);
        app.post("/api/ingest", documentController::handleIngest);
        app.get("/api/documents", documentController::handleListDocuments);
        app.post("/api/browse", documentController::handleBrowse);

        // 知识图谱路由（仅多路召回启用时注册）
        if (kgController != null) {
            app.post("/api/kg/build", kgController::handleBuildForDirectory);
            app.post("/api/kg/build/{docId}", kgController::handleBuildForDocument);
            app.get("/api/kg/status", kgController::handleGetStatus);
        }

        app.exception(Exception.class, (e, ctx) -> {
            org.slf4j.LoggerFactory.getLogger(WebApplication.class)
                    .error("Unhandled exception", e);
            ctx.status(500).json(Map.of("error", "Internal server error: " + e.getMessage()));
        });

        return app;
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
}
