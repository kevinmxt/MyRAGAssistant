package me.maxt.rag.web.config;

/**
 * 模型下载全局配置接口。
 */
public interface ModelConfig {

    /** 模型下载镜像地址，默认 https://hf-mirror.com */
    String getDownloadMirror();

    /** 模型文件缺失时是否自动下载，默认 true */
    boolean isAutoDownload();
}
