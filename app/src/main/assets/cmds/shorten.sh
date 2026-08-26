#!/data/data/com.termux/files/usr/bin/sh
# shorten <长链接> - 生成 is.gd 短链
[ -z "$1" ] && { echo "用法: shorten <URL>"; exit 1; }
U=$(printf '%s' "$1" | sed 's/ /%20/g')
R=$(curl -fsS --max-time 20 "https://is.gd/create.php?format=simple&url=${U}") \
  && echo "$R" \
  || echo "生成失败: 检查链接格式与网络"
