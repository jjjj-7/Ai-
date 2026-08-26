#!/data/data/com.termux/files/usr/bin/sh
# weather <城市> - 当前天气与三天预报 (中文)
CITY=$(printf '%s' "$*" | sed 's/ /%20/g')
[ -z "$CITY" ] && CITY=Beijing
exec curl -fsS --max-time 25 -H "User-Agent: curl/8" "https://wttr.in/${CITY}?lang=zh"
