package me.maxt.rag.web.service.environment;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * 进程探测工具，统一封装子进程的超时执行。
 * 供各个 DependencyChecker 实现类复用，避免重复 ProcessBuilder 样板代码。
 */
final class ProcessRunner {

    private static final Logger log = LoggerFactory.getLogger(ProcessRunner.class);

    private ProcessRunner() {}

    /**
     * 运行命令，等待 timeoutSeconds，捕获 stdout+stderr 输出。
     *
     * @return ProcessOutput 当 exitCode==0；null 表示超时/启动失败/非零退出
     */
    static ProcessOutput run(String[] command, int timeoutSeconds) {
        try {
            ProcessBuilder pb = new ProcessBuilder(command);
            pb.redirectErrorStream(true);
            Process process = pb.start();

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            Thread reader = new Thread(() -> {
                try {
                    process.getInputStream().transferTo(out);
                } catch (IOException ignored) {}
            });
            reader.setDaemon(true);
            reader.start();

            boolean finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                reader.interrupt();
                return null;
            }
            reader.join(1000);
            int exitCode = process.exitValue();
            if (exitCode != 0) {
                return null;
            }
            return new ProcessOutput(exitCode, out.toString(StandardCharsets.UTF_8).trim());
        } catch (Exception e) {
            log.debug("ProcessRunner {} failed: {}", command[0], e.getMessage());
            return null;
        }
    }

    /**
     * 运行命令并持续将 stdout 逐行交给 consumer（用于 pip install 等长时间任务）。
     *
     * @return 退出码；超时/异常返回 -1
     */
    static int runStreaming(String[] command, int timeoutSeconds, Consumer<String> consumer) {
        try {
            Process process = new ProcessBuilder(command)
                    .redirectErrorStream(true)
                    .start();

            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    consumer.accept(line);
                }
            }

            boolean finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                return -1;
            }
            return process.exitValue();
        } catch (Exception e) {
            log.error("ProcessRunner runStreaming {} failed: {}", String.join(" ", command), e.getMessage());
            return -1;
        }
    }

    record ProcessOutput(int exitCode, String output) {}
}
