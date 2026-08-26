#!/data/data/com.termux/files/usr/bin/sh
# joblog <名字> [行数] - 查看后台任务日志; joblog -l 列出全部任务及运行状态
D="$HOME/jobs"
if [ "$1" = "-l" ]; then
  [ -d "$D" ] || { echo "暂无后台任务"; exit 0; }
  for p in "$D"/*.pid; do
    [ -f "$p" ] || continue
    n=$(basename "$p" .pid); pid=$(cat "$p")
    if kill -0 "$pid" 2>/dev/null; then st="运行中"; else st="已结束(exit见log尾部)"; fi
    printf "%-16s [%s] %s\n" "$n" "$st" "pid=$pid"
  done
  exit 0
fi
[ -z "$1" ] && { echo "用法: joblog <名字> [行数] 或 joblog -l"; exit 1; }
L="${2:-40}"
tail -n "$L" "$D/$1.log" 2>/dev/null || echo "任务 $1 不存在"
PID=$(cat "$D/$1.pid" 2>/dev/null)
if [ -n "$PID" ] && kill -0 "$PID" 2>/dev/null; then echo "[仍在运行 pid=$PID]"; fi
