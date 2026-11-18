package com.opspro.model;

import java.nio.file.Path;
import java.util.List;

public final class Registry {

    public static final Path QODER_ROOT = Path.of("/Users/hegan/Documents/Qoder");

    public record AppDef(String name, String label, String dir, String startScript, String stopScript,
                         int port, String logDir) {
        public Path dirPath() { return Path.of(dir); }
        public Path logPath() { return Path.of(logDir); }
        public boolean installed() { return dirPath().resolve(startScript).toFile().isFile(); }
    }

    public record MwDef(String name, String label, int port, Integer consolePort, String endpoint,
                        List<String> usedBy, List<String> composeServices, String desc) {}

    public static final List<AppDef> APPS = List.of(
            new AppDef("claim-pro", "保险理赔多智能体", QODER_ROOT.resolve("claim-pro").toString(),
                    "run.sh", "stop.sh", 8097, QODER_ROOT.resolve("claim-pro/logs").toString()),
            new AppDef("cs-pro", "智能客服", QODER_ROOT.resolve("cs-pro").toString(),
                    "run.sh", "stop.sh", 8096, QODER_ROOT.resolve("cs-pro/logs").toString()),
            new AppDef("ride-pro", "网约车平台", QODER_ROOT.resolve("ride-pro").toString(),
                    "run.sh", "stop.sh", 8090, QODER_ROOT.resolve("ride-pro/logs").toString()),
            new AppDef("payment-pro", "支付平台", QODER_ROOT.resolve("payment-pro").toString(),
                    "run.sh", "stop.sh", 8080, QODER_ROOT.resolve("payment-pro/logs").toString()),
            new AppDef("yiwu-pro", "义乌商城", QODER_ROOT.resolve("yiwu-pro").toString(),
                    "start.sh", "stop.sh", 8095, QODER_ROOT.resolve("yiwu-pro/logs").toString())
    );

    public static final List<MwDef> MIDDLEWARE = List.of(
            new MwDef("temporal", "Temporal 工作流", 7233, 8088, "127.0.0.1:7233",
                    List.of("claim-pro"), List.of("temporal", "temporal-ui"),
                    "理赔长流程编排引擎，Web UI 端口 8088"),
            new MwDef("rocketmq-namesrv", "RocketMQ NameServer", 9876, null, "127.0.0.1:9876",
                    List.of("claim-pro", "cs-pro", "payment-pro"), List.of("rocketmq-namesrv"),
                    "消息路由注册中心"),
            new MwDef("rocketmq-broker", "RocketMQ Broker", 10911, null, "127.0.0.1:10911",
                    List.of("claim-pro", "cs-pro", "payment-pro"), List.of("rocketmq-broker"),
                    "消息存储与转发，已开启自动创建 Topic"),
            new MwDef("rocketmq-dashboard", "RocketMQ 控制台", 19876, 19876, "127.0.0.1:19876",
                    List.of("claim-pro", "ops-pro"), List.of("rocketmq-dashboard"),
                    "消息队列可视化运维界面"),
            new MwDef("mysql", "MySQL 数据库", 3306, null, "127.0.0.1:3306",
                    List.of("claim-pro", "cs-pro", "payment-pro", "ride-pro"), List.of("mysql"),
                    "单实例多库共享：claim / cs_pro / pp_*（支付按域分库）/ ride_*（打车按域分库，docker 模式）"),
            new MwDef("redis", "Redis 缓存", 6379, null, "127.0.0.1:6379",
                    List.of("claim-pro", "cs-pro（可选）", "payment-pro"), List.of("redis"),
                    "缓存与分布式锁/风控限流，cs-pro 可选复用"),
            new MwDef("minio", "MinIO 对象存储", 9000, 9001, "127.0.0.1:9000",
                    List.of("claim-pro"), List.of("minio"),
                    "理赔单证影像存储，控制台端口 9001"),
            new MwDef("zookeeper", "ZooKeeper 注册中心", 2181, null, "127.0.0.1:2181",
                    List.of("payment-pro"), List.of("zookeeper"),
                    "Dubbo 服务注册发现（payment-pro 由内嵌 ZK 迁此）"),
            new MwDef("zookeeper-ride", "ZooKeeper 注册中心（打车）", 2182, null, "127.0.0.1:2182",
                    List.of("ride-pro"), List.of("zookeeper-ride"),
                    "Dubbo 服务注册发现（ride-pro docker 模式；默认内嵌 ZK 双模式保留）")
    );

    public static AppDef app(String name) {
        return APPS.stream().filter(a -> a.name().equals(name)).findFirst().orElse(null);
    }

    public static MwDef mw(String name) {
        return MIDDLEWARE.stream().filter(m -> m.name().equals(name)).findFirst().orElse(null);
    }

    private Registry() {}
}