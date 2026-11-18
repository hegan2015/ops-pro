# 从源码构建 MinIO 镜像（上游 2025 起停止社区版预编译分发，全渠道拉取失败时的兜底）。
# 产物打标 minio/minio:RELEASE.2024-06-13T22-53-53Z，与 middleware-compose.yml 完全兼容。
# 构建：docker build -f minio-from-source.Dockerfile -t minio/minio:RELEASE.2024-06-13T22-53-53Z .
# 依赖通道：golang 基础镜像经 docker.m.daocloud.io；源码 github.com；Go 模块 goproxy.cn。
FROM docker.m.daocloud.io/library/golang:1.22-alpine AS build
RUN apk add --no-cache git
ENV GOPROXY=https://goproxy.cn,direct
WORKDIR /src
RUN git clone --depth 1 --branch RELEASE.2024-06-13T22-53-53Z https://github.com/minio/minio .
RUN CGO_ENABLED=0 go build -trimpath \
    -ldflags "-s -w -X github.com/minio/minio/cmd.Version=RELEASE.2024-06-13T22-53-53Z" \
    -o /out/minio .

FROM docker.m.daocloud.io/library/alpine:3.20
RUN apk add --no-cache ca-certificates curl
COPY --from=build /out/minio /usr/bin/minio
EXPOSE 9000 9001
ENTRYPOINT ["minio"]
CMD ["server", "/data", "--console-address", ":9001"]