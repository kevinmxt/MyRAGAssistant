package me.maxt.rag.web.service.model;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * 基于 HTTP 镜像链的模型仓库实现。
 *
 * <p>URL 拼接：{baseUrl}/{repo}/resolve/main/{repoPath}，镜像按注入顺序回退。
 * 单文件先写 ".part" 临时文件，Content-Length 校验通过后原子 rename，
 * 半截文件永远不会被当成已下载。仓库同步执行（无线程池），
 * 同 key 并发 ensurePresent 用锁对象表互斥。
 */
public class HttpModelRepository implements ModelRepository {

    private static final Logger log = LoggerFactory.getLogger(HttpModelRepository.class);
    private static final String PART_SUFFIX = ".part";

    private final List<String> mirrorBaseUrls;
    private final HttpClient client = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL)
            .connectTimeout(Duration.ofSeconds(10))
            .build();
    /** ensurePresent 注册过的制品，供 state() 无记账时按文件存在性现算 */
    private final ConcurrentHashMap<String, ModelArtifact> artifacts = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, DownloadState> states = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Object> locks = new ConcurrentHashMap<>();

    public HttpModelRepository(List<String> mirrorBaseUrls) {
        this.mirrorBaseUrls = List.copyOf(mirrorBaseUrls);
    }

    @Override
    public void ensurePresent(ModelArtifact artifact) {
        ensurePresent(artifact, line -> log.info(line));
    }

    @Override
    public void ensurePresent(ModelArtifact artifact, Consumer<String> logLine) {
        artifacts.put(artifact.key(), artifact);
        Object lock = locks.computeIfAbsent(artifact.key(), k -> new Object());
        synchronized (lock) {
            // 进入后复查状态：已 PRESENT 直接返回（含另一线程刚下完的场景）
            if (state(artifact.key()).status() == DownloadState.Status.PRESENT) {
                return;
            }
            states.put(artifact.key(), new DownloadState(DownloadState.Status.DOWNLOADING, artifact.key()));
            try {
                downloadAll(artifact, logLine);
                states.put(artifact.key(), new DownloadState(DownloadState.Status.PRESENT,
                        artifact.files().size() + " 个文件就绪: " + artifact.targetDir()));
            } catch (RuntimeException e) {
                states.put(artifact.key(), new DownloadState(DownloadState.Status.FAILED, e.getMessage()));
                throw e;
            }
        }
    }

    @Override
    public DownloadState state(String key) {
        DownloadState recorded = states.get(key);
        if (recorded != null) {
            return recorded;
        }
        ModelArtifact artifact = artifacts.get(key);
        if (artifact == null) {
            return new DownloadState(DownloadState.Status.MISSING, "未注册的制品: " + key);
        }
        return allFilesPresent(artifact)
                ? new DownloadState(DownloadState.Status.PRESENT, "文件齐全: " + artifact.targetDir())
                : new DownloadState(DownloadState.Status.MISSING, "文件缺失: " + artifact.targetDir());
    }

    private void downloadAll(ModelArtifact artifact, Consumer<String> logLine) {
        for (Map.Entry<String, String> entry : artifact.files().entrySet()) {
            String localName = entry.getKey();
            Path target = artifact.targetDir().resolve(localName);
            if (Files.exists(target)) {
                continue;  // 已存在即跳过（不重新校验，避免重复下载大文件）
            }
            states.put(artifact.key(), new DownloadState(DownloadState.Status.DOWNLOADING, localName));
            downloadFile(artifact, localName, entry.getValue(), target, logLine);
        }
    }

    private void downloadFile(ModelArtifact artifact, String localName, String repoPath, Path target,
                              Consumer<String> logLine) {
        Path part = artifact.targetDir().resolve(localName + PART_SUFFIX);
        String lastError = null;
        for (String baseUrl : mirrorBaseUrls) {
            String url = baseUrl + "/" + artifact.repo() + "/resolve/main/" + repoPath;
            try {
                logLine.accept("开始下载 " + localName + " (" + url + ")");
                HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                        .timeout(Duration.ofSeconds(300))
                        .build();
                HttpResponse<InputStream> response =
                        client.send(request, HttpResponse.BodyHandlers.ofInputStream());
                if (response.statusCode() != 200) {
                    lastError = "HTTP " + response.statusCode() + " (" + url + ")";
                    try (InputStream body = response.body()) {
                        // 丢弃错误响应体
                    }
                    continue;
                }
                long declaredSize = response.headers().firstValueAsLong("Content-Length").orElse(-1);
                Files.createDirectories(part.getParent());
                try (InputStream body = response.body()) {
                    Files.copy(body, part, StandardCopyOption.REPLACE_EXISTING);
                }
                long actualSize = Files.size(part);
                if (declaredSize > 0 && actualSize != declaredSize) {
                    Files.deleteIfExists(part);
                    lastError = "大小不匹配: 声明 " + declaredSize + " 字节, 实际 " + actualSize
                            + " (" + url + ")";
                    continue;
                }
                Files.move(part, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                logLine.accept("已下载 " + localName + " (" + actualSize + " 字节)");
                return;
            } catch (IOException e) {
                lastError = e.getClass().getSimpleName() + ": " + e.getMessage() + " (" + url + ")";
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new ModelDownloadException("下载被中断: " + localName, e);
            }
        }
        deleteQuietly(part);
        throw new ModelDownloadException("文件 " + localName + " 全部镜像下载失败: " + lastError);
    }

    private static boolean allFilesPresent(ModelArtifact artifact) {
        return artifact.files().keySet().stream()
                .allMatch(name -> Files.exists(artifact.targetDir().resolve(name)));
    }

    private static void deleteQuietly(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException e) {
            log.debug("清理临时文件失败: {}", path);
        }
    }
}
