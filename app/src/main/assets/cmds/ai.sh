#!/data/data/com.termux/files/usr/bin/sh
# ai - 终端原生多智能体 (零依赖: 纯 POSIX sh + curl + awk)
# 用法:
#   ai <总任务>      编排模式: 分解 -> 并行工人 -> 整合 -> 审核 -> 修复
#   ai -w <子任务>   工人模式: 单智能体执行循环 (编排器分裂体)
#   ai               交互输入
set -u

CFG="$HOME/.ai_config.json"
MAXP=3            # 并行工人上限
MAXSTEPS=30       # 单智能体最大轮数
CTMO=300          # 命令超时秒
LTMO=150          # LLM 超时秒
OUTLIM=2200       # 回传输出截断字节

say() { printf '%s\n' "$*"; }
die() { say "ai: $*" >&2; exit 1; }

command -v curl >/dev/null 2>&1 || {
  say "ai: 首次运行补装 curl ..."
  apt-get install -y curl >/dev/null 2>&1 || pkg install -y curl >/dev/null 2>&1 || die "需要 curl, 请手动: pkg install curl"
}
[ -f "$CFG" ] || die "缺少 $CFG — 在 App 设置页保存模型配置后自动生成"

TMPD="${TMPDIR:-$HOME/.cache}"
mkdir -p "$TMPD"

# ---------- JSON 工具 (纯 awk 状态机, 无 python) ----------

# 从 OpenAI 响应提取 message.content 并完整反转义到 stdout
json_content() {
  awk '
    function hexv(c) { return index("0123456789abcdefABCDEF", c) - 1 }
    function fromu(h,   cp) {
      cp = hexv(substr(h,1,1))*4096 + hexv(substr(h,2,1))*256 + hexv(substr(h,3,1))*16 + hexv(substr(h,4,1))
      if (cp < 128) return sprintf("%c", cp)
      if (cp < 2048) return sprintf("%c%c", 192+int(cp/64), 128+cp%64)
      if (cp < 65536) return sprintf("%c%c%c", 224+int(cp/4096), 128+int(cp%4096/64), 128+cp%64)
      return "?"
    }
    { s = s $0 "\n" }
    END {
      i = index(s, "\"content\"")
      if (!i) exit 3
      i += length("\"content\"")
      while (i <= length(s)) {
        c = substr(s, i, 1)
        if (c == ":" || c == " " || c == "\t") { i++; continue }
        break
      }
      if (substr(s, i, 1) != "\"") exit 3
      i++
      while (i <= length(s)) {
        c = substr(s, i, 1)
        if (c == "\\") {
          d = substr(s, i+1, 1)
          if (d == "n") { printf "\n"; i += 2 }
          else if (d == "t") { printf "\t"; i += 2 }
          else if (d == "r") { i += 2 }
          else if (d == "\"") { printf "\""; i += 2 }
          else if (d == "\\") { printf "\\"; i += 2 }
          else if (d == "/") { printf "/"; i += 2 }
          else if (d == "u") { printf "%s", fromu(substr(s, i+2, 4)); i += 6 }
          else { printf "%s", d; i += 2 }
        } else if (c == "\"") exit
        else { printf "%s", c; i++ }
      }
    }'
}

# 提取扁平 JSON 对象字符串字段 (读配置)
json_field() {
  awk -v key="\"$2\"" '
    { s = s $0 }
    END {
      i = index(s, key)
      if (!i) exit 3
      rest = substr(s, i); j = index(rest, ":"); i = i + j - 1
      while (substr(s, i, 1) == ":" || substr(s, i, 1) == " ") i++
      if (substr(s, i, 1) != "\"") exit 3
      i++
      while (i <= length(s)) {
        c = substr(s, i, 1)
        if (c == "\\") {
          d = substr(s, i+1, 1)
          if (d == "n") printf "\n"
          else if (d == "\"") printf "\""
          else if (d == "\\") printf "\\"
          else if (d == "/") printf "/"
          else if (d != "u") printf "%s", d
          i += 2
        } else if (c == "\"") exit
        else { printf "%s", c; i++ }
      }
    }' "$1"
}

