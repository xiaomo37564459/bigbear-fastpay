#!/usr/bin/env bash
#
# 生产服务器「正在操作」标记（操作锁）
#
# 干什么用的：让同一时间只有一个人在动生产服务器。
#
# 为什么要有：2026-08-20 那次上线，两路人几乎同时在动同一台生产服务器 ——
# 一路在给 MTM-170 收尾，另一路把 MTM-157 也推了上去，线上服务被重启了一次，
# 重启的人不是当时正在操作的那个人。那次运气好没出事，但一边在换文件、
# 另一边在重启，真出问题会极难排查。（背景：MTM-210）
#
# 怎么用（四条命令，照抄）：
#
#   # 1. 动手之前，先占上
#   /root/fastpay-ops/prod-lock.sh acquire MTM-210 "梁运｜运维" "上线 v1.6.0" 30
#
#   # 2. 每一段改生产的命令，开头先加这一句（占不到就立刻停，不往下走）
#   /root/fastpay-ops/prod-lock.sh check MTM-210 || exit 1
#
#   # 3. 干完了，放开
#   /root/fastpay-ops/prod-lock.sh release MTM-210
#
#   # 随时想看现在有没有人在动
#   /root/fastpay-ops/prod-lock.sh status
#
# ⚠️ 这个脚本不改任何业务数据：不写数据库、不改配置、不重启服务。
#    它只在 /root/fastpay-ops/ 下建一个标记目录和一份操作流水。
#
# ⚠️ 说清楚它的边界：所有人都是用 root 登这台机器的，root 想绕过它随时能绕。
#    所以它拦的是「顺手就上」，不是「铁了心要绕」。它做到的是：
#      - 占锁这个动作本身是原子的，两个人同时占，必然只有一个成功
#      - 谁在动、从什么时候开始动、预计动多久，一条命令看得见
#      - 每一次占/放/抢都写进流水，事后一定查得到是谁
#    真正的强制（把 root 收掉、改成只有一条发布通道能动生产）是更大的改动，
#    需要需求方拍板，见 docs/DEPLOY.md 第零节末尾。

set -uo pipefail

# 正常就是服务器上的 /root/fastpay-ops。留一个环境变量口子是为了能在本地跑自测，
# 生产上不要设这个变量。
OPS_DIR="${FASTPAY_OPS_DIR:-/root/fastpay-ops}"
LOCK_DIR="$OPS_DIR/prod-deploy.lock"    # 目录：mkdir 在 POSIX 上是原子的，两个人同时建必然只有一个成功
INFO_FILE="$LOCK_DIR/holder"
LOG_FILE="$OPS_DIR/prod-deploy.log"
GRACE_MIN=30                            # 超过「预计分钟数 + 这个宽限」才算过期

if [ -t 1 ]; then
  C_OK=$'\033[32m'; C_NO=$'\033[31m'; C_WARN=$'\033[33m'; C_B=$'\033[1m'; C_OFF=$'\033[0m'
else
  C_OK=''; C_NO=''; C_WARN=''; C_B=''; C_OFF=''
fi

die()  { printf '%s❌ %s%s\n' "$C_NO" "$1" "$C_OFF" >&2; exit 1; }
info() { printf '%s\n' "$1"; }

usage() {
  cat >&2 <<'EOF'
用法：

  prod-lock.sh acquire <令牌> "<谁在动>" "<在干什么>" [预计分钟，默认30]
      动生产之前占上。已经有人占着就直接失败（退出码 1），这时候你要做的是等，不是硬上。

  prod-lock.sh check <令牌>
      确认「现在占着锁的就是我」。是 -> 退出码 0；不是（没人占 / 别人占着）-> 退出码 1。
      每一段改生产的命令开头都加一句：prod-lock.sh check <令牌> || exit 1

  prod-lock.sh release <令牌>
      干完了放开。令牌对不上会拒绝（防止误放别人的锁），确实要强放加 --force。

  prod-lock.sh status
      现在有没有人在动、是谁、动多久了、超没超预计时间。谁都可以看。

  prod-lock.sh steal <令牌> "<谁在动>" "<为什么要抢>"
      上一个人的锁明显是忘了放（已过期）时，用这个接管。会写进流水，事后查得到。
      没过期不许抢 —— 先去他那条 issue 下面问一句。

  prod-lock.sh log [行数，默认30]
      看操作流水：谁在什么时候占过、放过、抢过。

令牌 = 你这次动生产是为了哪条 issue，直接写 issue 编号，例如 MTM-210。
EOF
  exit 2
}

