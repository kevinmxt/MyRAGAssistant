package me.maxt.rag.web.service.model;

import java.util.function.Consumer;

/**
 * 模型仓库：拥有模型制品的本地存在性（制品清单、镜像回退、原子落盘、状态记账）。
 * 实现必须同步执行（无线程池），线程调度归消费者。
 */
public interface ModelRepository {

    /**
     * 幂等确保制品全部文件就绪：文件齐全跳过网络，缺失则从镜像链补下。
     * 失败抛 {@link ModelDownloadException}。日志走 slf4j。
     */
    void ensurePresent(ModelArtifact artifact);

    /**
     * 同 {@link #ensurePresent(ModelArtifact)}，但逐文件日志行回调给消费者
     * （每文件一行，非进度百分比）。
     */
    void ensurePresent(ModelArtifact artifact, Consumer<String> log);

    /** 查询制品当前状态：有记账用记账，无记账时按文件存在性现算 MISSING/PRESENT。 */
    DownloadState state(String key);
}
