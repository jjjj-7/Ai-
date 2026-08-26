#!/data/data/com.termux/files/usr/bin/sh
# runbg <名字> <命令...> - 把耗时任务放到后台运行 (apt安装/编译/大下载), 立即返回
[ $# -lt 2 ] && { echo "用法: runbg <名字> <命令> [参数...]"; exit 1; }
mkdir -p "$HOME/jobs"
N="$1"; shift
case "$N" in */*|*" "*|""|.|..) echo "非法任务名"; exit 1;; esac
nohup sh -c "$*" >"$HOME/jobs/$N.log" 2>&1 &
echo $! > "$HOME/jobs/$N.pid"
echo "已后台启动 [$N] pid=$(cat "$HOME/jobs/$N.pid")  日志: ~/jobs/$N.log"
