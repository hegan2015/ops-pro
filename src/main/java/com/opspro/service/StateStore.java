package com.opspro.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class StateStore {

    public record AuditEntry(String time, String target, String action, String result, String detail) {}

    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final int MAX_AUDIT = 500;

    private final OpsProps props;
    private final ObjectMapper mapper = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
    private final List<AuditEntry> audit = new ArrayList<>();
    private final Object lock = new Object();

    public StateStore(OpsProps props) { this.props = props; }

    @PostConstruct
    @SuppressWarnings("unchecked")
    void load() {
        Path file = props.data().resolve("state.json");
        if (!Files.isRegularFile(file)) return;
        try {
            Map<String, Object> root = mapper.readValue(file.toFile(), Map.class);
            List<Map<String, Object>> list = (List<Map<String, Object>>) root.getOrDefault("audit", List.of());
            for (Map<String, Object> m : list) {
                audit.add(new AuditEntry(str(m.get("time")), str(m.get("target")), str(m.get("action")),
                        str(m.get("result")), str(m.get("detail"))));
            }
        } catch (Exception e) {
            System.err.println("state.json 读取失败，使用空状态启动（原文件保留）: " + e.getMessage());
        }
    }

    private static String str(Object o) { return o == null ? "" : o.toString(); }

    public void audit(String target, String action, String result, String detail) {
        synchronized (lock) {
            audit.add(new AuditEntry(LocalDateTime.now().format(FMT), target, action, result, detail));
            while (audit.size() > MAX_AUDIT) audit.remove(0);
            save();
        }
    }

    public List<AuditEntry> auditList() {
        synchronized (lock) {
            List<AuditEntry> copy = new ArrayList<>(audit);
            Collections.reverse(copy);
            return copy;
        }
    }

    private void save() {
        try {
            Files.createDirectories(props.data());
            Map<String, Object> root = new LinkedHashMap<>();
            root.put("audit", audit);
            Path tmp = props.data().resolve("state.json.tmp");
            mapper.writeValue(tmp.toFile(), root);
            Files.move(tmp, props.data().resolve("state.json"), StandardCopyOption.REPLACE_EXISTING);
        } catch (Exception e) {
            System.err.println("state.json 写入失败: " + e.getMessage());
        }
    }
}