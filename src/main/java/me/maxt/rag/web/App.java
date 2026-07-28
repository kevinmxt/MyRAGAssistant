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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.Map;

/**
 * 本地知识库智能问答 Web 应用入口。
 *
 * <p>启动流程：加载配置 → 初始化嵌入存储/文档服务/RAG 服务 → 自动摄入默认文档目录 → 启动 Javalin HTTP 服务器。
 * 提供 REST API 和静态前端页面。</p>
 *
 * @author maxt
 * @since 1.0
 */
public class App {

    private static final Logger log = LoggerFactory.getLogger(App.class);

    /**
     * 应用主入口。
     *
     * @param args 命令行参数（暂未使用，所有配置通过 config.json 和环境变量设置）
     */
    public static void main(String[] args) {
        // Load configuration
        AppConfig config = AppConfig.load();
        log.info("Starting RAG Web Application on port {}", config.getPort());

        // Initialize shared dependencies
        EmbeddingModel embeddingModel = new BgeSmallEnV15QuantizedEmbeddingModel();
        ChatModel chatModel = OpenAiChatModel.builder()
                .baseUrl(config.getBaseUrl())
                .apiKey(config.getApiKey())
                .modelName(config.getModelName())
                .temperature(config.getTemperature())
                .maxTokens(config.getMaxTokens())
                .timeout(Duration.ofSeconds(config.getTimeoutSeconds()))
                .build();

        // Initialize services
        EmbeddingStoreManager storeManager = new EmbeddingStoreManager(config.getStoreFilePath());
        RAGService ragService = new RAGService(config, storeManager, embeddingModel, chatModel);
        DocumentService documentService = new DocumentService(
                storeManager, embeddingModel, config.getChunkSize(), config.getChunkOverlap(),
                config.getSupportedFileExtensions());

        // Initialize controllers
        ChatController chatController = new ChatController(ragService);
        DocumentController documentController = new DocumentController(documentService, storeManager);

        // Check if default document directory exists and auto-ingest
        java.io.File defaultDocDir = new java.io.File(config.getDocumentDir());
        if (defaultDocDir.exists() && defaultDocDir.isDirectory() && storeManager.getEntryCount() == 0) {
            log.info("Auto-ingesting documents from default directory: {}", config.getDocumentDir());
            documentService.ingestDirectory(config.getDocumentDir());
        }

        // Start Javalin
        Javalin app = Javalin.create(jc -> {
                    jc.staticFiles.add(staticFileConfig -> {
                        staticFileConfig.directory = "webapp";
                        staticFileConfig.location = Location.CLASSPATH;
                    });
                })
                .start(config.getPort());

        // Register API routes
        app.get("/api/health", ctx -> ctx.json(Map.of("status", "ok")));

        app.post("/api/chat", chatController::handleChat);

        app.post("/api/ingest", documentController::handleIngest);

        app.get("/api/documents", documentController::handleListDocuments);

        app.post("/api/browse", documentController::handleBrowse);

        // Global exception handler
        app.exception(Exception.class, (e, ctx) -> {
            log.error("Unhandled exception", e);
            ctx.status(500).json(Map.of("error", "Internal server error: " + e.getMessage()));
        });

        // Graceful shutdown hook
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            log.info("Shutting down...");
            app.stop();
        }));

        log.info("Application started at http://localhost:{}", config.getPort());
    }
}
