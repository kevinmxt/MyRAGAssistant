package me.maxt.rag.web.service.model;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * HttpModelRepository 单元测试：本地 HttpServer 模拟镜像链，不访问外网。
 */
class HttpModelRepositoryTest {

    /** 与真实 HuggingFace URL 结构一致：/{repo}/resolve/main/{repoPath} */
    private static final String REPO = "test-repo/model";
    private static final String REPO_PREFIX = "/" + REPO + "/resolve/main/";

    @TempDir
    Path tempDir;

    private final List<AutoCloseable> closeables = new ArrayList<>();

    @AfterEach
    void stopServers() {
        closeables.forEach(c -> {
            try {
                c.close();
            } catch (Exception ignored) {
                // 清理阶段忽略
            }
        });
    }

    @Test
    void shouldReportMissingWhenFilesAbsent() {
        HttpModelRepository repository = new HttpModelRepository(List.of("http://localhost:1"));

        DownloadState state = repository.state("bge-reranker");

        assertThat(state.status()).isEqualTo(DownloadState.Status.MISSING);
    }

    @Test
    void shouldDownloadWithMirrorFallback() throws Exception {
        TestServer serverA = startFileServer(Map.of());   // 首选镜像全 404
        TestServer serverB = startFileServer(content());  // 次选镜像有全部文件
        HttpModelRepository repository = new HttpModelRepository(List.of(serverA.baseUrl(), serverB.baseUrl()));
        Path targetDir = tempDir.resolve("model");

        repository.ensurePresent(artifact(targetDir));

        assertThat(new String(Files.readAllBytes(targetDir.resolve("model.onnx")), StandardCharsets.UTF_8))
                .isEqualTo("fake-onnx");
        assertThat(Files.readString(targetDir.resolve("tokenizer.json"))).isEqualTo("fake-tokenizer");
        assertThat(repository.state("bge-reranker").status()).isEqualTo(DownloadState.Status.PRESENT);
        assertThat(serverA.requestCount()).isEqualTo(2);  // 每个文件都先试 A
        assertThat(serverB.requestCount()).isEqualTo(2);  // A 404 后回退到 B
    }

    @Test
    void shouldSkipNetworkWhenAllFilesPresent() throws Exception {
        TestServer server = startFileServer(content());
        HttpModelRepository repository = new HttpModelRepository(List.of(server.baseUrl()));
        Path targetDir = tempDir.resolve("model");
        Files.createDirectories(targetDir);
        Files.writeString(targetDir.resolve("model.onnx"), "local-onnx");
        Files.writeString(targetDir.resolve("tokenizer.json"), "local-tokenizer");

        repository.ensurePresent(artifact(targetDir));

        assertThat(server.requestCount()).isZero();
        assertThat(repository.state("bge-reranker").status()).isEqualTo(DownloadState.Status.PRESENT);
    }

    @Test
    void shouldFailWhenAllMirrorsUnavailable() throws Exception {
        TestServer serverA = startFileServer(Map.of());
        TestServer serverB = startFileServer(Map.of());
        HttpModelRepository repository = new HttpModelRepository(List.of(serverA.baseUrl(), serverB.baseUrl()));
        Path targetDir = tempDir.resolve("model");

        assertThatThrownBy(() -> repository.ensurePresent(artifact(targetDir)))
                .isInstanceOf(ModelDownloadException.class);

        assertThat(repository.state("bge-reranker").status()).isEqualTo(DownloadState.Status.FAILED);
        assertThat(targetDir.resolve("model.onnx")).doesNotExist();
        assertThat(targetDir.resolve("tokenizer.json")).doesNotExist();
        assertThat(targetDir.resolve("model.onnx.part")).doesNotExist();
    }

