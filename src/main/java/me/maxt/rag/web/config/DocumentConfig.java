package me.maxt.rag.web.config;

import java.util.List;

/**
 * 文档处理相关的配置接口。
 */
public interface DocumentConfig {
    String getDocumentDir();
    int getChunkSize();
    int getChunkOverlap();
    List<String> getSupportedFileExtensions();
}
