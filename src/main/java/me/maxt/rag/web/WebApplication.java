package me.maxt.rag.web;

import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.embedding.onnx.bgesmallzhv15q.BgeSmallZhV15QuantizedEmbeddingModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import io.javalin.Javalin;
import io.javalin.http.staticfiles.Location;
import me.maxt.rag.web.config.AppConfig;
import me.maxt.rag.web.controller.ChatController;
import me.maxt.rag.web.controller.DocumentController;
import me.maxt.rag.web.service.DocumentService;
import me.maxt.rag.web.service.EmbeddingStoreManager;
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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.io.File;
import java.time.Duration;
import java.util.Map;

/**
 * Web 应用组装工厂，负责创建共享依赖、实例化 service 和 controller、配置路由。
 * App.main() 只保留薄胶水层。
 */
public class WebApplication {

    private static final Logger log = LoggerFactory.getLogger(WebApplication.class);

    private final AppConfig config;
    private final EmbeddingModel embeddingModel;
    private final ChatModel chatModel;
    private final EmbeddingStoreManager storeManager;
    private final RAGService ragService;
    private final DocumentService documentService;
    private final ChatController chatController;
    private final DocumentController documentController;

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

        // 服务层
        this.storeManager = new EmbeddingStoreManager(config.getStoreFilePath());

        // 检测向量维度不兼容：旧 EN 模型 384 → 新 ZH 模型 512
        // 如果 store 中有旧数据且维度不匹配，自动清空
        if (storeManager.getEntryCount() > 0) {
            int newDim = embeddingModel.embed("test").content().dimension();
            EmbeddingStoreManager.StoredEntry firstEntry = storeManager.listDocuments().get(0);
            int oldDim = firstEntry.getEmbedding().length;
            if (oldDim != newDim) {
                log.warn("Vector dimension mismatch: old={}, new={}. Clearing store...", oldDim, newDim);
                storeManager.clear();
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

        this.ragService = new RAGService(config, storeManager, embeddingModel, chatModel,
                enhancementRouter, config);

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

        app.exception(Exception.class, (e, ctx) -> {
            org.slf4j.LoggerFactory.getLogger(WebApplication.class)
                    .error("Unhandled exception", e);
            ctx.status(500).json(Map.of("error", "Internal server error: " + e.getMessage()));
        });

        return app;
    }

    /** 启动后自动摄入默认文档目录（如目录存在且 store 为空）。 */
    public void autoIngestIfNeeded() {
        File defaultDocDir = new File(config.getDocumentDir());
        if (defaultDocDir.exists() && defaultDocDir.isDirectory() && storeManager.getEntryCount() == 0) {
            documentService.ingestDirectory(config.getDocumentDir());
        }
    }

    public AppConfig getConfig() { return config; }
    public EmbeddingStoreManager getStoreManager() { return storeManager; }
    public RAGService getRagService() { return ragService; }
    public DocumentService getDocumentService() { return documentService; }
}
