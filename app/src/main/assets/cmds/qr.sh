#!/data/data/com.termux/files/usr/bin/sh
# qr <文本或链接> - 在终端显示可扫描的二维码
[ -z "$1" ] && { echo "用法: qr <文本或URL>"; exit 1; }
D=$(printf '%s' "$*" | sed 's/ /%20/g; s/&/%26/g; s/?/%3F/g')
exec curl -fsS --max-time 20 "https://qrenco.de/${D}"
