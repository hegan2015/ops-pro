package com.opspro.service;

import com.opspro.model.Registry;
import com.opspro.model.Registry.MwDef;
import com.opspro.service.DockerService.DockerStatus;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class MiddlewareService {

    private record Op(String action, long startedAt) {}

    private static final String ALL = "__all__";

    private final DockerService docker;
    private final StateStore state;
    private final Map<String, Op> ops = new ConcurrentHashMap<>();

    public MiddlewareService(DockerService docker, StateStore state) {
        this.docker = docker;
        this.state = state;
    }

    public List<Map<String, Object>> list() {
        DockerStatus ds = docker.status();
        Map<String, String> ps = ds.available() ? docker.composePs() : Map.of();
        List<Map<String, Object>> out = new ArrayList<>();
        for (MwDef m : Registry.MIDDLEWARE) {
            Map<String, Object> item = new LinkedHashMap<>();
            boolean portOpen = ProcUtil.portOpen(m.port());
            Op op = ops.get(m.name());
            Op allOp = ops.get(ALL);
            String status;
            if (op != null || allOp != null) {
                String action = op != null ? op.action() : allOp.action();
                status = action.equals("start") ? "STARTING" : "STOPPING";
            } else if (!ds.available()) {
                status = "NEED_DOCKER";
            } else {
                boolean containerUp = m.composeServices().stream()
                        .anyMatch(s -> "running".equalsIgnoreCase(ps.get(s)));
                status = containerUp || portOpen ? "RUNNING" : "STOPPED";
            }
            item.put("name", m.name());
            item.put("label", m.label());
            item.put("desc", m.desc());
            item.put("port", m.port());
            item.put("consolePort", m.consolePort());
            item.put("endpoint", m.endpoint());
            item.put("consoleUrl", m.consolePort() == null ? null : "http://127.0.0.1:" + m.consolePort() + "/");
            item.put("usedBy", m.usedBy());
            item.put("composeServices", m.composeServices());
            item.put("status", status);
            item.put("busy", op != null || allOp != null);
            item.put("portOpen", portOpen);
            item.put("containerState", ps.getOrDefault(m.composeServices().get(0), ""));
            item.put("hint", status.equals("NEED_DOCKER") ? ds.guidance() : "");
            out.add(item);
        }
        return out;
    }

    public Map<String, Object> start(String name) {
        MwDef m = require(name);
        return runOp(m.name(), "start", m.composeServices(),
                "docker compose up -d " + String.join(" ", m.composeServices()));
    }

    public Map<String, Object> stop(String name) {
        MwDef m = require(name);
        return runOp(m.name(), "stop", m.composeServices(),
                "docker compose stop " + String.join(" ", m.composeServices()));
    }

    public Map<String, Object> startAll() {
        return runOp(ALL, "start", List.of(), "docker compose up -d（全部服务）");
    }

    public Map<String, Object> stopAll() {
        return runOp(ALL, "stop", List.of(), "docker compose stop（全部服务）");
    }

    private Map<String, Object> runOp(String key, String action, List<String> services, String desc) {
        DockerStatus ds = docker.status();
        if (!ds.available()) throw new IllegalStateException(ds.guidance());
        if (!ops.isEmpty()) throw new IllegalStateException("已有中间件操作进行中，请稍候");
        ops.put(key, new Op(action, System.currentTimeMillis()));
        String target = key.equals(ALL) ? "全部中间件" : key;
        String label = action.equals("start") ? "启动" : "停止";
        state.audit(target, label, "已下发", desc);
        Thread worker = new Thread(() -> {
            String result;
            String detail;
            try {
                ProcUtil.ExecResult r = action.equals("start")
                        ? docker.composeUp(services, 900)
                        : docker.composeStop(services, 120);
                docker.invalidatePs();
                if (r.code() == 0) {
                    result = "成功";
                    detail = desc + " 执行完成";
                } else {
                    result = "失败";
                    String out = r.output();
                    detail = desc + " 退出码 " + r.code() + "：" + (out.length() > 400 ? out.substring(out.length() - 400) : out);
                }
            } catch (Exception e) {
                result = "失败";
                detail = "异常: " + e.getMessage();
            } finally {
                ops.remove(key);
            }
            state.audit(target, label, result, detail);
        }, "mw-" + action + "-" + key);
        worker.setDaemon(true);
        worker.start();
        return Map.of("ok", true, "message", label + "指令已下发（" + desc + "），首次启动需拉取镜像，耗时可能较长，状态将自动刷新");
    }

    private MwDef require(String name) {
        MwDef m = Registry.mw(name);
        if (m == null) throw new IllegalArgumentException("未知中间件: " + name);
        return m;
    }
}