# 文本 -> JSON 字符串值(含引号); 有参转参数, 无参读 stdin
_json_esc_awk() {
  awk '
    BEGIN { printf "\"" }
    {
      if (NR > 1) printf "%sn", "\\"
      _line = $0
      n = length(_line)
      for (i = 1; i <= n; i++) {
        c = substr(_line, i, 1)
        if (c == "\\") printf "%s%s", "\\", "\\"
        else if (c == "\"") printf "%s%s", "\\", "\""
        else if (c == "\t") printf "%st", "\\"
        else printf "%s", c
      }
    }
    END { printf "\"" }'
}
json_esc() {
  if [ $# -gt 0 ]; then printf '%s' "$1" | _json_esc_awk; else _json_esc_awk; fi
}

BASE=$(json_field "$CFG" baseUrl)  || die "配置缺 baseUrl"
KEY=$(json_field "$CFG" apiKey)    || die "配置缺 apiKey"
MODEL=$(json_field "$CFG" model)   || die "配置缺 model"

first_word() { printf '%s\n' "$1" | head -n1 | cut -d' ' -f1; }

block_of() { # 去掉首行指令行, 取到 END 行之前的正文
  printf '%s\n' "$1" | sed '1d' | sed -n '/^END$/q;p'
}

mk_sys() { # mk_sys <提示词文本> -> stdout: 写好的临时文件路径
  _f=$(mktemp "$TMPD/aisys.XXXXXX")
  printf '%s\n' "$1" >"$_f"
  echo "$_f"
}

execute_block() { # execute_block body cwd -> stdout "[exit=N] 输出"
  _b="$1" _w="$2"
  _tmp=$(mktemp "$TMPD/aicmd.XXXXXX")
  printf '%s\n' "$_b" >"$_tmp"
  _out=$( { cd "$_w" && timeout "$CTMO" sh "$_tmp"; } 2>&1 )
  _rc=$?
  : >"$_tmp"
  [ ${#_out} -gt "$OUTLIM" ] && _out="$(printf '%s\n' "$_out" | tail -c "$OUTLIM")"
  [ -z "$_out" ] && _out="(无输出)"
  printf '[exit=%d]\n%s' "$_rc" "$_out"
}

# 单轮 LLM 调用 (参数1=系统提示文件; 要求调用方已设 MSGS): stdout 反转义回复
llm_call() {
  _rq=$(mktemp "$TMPD/aireq.XXXXXX")
  {
    printf '{"model":"%s","temperature":0.25,"messages":[\n' "$MODEL"
    printf '{"role":"system","content":%s},\n' "$(json_esc "$(cat "$1")")"
    sed '$!s/$/,/' "$MSGS"
    printf ']}'
  } >"$_rq"
  curl -s --max-time "$LTMO" \
    -H "Authorization: Bearer $KEY" \
    -H "Content-Type: application/json" \
    -d @"$_rq" \
    "$BASE/chat/completions" | json_content
  : >"$_rq"
}

msg_put() { # msg_put role content -> 追加进 $MSGS
  printf '{"role":"%s","content":%s}\n' "$1" "$(json_esc "$2")" >>"$MSGS"
}

WORKER_SYS='你是终端执行代理, 拥有这台 Android 设备的完全控制权。
环境: Termux 用户态, bash/curl/git/apt 可用, ~/bin 在 PATH 首位。
每轮只输出一种格式:
RUN
<任意 shell 命令, 可多行>
END
DONE <任务总结, 含产出文件位置>
规则: 直接干实事; 出错先诊断根因再换方法, 同一命令失败禁止原样重试第二次。'

ORCH_SYS='你是任务编排者。判断总任务如何并行分解。
简单或强串行依赖的任务, 只输出一行: SINGLE
可并行的任务输出:
SPLIT
w1|子任务描述(自包含, 含验收标准)
w2|子任务描述
END
最多 '"$MAXP"' 个工人; 各工人写不同文件, 子任务互相独立。'

MERGE_SYS='你是集成工程师。各工人在 .agents/wX 目录完成开发, 把成果整合进主目录。
每轮格式:
RUN
<cp/rsync/cat/sed 等整合命令, 可多行>
END
DONE <整合说明>
冲突时选更完整的一方并在 DONE 说明。'

REVIEW_SYS='你是严格的技术审核员。根据原始需求检查主目录产出。
通过输出: PASS <一句话结论>
未通过输出:
FAIL
fix1|具体修复指令(含文件路径)
END
轻微瑕疵算 PASS。'

# agent_loop SYSFILE TASK CWD LABEL -> stdout 总结
agent_loop() {
  _sysf="$1" _task="$2" _cwd="$3" _label="$4"
  MSGS=$(mktemp "$TMPD/aimsg.XXXXXX")
  msg_put user "$_task

[工作目录: $_cwd , 所有产出文件放这里]"
  _step=0
  _dup=0
  _prev_r=""
  while :; do
    [ "$_step" -ge "$MAXSTEPS" ] && { printf '%s 步数耗尽\n' "$_label"; break; }
    _step=$((_step+1))
    R=$(llm_call "$_sysf") || { printf '%s 失败: LLM 不可达\n' "$_label"; break; }
    [ -z "$R" ] && { printf '%s 失败: LLM 空响应\n' "$_label"; break; }
    if [ "$R" = "$_prev_r" ]; then
      _dup=$((_dup+1))
      [ "$_dup" -ge 3 ] && { printf '%s 失败: 连续重复响应, 熔断\n' "$_label"; break; }
    else
      _dup=0
      _prev_r="$R"
    fi
    case "$(first_word "$R")" in
      DONE)
        printf '%s\n' "$R" | sed '1s/^DONE *//' | head -c 1200
        break
        ;;
      RUN)
        B=$(block_of "$R")
        if [ -z "$(printf '%s' "$B" | tr -d ' \n')" ]; then
          msg_put assistant "$R"
          msg_put user "[协议] RUN 之后要有命令行, 最后单独一行 END"
          continue
        fi
        say "  [$_label] \$ $(printf '%.80s' "$(printf '%s\n' "$B" | head -n1)")"
        FB=$(execute_block "$B" "$_cwd")
        msg_put assistant "$R"
        msg_put user "$FB"
        ;;
      *)
        msg_put assistant "$R"
        msg_put user "[协议] 第一行只允许 RUN 或 DONE。RUN 格式: 首行 RUN, 命令多行, 末行 END"
        ;;
    esac
  done
  : >"$MSGS"
}

