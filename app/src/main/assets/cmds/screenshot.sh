#!/data/data/com.termux/files/usr/bin/sh
# screenshot [文件名] - 屏幕截图, 保存到相册 Pictures
mkdir -p /sdcard/Pictures
F="/sdcard/Pictures/Screenshot_$(date +%Y%m%d_%H%M%S).png"
[ -n "$1" ] && F="/sdcard/Pictures/$1.png"
screencap -p "$F" && echo "已保存: $F ($(wc -c < "$F") 字节)" || echo "截屏失败: 系统限制了该操作"
