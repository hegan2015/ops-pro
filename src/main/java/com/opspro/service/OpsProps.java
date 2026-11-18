package com.opspro.service;

import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

@Component
public class OpsProps {

    private final Properties props = new Properties();
    private final Path home = Path.of(System.getProperty("ops.home", System.getProperty("user.dir"))).toAbsolutePath();

    public OpsProps() {
        Path secret = Path.of(System.getProperty("user.home"), ".config", "ops-pro", "ops.properties");
        if (Files.isRegularFile(secret)) {
            try (InputStream in = Files.newInputStream(secret)) {
                props.load(in);
            } catch (IOException ignored) {
            }
        }
    }

    public Path home() { return home; }
    public Path data() { return home.resolve("data"); }
    public Path procLogDir() { return data().resolve("proc"); }
    public Path composePath() { return home.resolve("deploy").resolve("middleware-compose.yml"); }

    public String get(String key, String def) {
        String sys = System.getProperty(key);
        if (sys != null) return sys;
        return props.getProperty(key, def);
    }

    public String dockerBin() { return get("docker.bin", ""); }

    public String mysqlPassword() { return get("mysql.root.password", "ClaimShared_2026"); }
    public String minioUser() { return get("minio.root.user", "minioadmin"); }
    public String minioPassword() { return get("minio.root.password", "ClaimShared_2026"); }
    public String xxljobDbPassword() { return get("xxljob.db.password", "ClaimShared_2026"); }
    public String xxljobAccessToken() { return get("xxljob.access.token", "default_token"); }

    public void ensureDirs() throws IOException {
        Files.createDirectories(procLogDir());
    }
}