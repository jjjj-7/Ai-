#!/data/data/com.termux/files/usr/bin/sh
# jobwait <名字> [超时秒,默认60] - 等待后台任务结束, 返回其退出码
[ -z "$1" ] && { echo "用法: jobwait <名字> [秒]"; exit 1; }
P="$HOME/jobs/$1.pid"
T="${2:-60}"
[ -f "$P" ] || { echo "任务 $1 不存在"; exit 2; }
PID=$(cat "$P")
i=0
while kill -0 "$PID" 2>/dev/null; do
  [ "$i" -ge "$T" ] && { echo "[等待超时, 任务仍在运行]"; exit 3; }
  sleep 1; i=$((i+1))
done
wait "$PID" 2>/dev/null
C=$?
echo "[任务 $1 已完成 exit=$C]"
exit "$C"