    @Test
    void shouldFailOnSizeMismatch() throws Exception {
        // 声明的 Content-Length 比实际响应体大且提前断流，客户端拿到的字节少于声明值
        TestServer server = startTruncatingServer(content(), 100);
        HttpModelRepository repository = new HttpModelRepository(List.of(server.baseUrl()));
        Path targetDir = tempDir.resolve("model");

        assertThatThrownBy(() -> repository.ensurePresent(artifact(targetDir)))
                .isInstanceOf(ModelDownloadException.class);

        assertThat(repository.state("bge-reranker").status()).isEqualTo(DownloadState.Status.FAILED);
        assertThat(targetDir.resolve("model.onnx")).doesNotExist();       // 不 rename
        assertThat(targetDir.resolve("model.onnx.part")).doesNotExist();  // 半截文件不残留
    }

    @Test
    void shouldStreamLogLinesToConsumer() throws Exception {
        TestServer server = startFileServer(content());
        HttpModelRepository repository = new HttpModelRepository(List.of(server.baseUrl()));
        List<String> lines = new ArrayList<>();

        repository.ensurePresent(artifact(tempDir.resolve("model")), lines::add);

        assertThat(lines).anyMatch(line -> line.contains("model.onnx"));
        assertThat(lines).anyMatch(line -> line.contains("tokenizer.json"));
    }

    @Test
    void shouldCreateParentDirsForNestedLocalNames() throws Exception {
        // 嵌入制品含 "1_Pooling/config.json" 这类嵌套本地名，落盘前必须先建父目录
        TestServer server = startFileServer(
                Map.of("1_Pooling/config.json", "fake-pooling".getBytes(StandardCharsets.UTF_8)));
        HttpModelRepository repository = new HttpModelRepository(List.of(server.baseUrl()));
        Path targetDir = tempDir.resolve("model");

        ModelArtifact artifact = new ModelArtifact("bge-embedding", REPO,
                Map.of("1_Pooling/config.json", "1_Pooling/config.json"), targetDir);
        repository.ensurePresent(artifact);

        assertThat(Files.readString(targetDir.resolve("1_Pooling/config.json"))).isEqualTo("fake-pooling");
        assertThat(repository.state("bge-embedding").status()).isEqualTo(DownloadState.Status.PRESENT);
    }

    @Test
    void shouldNotThrowWhenReensureAfterPresent() throws Exception {
        TestServer server = startFileServer(content());
        HttpModelRepository repository = new HttpModelRepository(List.of(server.baseUrl()));
        Path targetDir = tempDir.resolve("model");
        repository.ensurePresent(artifact(targetDir));
        int requestsAfterFirst = server.requestCount();

        repository.ensurePresent(artifact(targetDir));

        assertThat(server.requestCount()).isEqualTo(requestsAfterFirst);
        assertThat(repository.state("bge-reranker").status()).isEqualTo(DownloadState.Status.PRESENT);
    }

    @Test
    void shouldComputeStateFromFilePresenceWithoutEnsurePresent() throws Exception {
        // 构造期注册后，未调 ensurePresent 时 state() 按文件现算而非"未注册的制品"
        // （autoDownload=false 手动放置模型、或环境检测先于下载线程的场景）
        Path presentDir = tempDir.resolve("present");
        Files.createDirectories(presentDir);
        Files.writeString(presentDir.resolve("model.onnx"), "local-onnx");
        Files.writeString(presentDir.resolve("tokenizer.json"), "local-tokenizer");
        ModelArtifact present = new ModelArtifact("present", REPO,
                Map.of("model.onnx", "onnx/model.onnx",
                       "tokenizer.json", "tokenizer.json"),
                presentDir);
        ModelArtifact absent = new ModelArtifact("absent", REPO,
                Map.of("model.onnx", "onnx/model.onnx"), tempDir.resolve("absent"));
        HttpModelRepository repository = new HttpModelRepository(List.of("http://localhost:1"), present, absent);

        DownloadState presentState = repository.state("present");
        DownloadState absentState = repository.state("absent");

        assertThat(presentState.status()).isEqualTo(DownloadState.Status.PRESENT);
        assertThat(absentState.status()).isEqualTo(DownloadState.Status.MISSING);
        assertThat(absentState.detail()).contains("文件缺失");  // 走的是文件现算分支，非"未注册"
    }

    // ---- 工具 ----

