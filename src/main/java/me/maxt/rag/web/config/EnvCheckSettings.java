package me.maxt.rag.web.config;

/**
 * 环境检测与自动安装配置节。
 *
 * @author maxt
 * @since 1.0
 */
public record EnvCheckSettings(
        @Key(json = "environment.enabled", env = "RAG_ENV_CHECK_ENABLED",
                def = "true") boolean isEnvCheckEnabled,
        @Key(json = "environment.autoInstall", env = "RAG_ENV_AUTO_INSTALL",
                def = "false") boolean isAutoInstallEnabled,
        @Key(json = "environment.checkTimeoutSeconds", env = "RAG_ENV_CHECK_TIMEOUT",
                def = "15") int getEnvCheckTimeoutSeconds,
        @Key(json = "environment.probeTimeoutSeconds", env = "RAG_ENV_PROBE_TIMEOUT",
                def = "5") int getProbeTimeoutSeconds) implements EnvCheckConfig {
}
