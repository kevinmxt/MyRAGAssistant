package me.maxt.rag.web;

import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.embedding.onnx.bgesmallenv15q.BgeSmallEnV15QuantizedEmbeddingModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import io.javalin.Javalin;
import io.javalin.http.staticfiles.Location;
import me.maxt.rag.web.config.AppConfig;
import me.maxt.rag.web.controller.ChatController;
import me.maxt.rag.web.controller.DocumentController;
import me.maxt.rag.web.service.DocumentService;
import me.maxt.rag.web.service.EmbeddingStoreManager;
import me.maxt.rag.web.service.RAGService;

import java.io.File;
import java.time.Duration;
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

    public WebApplication(AppConfig config) {
        this.config = config;

        // 共享依赖
        this.embeddingModel = new BgeSmallEnV15QuantizedEmbeddingModel();
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
        this.ragService = new RAGService(config, storeManager, embeddingModel, chatModel);
        this.documentService = new DocumentService(
                storeManager, embeddingModel,
                config.getChunkSize(), config.getChunkOverlap(),
                config.getSupportedFileExtensions());

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