# ---------------------------------------------------------------- 内部工具

now_ts()  { date +%s; }
now_str() { date '+%F %T %Z'; }

# 谁在什么地方敲的这条命令 —— 事后追人全靠它，所以每个取值都要给默认值：
# 直接在机器控制台上敲的时候 SSH_CLIENT 是没有的，脚本开了 set -u，
# 少写一个 :- 这一整格就会变成空的，等于把最关键的线索丢了。
whoami_str() {
  local from="${SSH_CLIENT:-}"
  from="${from%% *}"
  [ -n "$from" ] || from="本机控制台"
  printf '%s@%s(来自 %s, pid %s)' "$(id -un 2>/dev/null || echo '?')" \
         "$(hostname 2>/dev/null || echo '?')" "$from" "$$"
}

log_line() {
  mkdir -p "$OPS_DIR" 2>/dev/null
  printf '%s\t%s\t%s\t%s\n' "$(now_str)" "$1" "$2" "$(whoami_str)" >> "$LOG_FILE"
}

# 读锁里的字段：read_field <键>
read_field() {
  [ -f "$INFO_FILE" ] || return 1
  sed -n "s/^$1=//p" "$INFO_FILE" | head -n1
}

# 锁过期了没：0=过期，1=没过期或没锁
is_expired() {
  local start eta
  start=$(read_field START_TS) || return 1
  eta=$(read_field ETA_MIN)
  [ -n "$start" ] || return 1
  [ -n "$eta" ] || eta=$GRACE_MIN
  [ "$(( $(now_ts) - start ))" -gt "$(( (eta + GRACE_MIN) * 60 ))" ]
}

print_holder() {
  local token who what start eta elapsed
  token=$(read_field TOKEN); who=$(read_field WHO); what=$(read_field WHAT)
  start=$(read_field START_TS); eta=$(read_field ETA_MIN)
  elapsed=$(( ( $(now_ts) - ${start:-$(now_ts)} ) / 60 ))
  printf '  谁在动：    %s\n' "${who:-?}"
  printf '  为了哪条：  %s\n' "${token:-?}"
  printf '  在干什么：  %s\n' "${what:-?}"
  printf '  从什么时候：%s（已经 %s 分钟，他自己预计 %s 分钟）\n' \
         "$(date -d "@${start:-0}" '+%F %T' 2>/dev/null || echo '?')" "$elapsed" "${eta:-?}"
  printf '  在哪敲的：  %s\n' "$(read_field FROM)"
}

# ---------------------------------------------------------------- 子命令

