package com.opspro.service;

import com.opspro.model.Registry;
import com.opspro.model.Registry.ToolDef;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class ToolService {

    public List<Map<String, Object>> list() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (ToolDef t : Registry.TOOLS) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("name", t.name());
            item.put("label", t.label());
            item.put("desc", t.desc());
            item.put("port", t.port());
            item.put("url", t.url());
            item.put("status", ProcUtil.portOpen(t.port()) ? "RUNNING" : "STOPPED");
            out.add(item);
        }
        return out;
    }
}