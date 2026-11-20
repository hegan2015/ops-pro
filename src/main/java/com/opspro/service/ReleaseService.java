package com.opspro.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.opspro.model.Registry;
import com.opspro.model.Registry.AppDef;
import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 灰度发布：版本台账(jar 指纹/git commit) + canary 实例编排(端口偏移、隔离日志/pid) + 健康门 + 晋升/回滚。
 * canary 仅支持单 gateway jar 应用；多进程/脚本应用标记不支持，走维护窗口。
 * 历史持久化 data/releases.json；canary 进程日志/pid 在 data/release/ 下，不污染应用自身 run/logs。
 */
@Component
public class ReleaseService {

    public record Desc(String kind, String jarRel, String canaryProfile, int offset) {}
    public record Hist(String time, String app, String action, String result, String detail) {}
    private record Op(String action, long startedAt) {}

    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final int MAX_HIST = 200;
    private static final int CANARY_OFFSET = 1000;

    private static final Map<String, Desc> DESCS = Map.of(
            "claim-pro", new Desc("jar", "claim-gateway/target/claim-pro.jar", "local", CANARY_OFFSET),
            "cs-pro", new Desc("jar", "cs-gateway/target/cs-gateway.jar", "local", CANARY_OFFSET),
            "payment-pro", new Desc("jar", "payment-gateway/target/payment-gateway-1.0.0.jar", "local", CANARY_OFFSET),
            "ride-pro", new Desc("multi", null, null, CANARY_OFFSET),
            "yiwu-pro", new Desc("script", null, null, CANARY_OFFSET));

    private final OpsProps props;
    private final AppService apps;
    private final StateStore state;
    private final ObjectMapper mapper = new ObjectMapper();
    private final List<Hist> hist = new ArrayList<>();
    private final Map<String, String> canaryStatus = new ConcurrentHashMap<>();
    private final Map<String, Op> ops = new ConcurrentHashMap<>();
    private final Map<String, String[]> jarCache = new ConcurrentHashMap<>();
    private final Object lock = new Object();

    public ReleaseService(OpsProps props, AppService apps, StateStore state) {
        this.props = props; this.apps = apps; this.state = state;
    }

    @PostConstruct
    @SuppressWarnings("unchecked")
    void load() {
        Path f = props.data().resolve("releases.json");
        if (!Files.isRegularFile(f)) return;
        try {
            Map<String, Object> root = mapper.readValue(f.toFile(), Map.class);
            for (Map<String, Object> m : (List<Map<String, Object>>) root.getOrDefault("hist", List.of()))
                hist.add(new Hist(str(m.get("time")), str(m.get("app")), str(m.get("action")), str(m.get("result")), str(m.get("detail"))));
        } catch (Exception e) { System.err.println("releases.json 读取失败: " + e.getMessage()); }
    }

    private static String str(Object o) { return o == null ? "" : o.toString(); }

