package me.maxt.rag.web.service.environment;

/**
 * 单条外部依赖的检测结果。
 *
 * @param name     唯一标识，如 "python"、"milvus"
 * @param category 依赖分类
 * @param status   状态
 * @param version  版本号（可为 null）
 * @param message  详情，MISSING/ERROR 时包含修复提示
 */
public record CheckResult(
        String name,
        Category category,
        Status status,
        String version,
        String message) {

    public enum Status { OK, MISSING, INSTALLING, ERROR, SKIPPED }

    public enum Category { RUNTIME, PIP, SERVICE, BINARY, MODEL }

    public boolean ok() { return status == Status.OK; }

    public static CheckResult skipped(String name, String message) {
        return new CheckResult(name, Category.BINARY, Status.SKIPPED, null, message);
    }
}