cmd_acquire() {
  local token="${1:-}" who="${2:-}" what="${3:-}" eta="${4:-30}"
  [ -n "$token" ] && [ -n "$who" ] && [ -n "$what" ] || usage
  case "$eta" in ''|*[!0-9]*) die "预计分钟数要是个数字，你写的是「$eta」" ;; esac

  mkdir -p "$OPS_DIR" 2>/dev/null
  # mkdir 成功 = 抢到了；失败 = 已经有人占着。这一步是原子的，不会两个人同时成功。
  if ! mkdir "$LOCK_DIR" 2>/dev/null; then
    printf '%s❌ 占不到 —— 现在有人正在动这台生产服务器，请等他弄完。%s\n\n' "$C_NO" "$C_OFF" >&2
    print_holder >&2
    printf '\n' >&2
    if is_expired; then
      printf '%s⚠️  这把锁已经超过他自己预计的时间很久了，很可能是干完忘了放。%s\n' "$C_WARN" "$C_OFF" >&2
      printf '   先去 %s 那条 issue 下面问一句「你还在动生产吗」。\n' "$(read_field TOKEN)" >&2
      printf '   确认他确实不在动了，再接管：\n' >&2
      printf '     %s steal %s "%s" "上一把锁已过期且本人确认已结束"\n' "$0" "$token" "$who" >&2
    else
      printf '   请等着，不要并行上。要联系他，看上面那条 issue 编号。\n' >&2
    fi
    log_line "占锁失败(已被占)" "$token/$who/$what"
    exit 1
  fi

  cat > "$INFO_FILE" <<EOF
TOKEN=$token
WHO=$who
WHAT=$what
START_TS=$(now_ts)
START_STR=$(now_str)
ETA_MIN=$eta
FROM=$(whoami_str)
EOF
  log_line "占锁" "$token/$who/$what/预计${eta}分钟"
  printf '%s✅ 占上了。接下来这台生产服务器归你，别人占不进来。%s\n\n' "$C_OK" "$C_OFF"
  print_holder
  cat <<EOF

${C_B}接下来记住两件事：${C_OFF}
  1. 每一段改生产的命令，开头加一句：
       $0 check $token || exit 1
  2. 干完了一定要放开，别人还等着：
       $0 release $token
EOF
}

cmd_check() {
  local token="${1:-}"
  [ -n "$token" ] || usage
  if [ ! -d "$LOCK_DIR" ]; then
    printf '%s❌ 你没占锁就要动生产 —— 停下。%s\n' "$C_NO" "$C_OFF" >&2
    printf '   先占上：%s acquire %s "<谁在动>" "<在干什么>" <预计分钟>\n' "$0" "$token" >&2
    exit 1
  fi
  local holder; holder=$(read_field TOKEN)
  if [ "$holder" != "$token" ]; then
    printf '%s❌ 锁不在你手上，现在动生产会跟别人撞车 —— 停下。%s\n\n' "$C_NO" "$C_OFF" >&2
    print_holder >&2
    exit 1
  fi
  if is_expired; then
    printf '%s⚠️  锁是你的，但已经超过你自己写的预计时间很久了。%s\n' "$C_WARN" "$C_OFF" >&2
    printf '   还在干就继续，干完了记得 release，别人在等。\n' >&2
  fi
  exit 0
}

cmd_release() {
  local token="${1:-}" force="${2:-}"
  [ -n "$token" ] || usage
  if [ ! -d "$LOCK_DIR" ]; then
    info "现在本来就没人占锁，不用放。"
    exit 0
  fi
  local holder; holder=$(read_field TOKEN)
  if [ "$holder" != "$token" ] && [ "$force" != "--force" ]; then
    printf '%s❌ 这把锁不是你的（现在是 %s 的），不给放。%s\n\n' "$C_NO" "$holder" "$C_OFF" >&2
    print_holder >&2
    printf '\n   确实要强放（比如对方已经确认结束了）：%s release %s --force\n' "$0" "$token" >&2
    exit 1
  fi
  local who what start elapsed
  who=$(read_field WHO); what=$(read_field WHAT); start=$(read_field START_TS)
  elapsed=$(( ( $(now_ts) - ${start:-$(now_ts)} ) / 60 ))
  rm -rf "$LOCK_DIR"
  if [ "$holder" != "$token" ]; then
    log_line "强放锁(--force)" "原持有=$holder/$who，强放的令牌=$token，共占用${elapsed}分钟"
    printf '%s✅ 已强放 %s 的锁（占了 %s 分钟）。这一笔已经记进流水。%s\n' "$C_WARN" "$holder" "$elapsed" "$C_OFF"
  else
    log_line "放锁" "$token/$who/$what/共占用${elapsed}分钟"
    printf '%s✅ 放开了，占了 %s 分钟。下一个人可以动了。%s\n' "$C_OK" "$elapsed" "$C_OFF"
  fi
}

