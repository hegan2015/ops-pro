#!/bin/bash
# 停止 ops-pro：SIGTERM + 15s 等待 + SIGKILL 兜底。数据保留在 data/。
cd "$(dirname "$0")"
f="run/ops-pro.pid"
if [ ! -f "$f" ]; then
  echo "==> 无 pid 文件，尝试按端口 8098 清理"
else
  pid=$(cat "$f")
  rm -f "$f"
  if kill -0 "$pid" 2>/dev/null; then
    kill "$pid" 2>/dev/null
    echo "==> 停止 ops-pro (pid $pid)"
    for ((i=0; i<15; i++)); do
      kill -0 "$pid" 2>/dev/null || break
      sleep 1
    done
    kill -0 "$pid" 2>/dev/null && kill -9 "$pid" 2>/dev/null && echo "==> 强制结束残留进程 (pid $pid)"
  else
    echo "==> 进程 $pid 已不存在"
  fi
fi
for p in $(lsof -nP -iTCP:8098 -sTCP:LISTEN -t 2>/dev/null); do
  kill "$p" 2>/dev/null && echo "==> 按端口兜底结束进程 $p"
done
echo "==> 已停止。审计与状态保留在 data/state.json。"