    private static ModelArtifact artifact(Path targetDir) {
        return new ModelArtifact("bge-reranker", REPO,
                Map.of("model.onnx", "onnx/model.onnx",
                       "tokenizer.json", "tokenizer.json"),
                targetDir);
    }

    private static Map<String, byte[]> content() {
        return Map.of(
                "onnx/model.onnx", "fake-onnx".getBytes(StandardCharsets.UTF_8),
                "tokenizer.json", "fake-tokenizer".getBytes(StandardCharsets.UTF_8));
    }

    /** 启动本地文件服务器（映射内路径 200、其余 404），随机端口，返回基址与请求计数 */
    private TestServer startFileServer(Map<String, byte[]> content) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        AtomicInteger requests = new AtomicInteger();
        server.createContext("/", ex -> {
            requests.incrementAndGet();
            try {
                serve(ex, content);
            } catch (IOException e) {
                // 客户端提前断开等场景，吞掉避免污染测试输出
            }
        });
        server.start();
        closeables.add(() -> server.stop(0));
        return new TestServer("http://localhost:" + server.getAddress().getPort(), requests);
    }

    private static void serve(HttpExchange ex, Map<String, byte[]> content) throws IOException {
        String path = ex.getRequestURI().getPath();
        byte[] body = path.startsWith(REPO_PREFIX) ? content.get(path.substring(REPO_PREFIX.length())) : null;
        if (body == null) {
            ex.sendResponseHeaders(404, -1);
            ex.close();
            return;
        }
        ex.sendResponseHeaders(200, body.length);
        try (OutputStream out = ex.getResponseBody()) {
            out.write(body);
        }
    }

    /**
     * 原始 socket 谎报服务器：声明 Content-Length 大于实际响应体，写完立即关连接截断流。
     * 不用 HttpServer 是因为固定长度截断时其 keep-alive 不发 FIN，客户端会阻塞等缺失字节。
     */
    private TestServer startTruncatingServer(Map<String, byte[]> content, long declaredLengthDelta)
            throws IOException {
        ServerSocket serverSocket = new ServerSocket(0, 50, InetAddress.getLoopbackAddress());
        closeables.add(serverSocket);
        AtomicInteger requests = new AtomicInteger();
        Thread acceptor = new Thread(
                () -> acceptLoop(serverSocket, content, declaredLengthDelta, requests), "truncating-server");
        acceptor.setDaemon(true);
        acceptor.start();
        return new TestServer("http://127.0.0.1:" + serverSocket.getLocalPort(), requests);
    }

    private static void acceptLoop(ServerSocket serverSocket, Map<String, byte[]> content,
                                   long declaredLengthDelta, AtomicInteger requests) {
        while (true) {
            try (Socket socket = serverSocket.accept()) {
                requests.incrementAndGet();
                String path = readRequestPath(socket.getInputStream());
                byte[] body = path != null && path.startsWith(REPO_PREFIX)
                        ? content.get(path.substring(REPO_PREFIX.length())) : null;
                OutputStream out = socket.getOutputStream();
                if (body == null) {
                    out.write("HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\n\r\n"
                            .getBytes(StandardCharsets.UTF_8));
                    continue;
                }
                String head = "HTTP/1.1 200 OK\r\nContent-Length: " + (body.length + declaredLengthDelta)
                        + "\r\nConnection: close\r\n\r\n";
                out.write(head.getBytes(StandardCharsets.UTF_8));
                out.write(body);
                out.flush();
            } catch (IOException e) {
                return;  // 服务器已关闭
            }
        }
    }

    /** 只读请求行（GET {path} HTTP/1.1），返回路径 */
    private static String readRequestPath(InputStream in) throws IOException {
        StringBuilder line = new StringBuilder();
        int b;
        while ((b = in.read()) != -1) {
            if (b == '\n') {
                break;
            }
            line.append((char) b);
        }
        String[] parts = line.toString().trim().split(" ");
        return parts.length >= 2 ? parts[1] : null;
    }

    private record TestServer(String baseUrl, AtomicInteger requests) {
        int requestCount() {
            return requests.get();
        }
    }
}
