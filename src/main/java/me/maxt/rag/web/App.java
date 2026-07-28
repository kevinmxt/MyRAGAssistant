package me.maxt.rag.web;

import io.javalin.Javalin;
import me.maxt.rag.web.config.AppConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 本地知识库智能问答 Web 应用入口。
 *
 * <p>启动流程：加载配置 → {@link WebApplication} 组装依赖和服务 → 自动摄入 → 启动 HTTP 服务器。</p>
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
        AppConfig config = AppConfig.load();
        log.info("Starting RAG Web Application on port {}", config.getPort());

        WebApplication webApp = new WebApplication(config);
        webApp.autoIngestIfNeeded();

        Javalin server = webApp.createJavalin().start(config.getPort());

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            log.info("Shutting down...");
            server.stop();
        }));

        log.info("Application started at http://localhost:{}", config.getPort());
    }
}
