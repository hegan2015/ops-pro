package com.opspro.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.opspro.model.Registry;
import com.opspro.model.Registry.AppDef;
import jakarta.annotation.PreDestroy;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

@Component
public class LogService {

    public static final String PROC_SOURCE = "ops-proc";
    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.systemDefault());
    private static final int MAX_TAIL = 5000;

    private final OpsProps props;
    private final ObjectMapper mapper = new ObjectMapper();
    private final ScheduledExecutorService poller = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "log-tail-poller");
        t.setDaemon(true);
        return t;
    });
    private final List<TailSession> sessions = new CopyOnWriteArrayList<>();

    private static final class TailSession {
        final SseEmitter emitter;
        final Path file;
        final String filter;
        long pos;
        int ticks;
        volatile boolean dead;

        TailSession(SseEmitter emitter, Path file, String filter, long pos) {
            this.emitter = emitter;
            this.file = file;
            this.filter = filter;
            this.pos = pos;
        }
    }

    public LogService(OpsProps props) {
        this.props = props;
        poller.scheduleWithFixedDelay(this::poll, 1, 1, TimeUnit.SECONDS);
    }

    @PreDestroy
    void shutdown() { poller.shutdownNow(); }

    public List<Map<String, Object>> sources() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (AppDef a : Registry.APPS) out.add(sourceInfo(a.name(), a.label() + " 日志", a.logPath()));
        out.add(sourceInfo(PROC_SOURCE, "ops-pro 进程输出", props.procLogDir()));
        return out;
    }

    private Map<String, Object> sourceInfo(String id, String label, Path dir) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id);
        m.put("label", label);
        m.put("dir", dir.toString());
        m.put("exists", Files.isDirectory(dir));
        List<Map<String, Object>> files = new ArrayList<>();
        if (Files.isDirectory(dir)) {
            try (Stream<Path> s = Files.list(dir)) {
                s.filter(Files::isRegularFile)
                        .filter(p -> !p.getFileName().toString().startsWith("."))
                        .sorted(Comparator.comparingLong((Path p) -> p.toFile().lastModified()).reversed())
                        .limit(100)
                        .forEach(p -> {
                            Map<String, Object> f = new LinkedHashMap<>();
                            f.put("name", p.getFileName().toString());
                            f.put("size", p.toFile().length());
                            f.put("mtime", FMT.format(Instant.ofEpochMilli(p.toFile().lastModified())));
                            files.add(f);
                        });
            } catch (IOException ignored) {}
        }
        m.put("files", files);
        return m;
    }

    public Path resolveFile(String source, String file) {
        Path dir = sourceDir(source);
        if (file == null || file.isBlank()) throw new IllegalArgumentException("缺少 file 参数");
        Path target = dir.resolve(file).normalize();
        if (!target.startsWith(dir.normalize())) throw new IllegalArgumentException("非法文件路径");
        if (!Files.isRegularFile(target)) throw new IllegalArgumentException("文件不存在: " + file);
        return target;
    }

    private Path sourceDir(String source) {
        if (PROC_SOURCE.equals(source)) return props.procLogDir();
        AppDef a = Registry.app(source);
        if (a == null) throw new IllegalArgumentException("未知日志源: " + source);
        return a.logPath();
    }

    public Map<String, Object> tail(String source, String file, int tail) {
        Path p = resolveFile(source, file);
        int n = Math.max(1, Math.min(tail, MAX_TAIL));
        List<String> lines = readTail(p, n);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("source", source);
        m.put("file", file);
        m.put("size", p.toFile().length());
        m.put("mtime", FMT.format(Instant.ofEpochMilli(p.toFile().lastModified())));
        m.put("lines", lines);
        return m;
    }

    static List<String> readTail(Path p, int n) {
        List<String> lines = new ArrayList<>();
        try (RandomAccessFile raf = new RandomAccessFile(p.toFile(), "r")) {
            long len = raf.length();
            if (len == 0) return lines;
            long pos = len;
            int newlines = 0;
            byte[] buf = new byte[8192];
            java.io.ByteArrayOutputStream acc = new java.io.ByteArrayOutputStream();
            while (pos > 0 && newlines <= n) {
                int read = (int) Math.min(buf.length, pos);
                pos -= read;
                raf.seek(pos);
                raf.readFully(buf, 0, read);
                for (int i = 0; i < read; i++) {
                    if (buf[i] == '\n') newlines++;
                }
                byte[] prev = acc.toByteArray();
                acc.reset();
                acc.write(buf, 0, read);
                acc.write(prev, 0, prev.length);
            }
            String[] all = acc.toString(StandardCharsets.UTF_8).split("\n", -1);
            int from = Math.max(0, all.length - n);
            for (int i = from; i < all.length; i++) {
                String line = all[i];
                if (i == all.length - 1 && line.isEmpty()) continue;
                lines.add(line);
            }
        } catch (IOException e) {
            lines.add("[读取失败] " + e.getMessage());
        }
        return lines;
    }

    public SseEmitter stream(String source, String file, int tail, String filter) {
        Path p = resolveFile(source, file);
        int n = Math.max(1, Math.min(tail, 1000));
        SseEmitter emitter = new SseEmitter(0L);
        List<String> initial = readTail(p, n);
        if (filter != null && !filter.isBlank()) {
            String f = filter.toLowerCase();
            initial = initial.stream().filter(l -> l.toLowerCase().contains(f)).toList();
        }
        TailSession session = new TailSession(emitter, p, filter == null ? "" : filter, p.toFile().length());
        sessions.add(session);
        emitter.onCompletion(() -> session.dead = true);
        emitter.onTimeout(() -> session.dead = true);
        emitter.onError(e -> session.dead = true);
        try {
            emitter.send(SseEmitter.event().name("init").data(mapper.writeValueAsString(Map.of(
                    "file", file, "lines", initial, "size", p.toFile().length())), org.springframework.http.MediaType.APPLICATION_JSON));
        } catch (Exception e) {
            session.dead = true;
        }
        return emitter;
    }

    private void poll() {
        for (TailSession s : sessions) {
            if (s.dead) { sessions.remove(s); continue; }
            try {
                long len = s.file.toFile().length();
                if (len < s.pos) {
                    s.pos = 0;
                    s.emitter.send(SseEmitter.event().name("reset").data("{}"));
                }
                if (len > s.pos) {
                    long from = s.pos;
                    long to = Math.min(len, from + 512 * 1024);
                    byte[] buf = new byte[(int) (to - from)];
                    try (RandomAccessFile raf = new RandomAccessFile(s.file.toFile(), "r")) {
                        raf.seek(from);
                        raf.readFully(buf);
                    }
                    s.pos = to;
                    String text = new String(buf, StandardCharsets.UTF_8);
                    List<String> lines = new ArrayList<>(List.of(text.split("\n", -1)));
                    if (!lines.isEmpty() && lines.get(lines.size() - 1).isEmpty()) lines.remove(lines.size() - 1);
                    if (!s.filter.isBlank()) {
                        String f = s.filter.toLowerCase();
                        lines.removeIf(l -> !l.toLowerCase().contains(f));
                    }
                    if (!lines.isEmpty()) {
                        s.emitter.send(SseEmitter.event().name("lines")
                                .data(mapper.writeValueAsString(lines), org.springframework.http.MediaType.APPLICATION_JSON));
                    }
                }
                if (++s.ticks % 15 == 0) {
                    s.emitter.send(SseEmitter.event().comment("hb"));
                }
            } catch (Exception e) {
                s.dead = true;
                sessions.remove(s);
                try { s.emitter.complete(); } catch (Exception ignored) {}
            }
        }
    }
}