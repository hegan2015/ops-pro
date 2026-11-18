# ops-pro · 本机运维控制台

五应用（claim/cs/ride/payment/yiwu）+ 共享中间件栈的统一运维入口：**应用启停、Docker 中间件管理、日志中心（SSE 实时）、操作审计**。Java 21 + Spring Boot 3.2 单模块，端口 **8098**。

> 新会话请先读 [COLLABORATION.md](COLLABORATION.md)（用户指导要点、已拍板决策与踩坑规则）。

## 快速开始

```bash
./run.sh     # 编译（首次）+ 启动，就绪探测自动化
./stop.sh    # 停止（审计与状态保留在 data/state.json）
```

控制台：http://127.0.0.1:8098/

前置：应用启停无前置；中间件管理需 Docker Desktop（未安装/未启动时页面给出指引，其余功能不受影响）。终端手动用 docker 时先 `export PATH="$HOME/.docker/bin:$PATH"`。

## 共享中间件栈（claim-shared）

权威定义 `deploy/middleware-compose.yml`，数据卷 `claim-shared-*`，**停止不删数据**。claim / cs / payment / ride 四应用复用同一套实例，只此一份。

| 服务 | 端口 | 控制台 | 使用方 |
|---|---|---|---|
| Temporal + UI | 7233 | :8088 | claim-pro |
| RocketMQ NameServer | 9876 | — | claim-pro / cs-pro / payment-pro |
| RocketMQ Broker | 10911 | — | 同上（自动创建 Topic） |
| RocketMQ Dashboard | 19876 | :19876 | 运维可视化 |
| MySQL 8 | 3306 | — | claim-pro / cs-pro / payment-pro / ride-pro（单实例多库：claim / cs_pro / pp_* / ride_*） |
| Redis 7 | 6379 | — | claim-pro / cs-pro（可选）/ payment-pro（风控限流） |
| MinIO | 9000 | :9001 | claim-pro 证据影像（上游已停止预编译分发，镜像由源码构建，见下） |
| ZooKeeper 3.9 | 2181 | — | payment-pro（Dubbo 注册发现，由内嵌 ZK 迁此） |
| ZooKeeper 3.9（打车） | 2182 | — | ride-pro（docker 模式 Dubbo 注册发现；默认内嵌 ZK 双模式保留） |
| XXL-Job 调度中心 2.4.1 | 8094 | :8094/xxl-job-admin | claim-pro（图谱构建 02:30 / 金标回归 03:00，执行器 claim-pro:9999；调度库 xxl_job，DDL 存档 deploy/xxl-job/） |

敏感口令放仓库外：

```bash
mkdir -p ~/.config/ops-pro && chmod 700 ~/.config/ops-pro
cp config/ops.properties.example ~/.config/ops-pro/ops.properties
chmod 600 ~/.config/ops-pro/ops.properties   # 填 MySQL/MinIO/XXL-Job 口令（xxljob.db.password / xxljob.access.token / xxljob.admin.password）
```

payment-pro 按域分库（`pp_*` 9 库）。mysql 卷首次初始化后 initdb.d 不再触发，新环境需显式建库：

```bash
export PATH="$HOME/.docker/bin:$PATH"
docker exec -i claim-shared-mysql sh -c 'mysql -uroot -p"$MYSQL_ROOT_PASSWORD"' < deploy/payment-databases.sql
```

### MinIO 镜像：源码构建（上游下架兜底）

MinIO 官方 2025 起停止社区版预编译分发（CDN 410、quay 401、各镜像源下架），现成镜像拉不到。本仓库提供可复现的源码构建配方 `deploy/minio-from-source.Dockerfile`（golang 构建镜像经 daocloud、源码 github.com、Go 模块 goproxy.cn）：

```bash
export PATH="$HOME/.docker/bin:$PATH"
cd deploy
docker build -f minio-from-source.Dockerfile -t minio/minio:RELEASE.2024-06-13T22-53-53Z .
```

产物与 compose 定义完全兼容，构建后页面点 minio「启动」即可。已验证：S3 SigV4 建桶/传对象/读回/删除往返全过，控制台 :9001 正常。

## 功能与 API

- **总览**：应用/中间件运行比、Docker 环境状态、共享复用拓扑、实时操作动态、运维工具入口（Adminer 数据库管理 :8089、RocketMQ 控制台 :19876、Dubbo Admin 支付 :8086 / 打车 :8087、XXL-Job 调度中心 :8094，端口探活显示状态，新标签页打开）。Dubbo Admin 为独立容器 dubbo-admin-payment/ride（镜像 dubbo-admin:0.6.0，接 claim-shared_default 网络，分别连 ZK 2181/2182；容器内端口 38080，登录 root/root）。XXL-Job 登录 admin，口令见 `~/.config/ops-pro/ops.properties` 的 `xxljob.admin.password`（默认口令已改）。
- **应用管理**：`GET /api/apps`、`POST /api/apps/{name}/start|stop`（按各仓库 run.sh/stop.sh，端口探测判活）。
- **共享中间件**：`GET /api/middleware`、`POST /api/middleware/start-all|stop-all|{name}/start|stop`、`GET /api/docker-status`。
- **日志中心**：`GET /api/logs/sources`、`GET /api/logs?source=&file=&tail=`、`GET /api/logs/stream`（SSE 实时尾随）。
- **操作审计**：`GET /api/audit`；全部启停操作持久化 data/state.json（上限 500 条），失败带完整报错。

## 实现要点

- `DockerService`：docker bin 多路径探测；`composeEnv()` 把 docker bin 目录补进子进程 PATH（nohup 进程缺 `~/.docker/bin` 会导致 credential helper 找不到）。
- `ProcUtil`：进程执行统一超时/输出捕获；应用停止先执行各仓库 stop.sh（**240s 超时**，覆盖 ride 等多进程应用逐个优雅停的最坏耗时），再按端口兜底清理残留。
- `StateStore`：审计与状态 JSON 持久化，重启不丢。

## 约束

- 端口 8098（与 claim 8097/cs 8096/ride 8090 系/payment 8080 系/yiwu 8095 错开）。
- 中间件停止不删数据；删卷/重置须用户明确确认。
- 密钥一律仓库外 `~/.config/ops-pro/`（700/600），仓库只留 example。