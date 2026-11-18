package com.opspro.service;

import com.opspro.model.Registry;
import com.opspro.model.Registry.AppDef;
import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class AppService {

    private record Op(String action, long startedAt, long pid) {}

    private final OpsProps props;
    private final StateStore state;
    private final Map<String, Op> ops = new ConcurrentHashMap<>();
    private final Map<String, Long> lastPids = new ConcurrentHashMap<>();

    public AppService(OpsProps props, StateStore state) {
        this.props = props;
        this.state = state;
    }

    @PostConstruct
    void init() throws Exception { props.ensureDirs(); }

    public List<Map<String, Object>> list() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (AppDef a : Registry.APPS) out.add(info(a));
        return out;
    }

    public Map<String, Object> info(AppDef a) {
        Map<String, Object> m = new LinkedHashMap<>();
        boolean portOpen = ProcUtil.portOpen(a.port());
        Op op = ops.get(a.name());
        String status;
        if (op != null) {
            status = op.action().equals("start") ? "STARTING" : "STOPPING";
        } else if (portOpen) {
            status = "RUNNING";
        } else {
            Long pid = lastPids.get(a.name());
            boolean procAlive = pid != null && ProcUtil.pidAlive(pid);
            List<Long> portPids = ProcUtil.pidsByPort(a.port());
            status = (procAlive || !portPids.isEmpty()) ? "UNKNOWN" : "STOPPED";
        }
        m.put("name", a.name());
        m.put("label", a.label());
        m.put("dir", a.dir());
        m.put("port", a.port());
        m.put("endpoint", "127.0.0.1:" + a.port());
        m.put("url", "http://127.0.0.1:" + a.port() + "/");
        m.put("startScript", a.startScript());
        m.put("stopScript", a.stopScript());
        m.put("logDir", a.logDir());
        m.put("installed", a.installed());
        m.put("status", status);
        m.put("busy", op != null);
        m.put("pid", portOpen ? firstPid(a.port()) : lastPids.get(a.name()));
        m.put("hint", !a.installed() ? "启动脚本 " + a.startScript() + " 尚不存在（项目可能仍在构建中），仅可查看状态与日志" : "");
        return m;
    }

    private Long firstPid(int port) {
        List<Long> pids = ProcUtil.pidsByPort(port);
        return pids.isEmpty() ? null : pids.get(0);
    }

    public Map<String, Object> start(String name) {
        AppDef a = require(name);
        if (!a.installed()) throw new IllegalStateException("启动脚本 " + a.startScript() + " 不存在，无法启动（项目可能仍在构建中）");
        if (ops.containsKey(name)) throw new IllegalStateException("该应用已有操作进行中，请稍候");
        if (ProcUtil.portOpen(a.port())) throw new IllegalStateException("应用已在运行（端口 " + a.port() + " 已监听），无需重复启动");
        Path out = props.procLogDir().resolve(name + ".out");
        Process p;
        try {
            p = ProcUtil.startDetached(List.of("bash", a.startScript()), a.dirPath().toFile(), null, out);
        } catch (Exception e) {
            state.audit(name, "启动", "失败", "进程创建失败: " + e.getMessage());
            throw new IllegalStateException("启动进程创建失败: " + e.getMessage());
        }
        ops.put(name, new Op("start", System.currentTimeMillis(), p.pid()));
        lastPids.put(name, p.pid());
        state.audit(name, "启动", "已下发", "执行 bash " + a.startScript() + "，输出重定向 data/proc/" + name + ".out");
        Thread watcher = new Thread(() -> {
            String result = "失败";
            String detail;
            try {
                int code = p.waitFor();
                boolean up = ProcUtil.portOpen(a.port());
                if (up) {
                    result = "成功";
                    detail = "启动脚本退出码 " + code + "，端口 " + a.port() + " 已监听";
                } else {
                    detail = "启动脚本退出码 " + code + "，端口 " + a.port() + " 未监听，详见 data/proc/" + name + ".out";
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                detail = "启动监视被中断";
            } finally {
                ops.remove(name);
            }
            state.audit(name, "启动", result, detail);
        }, "app-start-" + name);
        watcher.setDaemon(true);
        watcher.start();
        return Map.of("ok", true, "message", "启动指令已下发，正在执行 " + a.startScript() + "（含编译，可能需要 1-2 分钟），状态将自动刷新");
    }

    public Map<String, Object> stop(String name) {
        AppDef a = require(name);
        if (ops.containsKey(name)) throw new IllegalStateException("该应用已有操作进行中，请稍候");
        boolean portOpen = ProcUtil.portOpen(a.port());
        if (!portOpen && !a.installed()) throw new IllegalStateException("应用未在运行，且停止脚本不存在");
        ops.put(name, new Op("stop", System.currentTimeMillis(), -1));
        state.audit(name, "停止", "已下发", "执行 bash " + a.stopScript() + "，必要时按端口 " + a.port() + " 兜底清理");
        Thread worker = new Thread(() -> {
            String result = "成功";
            StringBuilder detail = new StringBuilder();
            try {
                if (a.dirPath().resolve(a.stopScript()).toFile().isFile()) {
                    // 多进程应用（如 ride 10 进程逐个优雅停，每个最长 20s）最坏可超 200s，超时须留足
                    ProcUtil.ExecResult r = ProcUtil.exec(List.of("bash", a.stopScript()), a.dirPath().toFile(), null, 240);
                    detail.append("停止脚本退出码 ").append(r.code()).append("；");
                } else {
                    detail.append("停止脚本不存在，直接按端口清理；");
                }
                if (ProcUtil.portOpen(a.port())) {
                    ProcUtil.killByPort(a.port());
                    Thread.sleep(1500);
                    detail.append("已按端口兜底结束残留进程；");
                }
                if (ProcUtil.portOpen(a.port())) {
                    result = "失败";
                    detail.append("端口 ").append(a.port()).append(" 仍在监听");
                } else {
                    detail.append("端口 ").append(a.port()).append(" 已释放");
                }
            } catch (Exception e) {
                result = "失败";
                detail.append("异常: ").append(e.getMessage());
            } finally {
                ops.remove(name);
            }
            state.audit(name, "停止", result, detail.toString());
        }, "app-stop-" + name);
        worker.setDaemon(true);
        worker.start();
        return Map.of("ok", true, "message", "停止指令已下发，正在优雅停止（多进程应用可能需要数分钟），状态将自动刷新");
    }

    private AppDef require(String name) {
        AppDef a = Registry.app(name);
        if (a == null) throw new IllegalArgumentException("未知应用: " + name);
        return a;
    }
}