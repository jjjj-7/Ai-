#!/data/data/com.termux/files/usr/bin/sh
# mycmds - 列出所有自定义命令及用途
D="$HOME/bin"
[ -d "$D" ] || { echo "尚无自定义命令目录"; exit 0; }
found=0
for f in "$D"/*; do
  [ -f "$f" ] || continue
  found=1
  n=$(basename "$f")
  desc=$(head -5 "$f" 2>/dev/null | grep -m1 "^# " | sed 's/^# //')
  if [ -x "$f" ]; then mark=""; else mark="(未加执行权限)"; fi
  printf "%-14s %s %s\n" "$n" "${desc:--}" "$mark"
done
[ "$found" = "0" ] && echo "~/bin 为空, 用「创造指令」技能让 AI 造第一个命令"
