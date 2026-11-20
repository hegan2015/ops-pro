package com.opspro.web;

import com.opspro.service.AppService;
import com.opspro.service.DockerService;
import com.opspro.service.LogService;
import com.opspro.service.MiddlewareService;
import com.opspro.service.MonitorService;
import com.opspro.service.ReleaseService;
import com.opspro.service.StateStore;
import com.opspro.service.ToolService;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api")
public class OpsController {

    private final AppService apps;
    private final MiddlewareService middleware;
    private final DockerService docker;
    private final LogService logs;
    private final StateStore state;
    private final ToolService tools;
    private final MonitorService monitor;
    private final ReleaseService release;

    public OpsController(AppService apps, MiddlewareService middleware, DockerService docker,
                         LogService logs, StateStore state, ToolService tools,
                         MonitorService monitor, ReleaseService release) {
        this.apps = apps;
        this.middleware = middleware;
        this.docker = docker;
        this.logs = logs;
        this.state = state;
        this.tools = tools;
        this.monitor = monitor;
        this.release = release;
    }

    @GetMapping("/apps")
    public Object apps() { return apps.list(); }

    @PostMapping("/apps/{name}/start")
    public Object startApp(@PathVariable String name) { return apps.start(name); }

    @PostMapping("/apps/{name}/stop")
    public Object stopApp(@PathVariable String name) { return apps.stop(name); }

    @GetMapping("/middleware")
    public Object middleware() { return middleware.list(); }

    @PostMapping("/middleware/start-all")
    public Object startAll() { return middleware.startAll(); }

    @PostMapping("/middleware/stop-all")
    public Object stopAll() { return middleware.stopAll(); }

    @PostMapping("/middleware/{name}/start")
    public Object startMw(@PathVariable String name) { return middleware.start(name); }

    @PostMapping("/middleware/{name}/stop")
    public Object stopMw(@PathVariable String name) { return middleware.stop(name); }

    @GetMapping("/docker-status")
    public Object dockerStatus() { return docker.status(); }

    @GetMapping("/tools")
    public Object tools() { return tools.list(); }

    @GetMapping("/logs/sources")
    public Object logSources() { return logs.sources(); }

    @GetMapping("/logs")
    public Object logTail(@RequestParam String source, @RequestParam String file,
                          @RequestParam(defaultValue = "200") int tail) {
        return logs.tail(source, file, tail);
    }

    @GetMapping(value = "/logs/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter logStream(@RequestParam String source, @RequestParam String file,
                                @RequestParam(defaultValue = "50") int tail,
                                @RequestParam(required = false) String filter) {
        return logs.stream(source, file, tail, filter);
    }

    @GetMapping("/audit")
    public Object audit() { return state.auditList(); }

    @GetMapping("/monitor")
    public Object monitor() { return monitor.collect(); }

    @GetMapping("/monitor/history")
    public Object monitorHistory(@RequestParam String target) { return monitor.history(target); }

    @PostMapping("/monitor/rules")
    public Object monitorRules(@RequestBody List<MonitorService.Rule> rules) { return monitor.setRules(rules); }

    @PostMapping("/monitor/alerts/clear")
    public Object monitorClearAlerts() { monitor.clearAlerts(); return Map.of("ok", true); }

    @GetMapping("/release")
    public Object release() { return release.list(); }

    @GetMapping("/release/history")
    public Object releaseHistory() { return release.history(); }

    @PostMapping("/release/{name}/canary/start")
    public Object canaryStart(@PathVariable String name, @RequestParam(required = false) String profile) {
        return release.canaryStart(name, profile);
    }

    @PostMapping("/release/{name}/canary/stop")
    public Object canaryStop(@PathVariable String name) { return release.canaryStop(name); }

    @PostMapping("/release/{name}/promote")
    public Object promote(@PathVariable String name) { return release.promote(name); }

    @PostMapping("/release/{name}/rollback")
    public Object rollback(@PathVariable String name) { return release.rollback(name); }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> badRequest(IllegalArgumentException e) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of("ok", false, "message", e.getMessage()));
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<Map<String, Object>> conflict(IllegalStateException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("ok", false, "message", e.getMessage()));
    }
}