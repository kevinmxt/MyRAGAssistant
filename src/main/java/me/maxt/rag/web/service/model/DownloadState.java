package me.maxt.rag.web.service.model;

/**
 * 制品下载状态。
 *
 * @param status 状态
 * @param detail 说明（当前文件名 / 就绪摘要 / 失败原因）
 */
public record DownloadState(Status status, String detail) {

    /** MISSING 文件缺失（含未注册）；DOWNLOADING 下载中；PRESENT 文件齐全；FAILED 上次尝试失败 */
    public enum Status { MISSING, DOWNLOADING, PRESENT, FAILED }
}
