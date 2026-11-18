# ops-pro 协作指南 —— 用户指导 AI 开发要点复盘

> 整理日期：2026-11-18 ｜ 来源：本项目立项到交付的真实协作记录复盘
> 用途：新会话开工前读本文件，对齐已拍板决策与踩坑规则。配套 README.md。

## 一、项目由来（用户原话 → 落地）

| 用户指导 | 落地结果 |
|---|---|
| 「方便运维你还要给我做一套运维系统，方便启动中间件与应用与查看日志」 | 独立运维控制台（8098）：应用启停 + 中间件管理 + 日志中心（SSE 实时）+ 操作审计 |
| 「中间件与智能客服要复用」 | 共享中间件栈 claim-shared：claim-pro 与 cs-pro 共用同一套 MySQL/RocketMQ/Redis，只此一份，页面展示复用拓扑 |
| 「中间件都用 docker 管理」 | 权威定义 deploy/middleware-compose.yml，Docker Compose 统一启停；**停止不删数据**（命名卷 claim-shared-*） |
| 「dock desktop 是做什么现在要安装 是必须得吗」→「装」 | 先通俗解释再安装 Docker Desktop 4.99.0；未装/未启动时页面给安装指引，应用管理不受影响 |

## 二、已拍板决策（勿回退）

1. **权威定义唯一**：中间件以本仓库 `deploy/middleware-compose.yml` 为准（项目名 claim-shared）；claim-pro 内的 compose 只是同定义备用版。cs-pro 会话也在此栈上操作，改动前先查现状。
2. **密钥出仓库**：MySQL/MinIO 口令放 `~/.config/ops-pro/ops.properties`（700/600），仓库只留 example；compose 经环境变量注入，不落命令行明文。
3. **审计持久化**：所有启停操作写 data/state.json（上限 500 条），重启不丢；失败带完整报错详情。
4. **应用注册表**：claim/cs/ride/payment/yiwu 五应用按各自仓库 run.sh/stop.sh 启停，端口探测判活，不侵入应用代码。
5. **git 听指令**：提交/推送须用户明示；不提交 data/logs/密钥。

## 三、纠正与教训（踩坑 → 规则）

| 事件 | 确立的规则 |
|---|---|
| compose 拉镜像报 `docker-credential-desktop not found in $PATH` | Docker Desktop CLI 与 credential helper 在 ~/.docker/bin，nohup 启动的 Java 进程 PATH 里没有 → **DockerService.composeEnv() 必须把 docker bin 目录补进子进程 PATH**（2026-11-18 已修复） |
| minio 镜像五个渠道全失败 | Docker Hub 直连 context deadline exceeded；daocloud denied；1ms.run/rat.dev 无 minio 仓库。属网络限制非代码问题；不阻塞 claim local 模式（LocalFsEvidenceStore）。网络恢复后页面点 minio「启动」即可 |
| 直接 CLI pull 与 compose pull 报错不同 | 排障先看审计详情里的真实报错，区分凭据问题与网络问题，勿混为一谈 |
| 本机 shell 默认 PATH 无 docker | 终端手动操作先 `export PATH="$HOME/.docker/bin:$PATH"` |

## 四、验证记录（2026-11-18 交付口径）

- API curl 全通：apps / middleware / docker-status / audit / 静态页。
- 浏览器结构化验收：总览（3/5 应用、6/7 中间件、Docker 就绪）/共享中间件（7 服务卡片+启停按钮）/日志中心（6 日志源+文件列表）/操作审计（14 条持久化）四页渲染正常。
- 中间件 6/7 RUNNING：temporal(+UI)/rocketmq-namesrv/broker/dashboard/mysql/redis；minio 待网络恢复。
- cs-pro 会话已在本栈完成 H2→容器 MySQL 合入与双实例验证（跨会话复用实证）。

## 五、通用协作原则

与 ride/payment/yiwu/cs/claim 一致：开箱即用、方案拍板制、数据保护（删/重置先确认）、密钥出仓库、实测文化、中文教学式回答、并行会话复查、git 听指令。详见 claim-pro/COLLABORATION.md 第六节。