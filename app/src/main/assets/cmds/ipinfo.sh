#!/data/data/com.termux/files/usr/bin/sh
# ipinfo - 本机公网 IP 与归属地速查
curl -fsS --max-time 15 "https://ipinfo.io/json" || echo "查询失败"