    public List<Map<String, Object>> list() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (AppDef a : Registry.APPS) out.add(ledger(a));
        return out;
    }

    public Map<String, Object> ledger(AppDef a) {
        Desc d = DESCS.getOrDefault(a.name(), new Desc("script", null, null, CANARY_OFFSET));
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", a.name()); m.put("label", a.label()); m.put("kind", d.kind());
        m.put("port", a.port()); m.put("canaryPort", a.port() + d.offset());
        m.put("canarySupported", d.kind().equals("jar"));
        m.put("canaryProfile", d.canaryProfile());
        m.put("git", gitCommit(a));
        Path jar = d.jarRel() == null ? null : a.dirPath().resolve(d.jarRel());
        if (jar != null && Files.isRegularFile(jar)) {
            String[] fp = fingerprint(jar);
            m.put("jar", d.jarRel()); m.put("jarSha", fp[0]); m.put("jarMtime", fp[1]); m.put("jarMb", fp[2]);
        } else { m.put("jar", null); }
        String st = canaryStatus.getOrDefault(a.name(), "IDLE");
        if (st.equals("UP") && !ProcUtil.portOpen(a.port() + d.offset())) st = "IDLE";
        m.put("canaryStatus", st);
        m.put("busy", ops.containsKey(a.name()));
        m.put("mainStatus", ProcUtil.portOpen(a.port()) ? "RUNNING" : "STOPPED");
        return m;
    }

    private String gitCommit(AppDef a) {
        String s = ProcUtil.execCapture(List.of("git", "-C", a.dir(), "rev-parse", "--short", "HEAD"), null, 5).trim();
        return s.isEmpty() ? null : s;
    }

    private String[] fingerprint(Path jar) {
        try {
            long mt = Files.getLastModifiedTime(jar).toMillis();
            long sz = Files.size(jar);
            String key = mt + ":" + sz;
            String[] cached = jarCache.get(jar.toString());
            if (cached != null && cached[3].equals(key)) return cached;
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(Files.readAllBytes(jar));
            String sha = HexFormat.of().formatHex(digest).substring(0, 12);
            String[] fp = {sha, String.valueOf(mt), String.valueOf(Math.round(sz / 1048576.0)), key};
            jarCache.put(jar.toString(), fp);
            return fp;
        } catch (Exception e) { return new String[]{"?", "0", "0", ""}; }
    }

    public Map<String, Object> canaryStart(String name, String profile) {
        AppDef a = require(name);
        Desc d = desc(a);
        if (!d.kind().equals("jar")) throw new IllegalStateException("该应用为 " + d.kind() + " 形态，不支持 canary，请走维护窗口发布");
        Path jar = a.dirPath().resolve(d.jarRel());
        if (!Files.isRegularFile(jar)) throw new IllegalStateException("jar 不存在: " + d.jarRel() + "（先构建）");
        int cp = a.port() + d.offset();
        if (ProcUtil.portOpen(cp)) throw new IllegalStateException("canary 端口 " + cp + " 已在监听");
        if (ops.containsKey(name)) throw new IllegalStateException("该应用已有发布操作进行中");
        String prof = (profile == null || profile.isBlank()) ? d.canaryProfile() : profile;
        Path out = props.data().resolve("release").resolve(name + "-canary.out");
        try {
            ProcUtil.startDetached(List.of(ProcUtil.javaHome() + "/bin/java", "-jar", jar.toString(),
                    "--server.port=" + cp, "--spring.profiles.active=" + prof), a.dirPath().toFile(), null, out);
        } catch (Exception e) {
            throw new IllegalStateException("canary 进程创建失败: " + e.getMessage());
        }
        canaryStatus.put(name, "STARTING");
        ops.put(name, new Op("canary", System.currentTimeMillis()));
        record(name, "起canary", "已下发", "port=" + cp + " profile=" + prof);
        Thread w = new Thread(() -> {
            String result = "失败"; String detail;
            try {
                long deadline = System.currentTimeMillis() + 120_000;
                boolean up = false;
                while (System.currentTimeMillis() < deadline) {
                    if (ProcUtil.portOpen(cp)) { up = true; break; }
                    Thread.sleep(2000);
                }
                canaryStatus.put(name, up ? "UP" : "FAILED");
                result = up ? "成功" : "失败";
                detail = up ? "canary 端口 " + cp + " 已监听，健康门通过" : "120s 内 canary 端口 " + cp + " 未监听，详见 data/release/" + name + "-canary.out";
            } catch (InterruptedException e) { Thread.currentThread().interrupt(); detail = "监视中断"; }
            finally { ops.remove(name); }
            record(name, "起canary", result, detail);
        }, "canary-" + name);
        w.setDaemon(true); w.start();
        return Map.of("ok", true, "message", "canary 启动中（port " + cp + ", profile " + prof + "），健康门自动判定");
    }

    public Map<String, Object> canaryStop(String name) {
        AppDef a = require(name);
        int cp = a.port() + desc(a).offset();
        ProcUtil.killByPort(cp);
        canaryStatus.put(name, "IDLE");
        record(name, "停canary", "成功", "canary 端口 " + cp + " 已清理");
        return Map.of("ok", true, "message", "canary 已停止");
    }

    public Map<String, Object> rollback(String name) {
        canaryStop(name);
        record(name, "灰度回滚", "成功", "保留主实例旧版本，canary 已销毁");
        return Map.of("ok", true, "message", "已回滚：主实例不变，canary 销毁");
    }

    public Map<String, Object> promote(String name) {
        AppDef a = require(name);
        Desc d = desc(a);
        if (!"UP".equals(canaryStatus.get(name))) throw new IllegalStateException("canary 未通过健康门（需 UP），不可晋升");
        if (ops.containsKey(name)) throw new IllegalStateException("该应用已有发布操作进行中");
        ops.put(name, new Op("promote", System.currentTimeMillis()));
        canaryStatus.put(name, "PROMOTING");
        record(name, "灰度晋升", "已下发", "停主→启主(新jar)→销毁canary");
        Thread w = new Thread(() -> {
            String result = "成功"; StringBuilder detail = new StringBuilder();
            try {
                apps.stop(name);
                waitPort(a.port(), false, 240);
                detail.append("主实例已停；");
                apps.start(name);
                boolean up = waitPort(a.port(), true, 300);
                detail.append(up ? "主实例已用新 jar 启动；" : "主实例启动超时；");
                ProcUtil.killByPort(a.port() + d.offset());
                canaryStatus.put(name, "IDLE");
                if (!up) result = "失败";
            } catch (Exception e) { result = "失败"; detail.append("异常: ").append(e.getMessage()); }
            finally { ops.remove(name); }
            record(name, "灰度晋升", result, detail.toString());
        }, "promote-" + name);
        w.setDaemon(true); w.start();
        return Map.of("ok", true, "message", "晋升编排已下发：停主→启主→销毁 canary，状态自动刷新");
    }

    private boolean waitPort(int port, boolean wantOpen, int sec) throws InterruptedException {
        long deadline = System.currentTimeMillis() + sec * 1000L;
        while (System.currentTimeMillis() < deadline) {
            if (ProcUtil.portOpen(port) == wantOpen) return true;
            Thread.sleep(2000);
        }
        return ProcUtil.portOpen(port) == wantOpen;
    }

    public List<Hist> history() { synchronized (lock) { return new ArrayList<>(hist); } }

    private void record(String app, String action, String result, String detail) {
        state.audit(app, action, result, detail);
        synchronized (lock) {
            hist.add(0, new Hist(LocalDateTime.now().format(FMT), app, action, result, detail));
            while (hist.size() > MAX_HIST) hist.remove(hist.size() - 1);
            save();
        }
    }

    private void save() {
        try {
            Files.createDirectories(props.data());
            Path tmp = props.data().resolve("releases.json.tmp");
            mapper.writeValue(tmp.toFile(), Map.of("hist", hist));
            Files.move(tmp, props.data().resolve("releases.json"), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } catch (Exception e) { System.err.println("releases.json 写入失败: " + e.getMessage()); }
    }

    private Desc desc(AppDef a) { return DESCS.getOrDefault(a.name(), new Desc("script", null, null, CANARY_OFFSET)); }
    private AppDef require(String name) {
        AppDef a = Registry.app(name);
        if (a == null) throw new IllegalArgumentException("未知应用: " + name);
        return a;
    }
}