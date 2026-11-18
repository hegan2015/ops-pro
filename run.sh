#!/bin/bash
# ops-pro 运维控制台一键编译并启动（端口 8098）。
set -e
cd "$(dirname "$0")"

mkdir -p run logs data/proc config deploy

SEC_DIR="$HOME/.config/ops-pro"
SEC_FILE="$SEC_DIR/ops.properties"
mkdir -p "$SEC_DIR"
chmod 700 "$SEC_DIR"
if [ ! -f "$SEC_FILE" ]; then
  MYSQL_PWD="Ops$(openssl rand -hex 12)"
  MINIO_PWD="Ops$(openssl rand -hex 12)"
  {
    echo "mysql.root.password=$MYSQL_PWD"
    echo "minio.root.user=minioadmin"
    echo "minio.root.password=$MINIO_PWD"
  } > "$SEC_FILE"
  chmod 600 "$SEC_FILE"
  echo "==> 已生成密钥文件 $SEC_FILE（权限 600）"
else
  chmod 600 "$SEC_FILE"
fi

if [ -f run/ops-pro.pid ] && kill -0 "$(cat run/ops-pro.pid)" 2>/dev/null; then
  echo "==> ops-pro 已在运行 (pid $(cat run/ops-pro.pid))，如需重启请先 ./stop.sh"
  exit 0
fi

if [ "$1" != "--no-build" ]; then
  echo "==> 编译打包（Maven Wrapper）..."
  ./mvnw -q package -DskipTests
fi

nohup java -jar target/ops-pro.jar > logs/ops-pro.log 2>&1 &
echo $! > run/ops-pro.pid
echo "==> 启动 ops-pro (pid $!)"

for ((i=0; i<60; i++)); do
  if curl -sf -o /dev/null --max-time 2 "http://127.0.0.1:8098/api/docker-status"; then
    echo "==> ops-pro 就绪"
    echo "==> 运维控制台: http://127.0.0.1:8098/"
    exit 0
  fi
  sleep 1
done
echo "!! ops-pro 在 60s 内未就绪，查看 logs/ops-pro.log"
exit 1