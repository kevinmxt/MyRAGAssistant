package me.maxt.rag.web.service.model;

/**
 * 模型下载失败：全部镜像不可用、Content-Length 大小校验不匹配、下载被中断等。
 */
public class ModelDownloadException extends RuntimeException {

    public ModelDownloadException(String message) {
        super(message);
    }

    public ModelDownloadException(String message, Throwable cause) {
        super(message, cause);
    }
}