cmd_status() {
  printf '\n%s===== 生产服务器（%s）现在有没有人在动 =====%s\n' "$C_B" "$(hostname)" "$C_OFF"
  printf '现在时间：%s\n\n' "$(now_str)"
  if [ ! -d "$LOCK_DIR" ]; then
    printf '%s✅ 没人在动，是空的。你要动就先占上：%s\n' "$C_OK" "$C_OFF"
    printf '   %s acquire <你的issue编号> "<谁在动>" "<在干什么>" <预计分钟>\n\n' "$0"
    exit 0
  fi
  if is_expired; then
    printf '%s⚠️  有人占着，而且已经超时很久了（多半是干完忘了放）%s\n\n' "$C_WARN" "$C_OFF"
  else
    printf '%s⛔ 有人正在动，你先等着%s\n\n' "$C_NO" "$C_OFF"
  fi
  print_holder
  printf '\n要联系他，看上面那条 issue 编号。最近的操作流水：%s log\n\n' "$0"
  exit 1
}

cmd_steal() {
  local token="${1:-}" who="${2:-}" why="${3:-}"
  [ -n "$token" ] && [ -n "$who" ] && [ -n "$why" ] || usage
  [ -d "$LOCK_DIR" ] || die "现在根本没人占锁，不用抢，直接 acquire。"
  if ! is_expired; then
    printf '%s❌ 上一把锁还没过期，不许抢。%s\n\n' "$C_NO" "$C_OFF" >&2
    print_holder >&2
    printf '\n   先去他那条 issue 下面问一句。真的确认他不在动了，让他自己 release，\n' >&2
    printf '   或者由总指挥拍板后用：%s release <他的令牌> --force\n' "$0" >&2
    exit 1
  fi
  local old_token old_who
  old_token=$(read_field TOKEN); old_who=$(read_field WHO)
  rm -rf "$LOCK_DIR"
  mkdir "$LOCK_DIR" 2>/dev/null || die "接管失败，锁目录建不起来，手工看一眼 $LOCK_DIR"
  cat > "$INFO_FILE" <<EOF
TOKEN=$token
WHO=$who
WHAT=接管自 $old_token（$old_who）：$why
START_TS=$(now_ts)
START_STR=$(now_str)
ETA_MIN=30
FROM=$(whoami_str)
EOF
  log_line "抢锁" "新=$token/$who，被抢=$old_token/$old_who，理由=$why"
  printf '%s✅ 接管了（原来是 %s / %s 占着，已过期）。这一笔已经记进流水。%s\n' \
         "$C_WARN" "$old_token" "$old_who" "$C_OFF"
  printf '   请去 %s 那条 issue 下面说一句你接管了，免得他回来一脸懵。\n' "$old_token"
}

cmd_log() {
  local n="${1:-30}"
  [ -f "$LOG_FILE" ] || { info "还没有任何操作流水（$LOG_FILE 不存在）。"; exit 0; }
  printf '\n%s===== 最近 %s 条生产操作流水 =====%s\n' "$C_B" "$n" "$C_OFF"
  printf '（时间 / 干了什么 / 详情 / 谁在哪敲的）\n\n'
  tail -n "$n" "$LOG_FILE"
  printf '\n'
}

# ---------------------------------------------------------------- 入口

case "${1:-}" in
  acquire) shift; cmd_acquire "$@" ;;
  check)   shift; cmd_check   "$@" ;;
  release) shift; cmd_release "$@" ;;
  status)  shift; cmd_status  "$@" ;;
  steal)   shift; cmd_steal   "$@" ;;
  log)     shift; cmd_log     "$@" ;;
  *)       usage ;;
esac
