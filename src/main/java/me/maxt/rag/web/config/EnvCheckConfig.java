package me.maxt.rag.web.config;

/**
 * 环境检测与自动安装配置。
 */
public interface EnvCheckConfig {

    /** 是否启用环境检测，默认 true */
    boolean isEnvCheckEnabled();

    /** 缺失依赖是否自动安装（pip 包），默认 false（需显式开启） */
    boolean isAutoInstallEnabled();

    /** 全部检测的总超时秒数，默认 15 */
    int getEnvCheckTimeoutSeconds();

    /** 单个子进程探测超时秒数，默认 5 */
    int getProbeTimeoutSeconds();
}
