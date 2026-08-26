#!/data/data/com.termux/files/usr/bin/sh
# battery - 电量与充电状态速查
dumpsys battery 2>/dev/null | grep -E "level|temperature|powered" | head -5 || echo "不可用"
