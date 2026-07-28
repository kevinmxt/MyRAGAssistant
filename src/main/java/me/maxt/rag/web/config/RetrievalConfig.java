package me.maxt.rag.web.config;

/**
 * 检索相关的配置接口。
 */
public interface RetrievalConfig {
    int getMaxResults();
    double getMinScore();
    int getMemorySize();
}
