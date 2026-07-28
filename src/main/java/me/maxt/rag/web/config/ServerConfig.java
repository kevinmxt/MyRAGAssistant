package me.maxt.rag.web.config;

/**
 * 服务器和存储相关的配置接口。
 */
public interface ServerConfig {
    int getPort();
    String getStoreFilePath();
}
