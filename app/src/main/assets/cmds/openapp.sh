#!/data/data/com.termux/files/usr/bin/sh
# openapp <关键词> - 模糊匹配并打开手机应用 (如: openapp 微信 / openapp taobao)
K=$(printf '%s' "$*" | tr 'A-Z' 'a-z')
[ -z "$K" ] && { echo "用法: openapp <应用名关键词>"; exit 1; }
PKG=$(pm list packages -3 2>/dev/null | sed 's/^package://' | grep -i "$K" | head -1)
if [ -z "$PKG" ]; then
  PKG=$(pm list packages 2>/dev/null | sed 's/^package://' | grep -i "$K" | head -1)
fi
if [ -z "$PKG" ]; then
  echo "未找到匹配 '${K}' 的应用。已装第三方应用:"
  pm list packages -3 2>/dev/null | sed 's/^package:/  /'
  exit 1
fi
ACT=$(cmd package resolve-activity --brief "$PKG" 2>/dev/null | tail -1)
echo "启动: $PKG"
if [ -n "$ACT" ]; then
  am start -n "$ACT" >/dev/null 2>&1 && exit 0
fi
monkey -p "$PKG" -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1 && exit 0
echo "启动失败: 该应用可能无可用入口 Activity"
exit 1