# chat_once SYSFILE USERTEXT TEMPERATURE -> stdout 回复 (单轮无历史)
chat_once() {
  _rq=$(mktemp "$TMPD/aireq.XXXXXX")
  printf '{"model":"%s","temperature":%s,"messages":[{"role":"system","content":%s},{"role":"user","content":%s}]}' \
    "$MODEL" "$3" "$(json_esc "$(cat "$1")")" "$(json_esc "$2")" >"$_rq"
  curl -s --max-time "$LTMO" -H "Authorization: Bearer $KEY" \
    -H "Content-Type: application/json" -d @"$_rq" \
    "$BASE/chat/completions" | json_content
  : >"$_rq"
}

merge_all() { # merge_all task -> stdout 整合说明
  SYSF=$(mk_sys "$MERGE_SYS")
  MSGS=$(mktemp "$TMPD/aimsg.XXXXXX")
  _inv=$(mktemp "$TMPD/aiinv.XXXXXX")
  for d in .agents/w*/; do
    [ -d "$d" ] || continue
    printf '== %s ==\n' "$d" >>"$_inv"
    find "$d" -type f 2>/dev/null | head -30 >>"$_inv"
  done
  msg_put user "原始需求: $1

现有主目录文件:
$(find . -type f -not -path './.agents/*' 2>/dev/null | head -40)

各工人产出清单:
$(cat "$_inv")"
  : >"$_inv"
  _step=0
  while [ "$_step" -lt 12 ]; do
    _step=$((_step+1))
    R=$(llm_call "$SYSF") || break
    [ -z "$R" ] && break
    case "$(first_word "$R")" in
      DONE)
        printf '%s\n' "$R" | sed '1s/^DONE *//' | head -c 1000
        break
        ;;
      RUN)
        B=$(block_of "$R")
        say "[merge] \$ $(printf '%.80s' "$(printf '%s\n' "$B" | head -n1)")"
        FB=$(execute_block "$B" ".")
        msg_put assistant "$R"
        msg_put user "$FB"
        ;;
      *)
        msg_put assistant "$R"
        msg_put user "[协议] 只允许 RUN...END 或 DONE 总结"
        ;;
    esac
  done
  : >"$MSGS"
}

