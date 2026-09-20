package me.maxt.rag.web.config;

/**
 * 服务器配置节：含跨节键 store.filePath（向量存储文件路径，json 路径在 store 节下）。
 *
 * @author maxt
 * @since 1.0
 */
public record ServerSettings(
        @Key(json = "server.port", env = "RAG_SERVER_PORT", def = "8080") int getPort,
        @Key(json = "store.filePath", env = "RAG_STORE_PATH",
                def = "./data/embedding-store.json") String getStoreFilePath) implements ServerConfig {
}
