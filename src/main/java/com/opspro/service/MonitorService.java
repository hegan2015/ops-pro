package com.opspro.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.opspro.model.Registry;
import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 可观测中心：进程指标(ps) + 容器指标(docker stats) + 健康探针延迟 + 告警规则 + 趋势环。
 * 只读采集，不干预被管对象；告警/规则持久化 data/monitor.json（独立于审计 state.json）。
 */
@Component
public class MonitorService {

    public record Rule(String id, String metric, double threshold, boolean enabled) {}
    public record Alert(String time, String target, String metric, String message) {}
    public record Sample(long t, Double cpu, Double mem) {}

    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final int MAX_ALERTS = 200;
    private static final int MAX_TREND = 60;

    private final OpsProps props;
    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofMillis(800)).build();
    private final List<Rule> rules = new ArrayList<>();
    private final List<Alert> alerts = new ArrayList<>();
    private final Map<String, Deque<Sample>> trend = new ConcurrentHashMap<>();
    private final Map<String, Boolean> active = new ConcurrentHashMap<>();
    private volatile Map<String, double[]> dockerCache = new LinkedHashMap<>();
    private volatile long dockerFetchAt = 0;
    private final Object lock = new Object();

    public MonitorService(OpsProps props) { this.props = props; }

    @PostConstruct
    @SuppressWarnings("unchecked")
    void load() {
        rules.add(new Rule("cpu", "cpu", 80, true));
        rules.add(new Rule("mem", "mem", 85, true));
        rules.add(new Rule("down", "down", 0, false));
        Path f = props.data().resolve("monitor.json");
        if (!Files.isRegularFile(f)) return;
        try {
            Map<String, Object> root = mapper.readValue(f.toFile(), Map.class);
            List<Map<String, Object>> rs = (List<Map<String, Object>>) root.getOrDefault("rules", List.of());
            if (!rs.isEmpty()) {
                rules.clear();
                for (Map<String, Object> m : rs)
                    rules.add(new Rule(str(m.get("id")), str(m.get("metric")), num(m.get("threshold")), bool(m.get("enabled"))));
            }
            List<Map<String, Object>> as = (List<Map<String, Object>>) root.getOrDefault("alerts", List.of());
            for (Map<String, Object> m : as)
                alerts.add(new Alert(str(m.get("time")), str(m.get("target")), str(m.get("metric")), str(m.get("message"))));
        } catch (Exception e) {
            System.err.println("monitor.json 读取失败，使用默认规则: " + e.getMessage());
        }
    }

    private static String str(Object o) { return o == null ? "" : o.toString(); }
    private static double num(Object o) { try { return Double.parseDouble(str(o)); } catch (Exception e) { return 0; } }
    private static boolean bool(Object o) { return Boolean.parseBoolean(str(o)); }

    public Map<String, Object> collect() {
        List<Map<String, Object>> targets = new ArrayList<>();
        for (Registry.AppDef a : Registry.APPS) targets.add(appTarget(a));
        Map<String, double[]> docker = dockerStats();
        for (Registry.MwDef mw : Registry.MIDDLEWARE) targets.add(mwTarget(mw, docker));
        evaluate(targets);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("collectedAt", LocalDateTime.now().format(FMT));
        out.put("targets", targets);
        out.put("rules", rules);
        synchronized (lock) { out.put("alerts", new ArrayList<>(alerts)); }
        return out;
    }

    private Map<String, Object> appTarget(Registry.AppDef a) {
        Map<String, Object> m = base(a.name(), a.label(), "app", a.port());
        List<Long> pids = ProcUtil.pidsByPort(a.port());
        boolean up = !pids.isEmpty();
        m.put("status", up ? "RUNNING" : "STOPPED");
        if (up) {
            String csv = String.join(",", pids.stream().map(String::valueOf).toList());
            String ps = ProcUtil.execCapture(List.of("ps", "-o", "pid=,pcpu=,pmem=,rss=,etime=", "-p", csv), null, 5);
            double cpu = 0, mem = 0, rss = 0; String uptime = "";
            for (String line : ps.split("\n")) {
                String[] t = line.trim().split("\\s+");
                if (t.length < 5) continue;
                cpu += parse(t[1]); mem += parse(t[2]); rss += parse(t[3]); uptime = t[4];
            }
            m.put("cpu", round(cpu)); m.put("mem", round(mem));
            m.put("rssMb", round(rss / 1024)); m.put("uptime", uptime); m.put("procs", pids.size());
        }
        probe(m, a.port());
        pushTrend(a.name(), (Double) m.get("cpu"), (Double) m.get("mem"));
        m.put("trend", history(a.name()).stream().map(Sample::cpu).toList());
        return m;
    }

    private Map<String, Object> mwTarget(Registry.MwDef mw, Map<String, double[]> docker) {
        Map<String, Object> m = base(mw.name(), mw.label(), "middleware", mw.port());
        boolean up = ProcUtil.portOpen(mw.port());
        m.put("status", up ? "RUNNING" : "STOPPED");
        double[] d = null;
        for (String svc : mw.composeServices()) {
            d = docker.get("claim-shared-" + svc);
            if (d == null) d = docker.get(svc);
            if (d != null) break;
        }
        if (d != null) { m.put("cpu", round(d[0])); m.put("memMb", round(d[1])); m.put("container", true); }
        pushTrend(mw.name(), (Double) m.get("cpu"), null);
        m.put("trend", history(mw.name()).stream().map(Sample::cpu).toList());
        return m;
    }

    private Map<String, Object> base(String name, String label, String kind, int port) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", name); m.put("label", label); m.put("kind", kind); m.put("port", port);
        return m;
    }

    private void probe(Map<String, Object> m, int port) {
        long t0 = System.nanoTime();
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/actuator/health"))
                    .timeout(Duration.ofMillis(900)).GET().build();
            HttpResponse<String> r = http.send(req, HttpResponse.BodyHandlers.ofString());
            m.put("latencyMs", Math.round((System.nanoTime() - t0) / 1e6));
            m.put("httpCode", r.statusCode());
        } catch (Exception e) {
            m.put("latencyMs", null); m.put("httpCode", null);
        }
    }

    private Map<String, double[]> dockerStats() {
        long now = System.currentTimeMillis();
        if (now - dockerFetchAt < 8000 && !dockerCache.isEmpty()) return dockerCache;
        dockerFetchAt = now;
        Map<String, double[]> out = new LinkedHashMap<>();
        String bin = props.dockerBin().isEmpty() ? "/Users/hegan/.docker/bin/docker" : props.dockerBin();
        String s = ProcUtil.execCapture(List.of(bin, "stats", "--no-stream", "--format", "{{.Name}}\t{{.CPUPerc}}\t{{.MemUsage}}"), null, 15);
        for (String line : s.split("\n")) {
            String[] t = line.split("\t");
            if (t.length < 3) continue;
            double cpu = parse(t[1].replace("%", ""));
            double memMb = parseMem(t[2].split("/")[0].trim());
            out.put(t[0].trim(), new double[]{cpu, memMb});
        }
        dockerCache = out;
        return out;
    }

    private static double parseMem(String s) {
        try {
            double v = Double.parseDouble(s.replaceAll("[^0-9.]", ""));
            if (s.contains("GiB")) return v * 1024;
            if (s.contains("KiB")) return v / 1024;
            return v; // MiB
        } catch (Exception e) { return 0; }
    }

    private static double parse(String s) { try { return Double.parseDouble(s.trim()); } catch (Exception e) { return 0; } }
    private static double round(double v) { return Math.round(v * 10) / 10.0; }

    private void pushTrend(String name, Double cpu, Double mem) {
        Deque<Sample> q = trend.computeIfAbsent(name, k -> new ArrayDeque<>());
        synchronized (q) {
            q.addLast(new Sample(System.currentTimeMillis(), cpu, mem));
            while (q.size() > MAX_TREND) q.removeFirst();
        }
    }

    public List<Sample> history(String target) {
        Deque<Sample> q = trend.get(target);
        if (q == null) return List.of();
        synchronized (q) { return new ArrayList<>(q); }
    }

    private void evaluate(List<Map<String, Object>> targets) {
        for (Map<String, Object> t : targets) {
            String name = str(t.get("name"));
            for (Rule r : rules) {
                if (!r.enabled()) continue;
                boolean breach = false; String msg = "";
                if (r.metric().equals("cpu") && t.get("cpu") != null && (double) t.get("cpu") > r.threshold()) {
                    breach = true; msg = "CPU " + t.get("cpu") + "% > " + r.threshold() + "%";
                } else if (r.metric().equals("mem") && t.get("mem") != null && (double) t.get("mem") > r.threshold()) {
                    breach = true; msg = "内存 " + t.get("mem") + "% > " + r.threshold() + "%";
                } else if (r.metric().equals("down") && "STOPPED".equals(t.get("status"))) {
                    breach = true; msg = "目标停止（端口 " + t.get("port") + " 未监听）";
                }
                String key = name + ":" + r.metric();
                if (breach && active.putIfAbsent(key, true) == null) {
                    synchronized (lock) {
                        alerts.add(0, new Alert(LocalDateTime.now().format(FMT), name, r.metric(), msg));
                        while (alerts.size() > MAX_ALERTS) alerts.remove(alerts.size() - 1);
                    }
                    save();
                } else if (!breach) {
                    active.remove(key);
                }
            }
        }
    }

    public List<Rule> setRules(List<Rule> next) {
        synchronized (lock) {
            rules.clear();
            rules.addAll(next);
            active.clear();
            save();
            return new ArrayList<>(rules);
        }
    }

    public void clearAlerts() { synchronized (lock) { alerts.clear(); active.clear(); save(); } }

    private void save() {
        try {
            Files.createDirectories(props.data());
            Map<String, Object> root = new LinkedHashMap<>();
            root.put("rules", rules);
            root.put("alerts", alerts);
            Path tmp = props.data().resolve("monitor.json.tmp");
            mapper.writeValue(tmp.toFile(), root);
            Files.move(tmp, props.data().resolve("monitor.json"), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } catch (Exception e) {
            System.err.println("monitor.json 写入失败: " + e.getMessage());
        }
    }
}