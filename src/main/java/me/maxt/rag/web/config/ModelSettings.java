package me.maxt.rag.web.config;

/**
 * 模型下载配置节。
 *
 * @author maxt
 * @since 1.0
 */
public record ModelSettings(
        @Key(json = "model.downloadMirror", env = "RAG_MODEL_MIRROR",
                def = "https://hf-mirror.com") String getDownloadMirror,
        @Key(json = "model.autoDownload", env = "RAG_MODEL_AUTO_DOWNLOAD",
                def = "true") boolean isAutoDownload) implements ModelConfig {
}
