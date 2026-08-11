package me.maxt.rag.web.service.environment;

import java.util.function.Consumer;

/**
 * 外部依赖检测器。每个外部依赖一个实现类。
 */
public interface DependencyChecker {

    /** 唯一标识，如 "python"、"milvus" */
    String name();

    /** 执行检测，实现必须自带超时保护 */
    CheckResult check();

    /** 是否支持自动安装（默认否） */
    default boolean canAutoInstall() { return false; }

    /** 执行自动安装，日志通过 consumer 流式输出；成功返回 true */
    default boolean autoInstall(Consumer<String> logConsumer) { return false; }
}