review_all() { # review_all task -> stdout 审核结果
  SYSF=$(mk_sys "$REVIEW_SYS")
  MSGS=$(mktemp "$TMPD/aimsg.XXXXXX")
  _prev=""
  for f in $(find . -maxdepth 2 -type f -not -path './.agents/*' 2>/dev/null | head -5); do
    _prev="$_prev--- $f ---
$(head -c 600 "$f")
"
  done
  msg_put user "原始需求: $1

主目录产出清单:
$(find . -type f -not -path './.agents/*' 2>/dev/null | head -50)

关键文件预览:
$_prev"
  R=$(llm_call "$SYSF")
  if [ -n "${R:-}" ]; then printf '%s\n' "$R"; else echo "PASS (审核跳过)"; fi
  : >"$MSGS"
}

orchestrate() { # orchestrate task
  _task="$1"

  # ---- 计划: 分解或单体 ----
  OSYSF=$(mk_sys "$ORCH_SYS")
  PLAN=$(chat_once "$OSYSF" "$_task" 0.15) || PLAN=""
  SPECS=""
  if [ "$(first_word "$PLAN")" = "SPLIT" ]; then
    SPECS=$(mktemp "$TMPD/aispec.XXXXXX")
    block_of "$PLAN" | grep '|' >"$SPECS"
    [ -s "$SPECS" ] || SPECS=""
  fi

  WSYSF=$(mk_sys "$WORKER_SYS")

  # ---- 执行 ----
  if [ -z "$SPECS" ]; then
    say "» 单体执行"
    agent_loop "$WSYSF" "$_task" "." "main"
  else
    _n=$(grep -c . "$SPECS")
    say "» 分解为 $_n 个并行子任务:"
    grep . "$SPECS" | while IFS='|' read -r id t; do
      say "  - [$id] $(printf '%.60s' "$t")"
    done
    mkdir -p .agents
    _i=0
    while IFS='|' read -r id t; do
      mkdir -p ".agents/$id"
      (
        agent_loop "$WSYSF" "$t" ".agents/$id" "$id" >".agents/$id.summary" 2>&1
      ) &
      _i=$((_i+1))
      [ $((_i % MAXP)) -eq 0 ] && wait
    done <"$SPECS"
    wait
    say "» 全部收工, 开始整合"

    MERGE_OUT=$(merge_all "$_task")

    REV=$(review_all "$_task")
    say "
═══ 结果 ═══
整合: ${MERGE_OUT:-见目录}
审核: $(printf '%s\n' "$REV" | head -n1)"

    # ---- 审核未过 -> 并行修复 ----
    if printf '%s\n' "$REV" | head -n1 | grep -q '^FAIL'; then
      say "» 启动修复工人..."
      FIXSPEC=$(mktemp "$TMPD/aifix.XXXXXX")
      printf '%s\n' "$REV" | sed '1d' | sed -n '/^END$/q;p' | grep '|' | head -$MAXP >"$FIXSPEC"
      while IFS='|' read -r id fix; do
        (
          mkdir -p ".agents/$id"
          agent_loop "$WSYSF" "$fix" ".agents/$id" "$id" >".agents/$id.fixlog" 2>&1
        ) &
      done <"$FIXSPEC"
      wait
      say "修复完成, 请复核。"
    fi
  fi
  :
}

# ---------- 入口 ----------
if [ $# -gt 0 ] && [ "${1:-}" = "-w" ]; then
  shift
  WORKER_MODE=1
else
  WORKER_MODE=0
fi
TASK="$*"
if [ -z "$TASK" ]; then
  printf 'ai> ' >&2
  IFS= read -r TASK
fi
[ -z "$TASK" ] && exit 0

if [ "$WORKER_MODE" = "1" ]; then
  WSYSF=$(mk_sys "$WORKER_SYS")
  agent_loop "$WSYSF" "$TASK" "." "worker"
else
  orchestrate "$TASK"
fi
