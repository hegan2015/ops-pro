package com.opspro.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class DockerService {

    public static final String PROJECT = "claim-shared";

    public record DockerStatus(boolean installed, boolean running, boolean composeOk, boolean available,
                               String version, String composeVersion, String message, String guidance) {}

    private static final String INSTALL_GUIDANCE =
            "本机未检测到 Docker。中间件统一由 Docker Compose 管理，请先安装 Docker Desktop（Apple Silicon 版）：" +
            "方式一：访问 https://www.docker.com/products/docker-desktop/ 下载 Apple Silicon 安装包并安装；" +
            "方式二：终端执行 brew install --cask docker，然后启动 Docker Desktop。" +
            "安装并启动后刷新本页即可自动识别，应用管理不受影响。";

    private static final String DAEMON_GUIDANCE =
            "已检测到 Docker CLI，但 Docker 守护进程未运行。请启动 Docker Desktop（应用程序中打开，等待状态栏鲸鱼图标变为 Running），然后刷新本页。";

    private final OpsProps props;
    private final ObjectMapper mapper = new ObjectMapper();
    private volatile String bin;
    private volatile boolean binResolved;
    private volatile DockerStatus cachedStatus;
    private volatile long statusAt;
    private volatile Map<String, String> cachedPs;
    private volatile long psAt;

    public DockerService(OpsProps props) { this.props = props; }

    public synchronized String bin() {
        if (binResolved) return bin;
        binResolved = true;
        String override = props.dockerBin();
        if (!override.isBlank()) {
            bin = Files.isExecutable(Path.of(override)) ? override : null;
            return bin;
        }
        List<String> candidates = List.of(
                "/opt/homebrew/bin/docker", "/usr/local/bin/docker",
                System.getProperty("user.home") + "/.docker/bin/docker",
                "/Applications/Docker.app/Contents/Resources/bin/docker");
        for (String c : candidates) {
            if (Files.isExecutable(Path.of(c))) { bin = c; return bin; }
        }
        ProcUtil.ExecResult r = ProcUtil.exec(List.of("docker", "--version"), null, null, 5);
        if (r.code() == 0) bin = "docker";
        return bin;
    }

    public DockerStatus status() {
        long now = System.currentTimeMillis();
        DockerStatus c = cachedStatus;
        if (c != null && now - statusAt < 10_000) return c;
        synchronized (this) {
            if (cachedStatus != null && System.currentTimeMillis() - statusAt < 10_000) return cachedStatus;
            cachedStatus = probe();
            statusAt = System.currentTimeMillis();
            return cachedStatus;
        }
    }

    private DockerStatus probe() {
        String b = bin();
        if (b == null) {
            return new DockerStatus(false, false, false, false, "", "",
                    "未安装 Docker，中间件不可用（NEED_DOCKER）", INSTALL_GUIDANCE);
        }
        ProcUtil.ExecResult info = ProcUtil.exec(List.of(b, "info", "--format", "{{.ServerVersion}}"), null, null, 10);
        boolean running = info.code() == 0 && !info.output().isBlank();
        if (!running) {
            return new DockerStatus(true, false, false, false, "", "",
                    "Docker 守护进程未运行", DAEMON_GUIDANCE);
        }
        ProcUtil.ExecResult cv = ProcUtil.exec(List.of(b, "compose", "version", "--short"), null, null, 10);
        boolean composeOk = cv.code() == 0 && !cv.output().isBlank();
        String version = info.output().trim();
        String composeVersion = composeOk ? cv.output().trim() : "";
        String message = composeOk ? "Docker 就绪（引擎 " + version + " / Compose " + composeVersion + "）"
                : "Docker 引擎已运行，但未检测到 Compose 插件，请升级 Docker Desktop";
        return new DockerStatus(true, true, composeOk, composeOk, version, composeVersion, message,
                composeOk ? "" : "请升级 Docker Desktop 至包含 docker compose 插件的版本后刷新本页。");
    }

    public Map<String, String> composePs() {
        long now = System.currentTimeMillis();
        Map<String, String> c = cachedPs;
        if (c != null && now - psAt < 5_000) return c;
        synchronized (this) {
            if (cachedPs != null && System.currentTimeMillis() - psAt < 5_000) return cachedPs;
            Map<String, String> result = new HashMap<>();
            DockerStatus ds = status();
            if (ds.available()) {
                ProcUtil.ExecResult r = ProcUtil.exec(composeCmd("ps", "--format", "json"), null, composeEnv(), 20);
                if (r.code() == 0) {
                    try {
                        String out = r.output().trim();
                        List<JsonNode> nodes = new ArrayList<>();
                        if (out.startsWith("[")) {
                            mapper.readTree(out).forEach(nodes::add);
                        } else {
                            for (String line : out.split("\n")) {
                                if (!line.isBlank()) nodes.add(mapper.readTree(line));
                            }
                        }
                        for (JsonNode n : nodes) {
                            String svc = n.path("Service").asText("");
                            String state = n.path("State").asText("");
                            if (!svc.isEmpty()) result.put(svc, state);
                        }
                    } catch (Exception ignored) {}
                }
            }
            cachedPs = result;
            psAt = System.currentTimeMillis();
            return result;
        }
    }

    public void invalidatePs() { psAt = 0; }

    public List<String> composeCmd(String... args) {
        List<String> cmd = new ArrayList<>(List.of(bin(), "compose", "-f",
                props.composePath().toString(), "-p", PROJECT));
        cmd.addAll(List.of(args));
        return cmd;
    }

    public Map<String, String> composeEnv() {
        Map<String, String> env = new LinkedHashMap<>();
        env.put("MYSQL_ROOT_PASSWORD", props.mysqlPassword());
        env.put("MINIO_ROOT_USER", props.minioUser());
        env.put("MINIO_ROOT_PASSWORD", props.minioPassword());
        // Docker Desktop 的 CLI 与 credential helper 在 ~/.docker/bin，nohup 启动的进程 PATH 里没有，
        // 不补上会导致 compose 拉镜像时报 docker-credential-desktop not found
        String b = bin();
        if (b != null && b.contains("/")) {
            String dockerDir = Path.of(b).getParent().toString();
            String path = System.getenv("PATH");
            if (path == null || !path.contains(dockerDir)) {
                env.put("PATH", dockerDir + (path == null ? "" : ":" + path));
            }
        }
        return env;
    }

    public ProcUtil.ExecResult composeUp(List<String> services, long timeoutSec) {
        List<String> cmd = composeCmd("up", "-d");
        cmd.addAll(services);
        return ProcUtil.exec(cmd, props.home().toFile(), composeEnv(), timeoutSec);
    }

    public ProcUtil.ExecResult composeStop(List<String> services, long timeoutSec) {
        List<String> cmd = composeCmd("stop");
        cmd.addAll(services);
        return ProcUtil.exec(cmd, props.home().toFile(), composeEnv(), timeoutSec);
    }
}