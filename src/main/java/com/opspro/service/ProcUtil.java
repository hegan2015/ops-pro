package com.opspro.service;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

public final class ProcUtil {

    public static boolean portOpen(int port) {
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress("127.0.0.1", port), 500);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    public static List<Long> pidsByPort(int port) {
        List<Long> pids = new ArrayList<>();
        for (String line : execCapture(List.of("lsof", "-nP", "-iTCP:" + port, "-sTCP:LISTEN", "-t"), null, 5).split("\n")) {
            try { pids.add(Long.parseLong(line.trim())); } catch (NumberFormatException ignored) {}
        }
        return pids;
    }

    public static String execCapture(List<String> cmd, File dir, long timeoutSec) {
        try {
            ProcessBuilder pb = new ProcessBuilder(cmd).redirectErrorStream(true);
            if (dir != null) pb.directory(dir);
            Process p = pb.start();
            StringBuilder sb = new StringBuilder();
            try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) sb.append(line).append('\n');
            }
            if (!p.waitFor(timeoutSec, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                return sb.toString();
            }
            return sb.toString();
        } catch (Exception e) {
            return "";
        }
    }

    public static int execWait(List<String> cmd, File dir, Map<String, String> env, Path outFile, long timeoutSec)
            throws Exception {
        ProcessBuilder pb = new ProcessBuilder(cmd).directory(dir).redirectErrorStream(true);
        if (env != null) pb.environment().putAll(env);
        if (outFile != null) {
            Files.createDirectories(outFile.getParent());
            pb.redirectOutput(ProcessBuilder.Redirect.appendTo(outFile.toFile()));
        }
        Process p = pb.start();
        if (outFile == null) p.getInputStream().readAllBytes();
        if (!p.waitFor(timeoutSec, TimeUnit.SECONDS)) {
            p.destroyForcibly();
            return -1;
        }
        return p.exitValue();
    }

    public record ExecResult(int code, String output) {}

    public static ExecResult exec(List<String> cmd, File dir, Map<String, String> env, long timeoutSec) {
        try {
            ProcessBuilder pb = new ProcessBuilder(cmd).redirectErrorStream(true);
            if (dir != null) pb.directory(dir);
            if (env != null) pb.environment().putAll(env);
            Process p = pb.start();
            StringBuilder sb = new StringBuilder();
            Thread reader = new Thread(() -> {
                try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = r.readLine()) != null) sb.append(line).append('\n');
                } catch (Exception ignored) {}
            });
            reader.setDaemon(true);
            reader.start();
            if (!p.waitFor(timeoutSec, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                reader.join(1000);
                return new ExecResult(-1, sb.toString());
            }
            reader.join(2000);
            return new ExecResult(p.exitValue(), sb.toString());
        } catch (Exception e) {
            return new ExecResult(-1, e.getMessage() == null ? e.toString() : e.getMessage());
        }
    }

    public static Process startDetached(List<String> cmd, File dir, Map<String, String> env, Path outFile)
            throws Exception {
        ProcessBuilder pb = new ProcessBuilder(cmd).directory(dir).redirectErrorStream(true);
        if (env != null) pb.environment().putAll(env);
        Files.createDirectories(outFile.getParent());
        pb.redirectOutput(ProcessBuilder.Redirect.appendTo(outFile.toFile()));
        return pb.start();
    }

    public static boolean pidAlive(long pid) {
        return ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false);
    }

    public static void killPid(long pid) {
        ProcessHandle.of(pid).ifPresent(h -> {
            h.destroy();
            try {
                h.onExit().get(8, TimeUnit.SECONDS);
            } catch (Exception e) {
                h.destroyForcibly();
            }
        });
    }

    public static void killByPort(int port) {
        for (long pid : pidsByPort(port)) killPid(pid);
    }

    public static String javaHome() { return System.getProperty("java.home"); }

    private ProcUtil() {}
}