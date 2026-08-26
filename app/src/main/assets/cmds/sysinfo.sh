#!/data/data/com.termux/files/usr/bin/sh
# sysinfo - 设备档案大全
echo "== 设备 =="
getprop ro.product.brand; getprop ro.product.model
getprop ro.build.version.release | tr '\n' ' '; echo "(Android)"
echo "== CPU =="
getprop ro.product.cpu.abi; printf "核心数: "; nproc
echo "== 内存 =="
free -h | head -2
echo "== 存储 =="
df -h /storage/emulated/0 2>/dev/null | tail -1
echo "== 电量 =="
dumpsys battery 2>/dev/null | grep level | head -1
echo "== 内核 =="
uname -mnr
echo "== 本机IP (局域网) =="
ip -4 addr show 2>/dev/null | grep inet | grep -v 127.0.0.1 | head -3
