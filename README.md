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

权威定义 `deploy/middleware-compose.yml`，数据卷 `claim-shared-*`，**停止不删数据**。claim-pro 与 cs-pro 复用同一套实例，只此一份。

| 服务 | 端口 | 控制台 | 使用方 |
|---|---|---|---|
| Temporal + UI | 7233 | :8088 | claim-pro |
| RocketMQ NameServer | 9876 | — | claim-pro / cs-pro |
| RocketMQ Broker | 10911 | — | 同上（自动创建 Topic） |
| RocketMQ Dashboard | 19876 | :19876 | 运维可视化 |
| MySQL 8 | 3306 | — | claim-pro / cs-pro（初始库 claim） |
| Redis 7 | 6379 | — | claim-pro / cs-pro（可选） |
| MinIO | 9000 | :9001 | claim-pro 证据影像（镜像拉取受网络限制时暂缺，不阻塞 local 模式） |

敏感口令放仓库外：

```bash
mkdir -p ~/.config/ops-pro && chmod 700 ~/.config/ops-pro
cp config/ops.properties.example ~/.config/ops-pro/ops.properties
chmod 600 ~/.config/ops-pro/ops.properties   # 填 MySQL/MinIO 口令
```

## 功能与 API

- **总览**：应用/中间件运行比、Docker 环境状态、共享复用拓扑、实时操作动态。
- **应用管理**：`GET /api/apps`、`POST /api/apps/{name}/start|stop`（按各仓库 run.sh/stop.sh，端口探测判活）。
- **共享中间件**：`GET /api/middleware`、`POST /api/middleware/start-all|stop-all|{name}/start|stop`、`GET /api/docker-status`。
- **日志中心**：`GET /api/logs/sources`、`GET /api/logs?source=&file=&tail=`、`GET /api/logs/stream`（SSE 实时尾随）。
- **操作审计**：`GET /api/audit`；全部启停操作持久化 data/state.json（上限 500 条），失败带完整报错。

## 实现要点

- `DockerService`：docker bin 多路径探测；`composeEnv()` 把 docker bin 目录补进子进程 PATH（nohup 进程缺 `~/.docker/bin` 会导致 credential helper 找不到）。
- `ProcUtil`：进程执行统一超时/输出捕获；应用停止按端口找 PID，优雅 destroy 超时再强杀。
- `StateStore`：审计与状态 JSON 持久化，重启不丢。

## 约束

- 端口 8098（与 claim 8097/cs 8096/ride 8090 系/payment 8080 系/yiwu 8095 错开）。
- 中间件停止不删数据；删卷/重置须用户明确确认。
- 密钥一律仓库外 `~/.config/ops-pro/`（700/600），仓库只留 example。