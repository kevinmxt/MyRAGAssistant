package me.maxt.rag.web.service.environment;

import me.maxt.rag.web.config.EnvCheckConfig;
import me.maxt.rag.web.service.environment.CheckResult.Category;
import me.maxt.rag.web.service.environment.CheckResult.Status;

/**
 * Milvus 连接性检测。TCP 探针 + gRPC 版本查询。
 */
public class MilvusChecker implements DependencyChecker {

    private final String host;
    private final int port;

    public MilvusChecker(EnvCheckConfig config, String host, int port) {
        this.host = host;
        this.port = port;
    }

    @Override
    public String name() { return "milvus"; }

    @Override
    public CheckResult check() {
        // TCP connect 快速判断可达性
        try (java.net.Socket socket = new java.net.Socket()) {
            socket.connect(new java.net.InetSocketAddress(host, port), 2000);
        } catch (Exception e) {
            return new CheckResult(name(), Category.SERVICE, Status.MISSING, null,
                    "Milvus 不可达 (" + host + ":" + port + ") → docker compose up -d 启动 Milvus");
        }

        // 尝试 gRPC 版本查询（best-effort）
        String version = null;
        try {
            io.milvus.v2.client.ConnectConfig connectConfig = io.milvus.v2.client.ConnectConfig.builder()
                    .uri("http://" + host + ":" + port)
                    .build();
            io.milvus.v2.client.MilvusClientV2 client = new io.milvus.v2.client.MilvusClientV2(connectConfig);
            try {
                Object resp = client.getServerVersion();
                if (resp != null) {
                    version = resp.toString();
                }
            } catch (Exception e) {
                // 版本查询失败不影响可达性判断
            }
            client.close();
        } catch (Exception e) {
            // 版本查询失败不影响可达性判断
        }

        return new CheckResult(name(), Category.SERVICE, Status.OK, version,
                "Milvus 可连接 (" + host + ":" + port + ")");
    }
}
