#!/usr/bin/env bash
#
# prod-lock.sh 的自测。改完那个脚本，先跑这个，再装到服务器上。
#
#     bash deploy/prod-lock.test.sh
#
# 全过退出码 0，有一条不过退出码 1。
#
# 这个测试**不需要服务器、不需要 root、不动真的锁**：它把 FASTPAY_OPS_DIR 指到一个
# 临时目录，所有占锁放锁都发生在那里，跑完就删。
#
# 重点盯的是「该拦的必须拦住」。这个脚本最要命的错法不是报错，而是**该拦没拦**——
# 放行是安安静静的，没有任何报错、没有告警，等发现的时候两路人已经在同一台生产机上
# 对着干了（MTM-210 那次就是这么撞的）。所以每一条「拦」的用例都在这里钉死。
#
# ⚠️ 在 Windows 的 Git Bash 里跑，第 5 组并发用例可能会很慢甚至卡住（Git Bash 起进程
#    很贵）。那是环境问题不是脚本问题 —— 这个脚本本来就是给 Linux 服务器用的，
#    要复现请在 Linux 上跑，或者直接看 CI。

set -uo pipefail

S="${1:-$(cd "$(dirname "$0")" && pwd)/prod-lock.sh}"
[ -r "$S" ] || { echo "找不到被测脚本 $S"; exit 2; }

export FASTPAY_OPS_DIR
FASTPAY_OPS_DIR=$(mktemp -d -t fastpay-lock-test-XXXXXX)
trap 'rm -rf "$FASTPAY_OPS_DIR"' EXIT

P=0; F=0
t() { # t <说明> <期望退出码> <命令...>
  local desc="$1" want="$2"; shift 2
  local out; out=$("$@" 2>&1); local got=$?
  if [ "$got" = "$want" ]; then P=$((P+1)); printf '  ✅ %s（退出码 %s）\n' "$desc" "$got"
  else F=$((F+1)); printf '  ❌ %s（期望 %s，实际 %s）\n' "$desc" "$want" "$got"; printf '%s\n' "$out" | sed 's/^/       /'; fi
}

echo "===== 一、没人占锁时 ====="
t "status 报「没人在动」并以 0 退出"          0 bash "$S" status
t "没占锁就 check —— 必须拦住"                1 bash "$S" check MTM-210
t "没锁时 release 不报错"                     0 bash "$S" release MTM-210
t "没锁时 steal —— 拒绝"                      1 bash "$S" steal MTM-210 "甲" "没锁也想抢"

echo "===== 二、占上锁之后，别人必须进不来 ====="
t "第一个人占锁成功"                          0 bash "$S" acquire MTM-210 "梁运｜运维" "改版本标记" 20
t "自己 check —— 放行"                        0 bash "$S" check MTM-210
t "别人 check —— 拦住"                        1 bash "$S" check MTM-157
t "别人再 acquire —— 拦住"                    1 bash "$S" acquire MTM-157 "另一路" "推 v1.6.0" 30
t "有人占着时 status 以 1 退出（可当判断用）"  1 bash "$S" status
t "没过期不许抢"                              1 bash "$S" steal MTM-157 "另一路" "我急"
t "别人不许 release 我的锁"                   1 bash "$S" release MTM-157

echo "===== 三、放锁 ====="
t "本人 release 成功"                         0 bash "$S" release MTM-210
t "放完之后 check 应该拦住"                   1 bash "$S" check MTM-210
t "放完之后别人能占上"                        0 bash "$S" acquire MTM-157 "另一路" "推 v1.6.0" 30
t "--force 可以强放别人的锁"                  0 bash "$S" release MTM-210 --force

echo "===== 四、过期锁：干完忘了放的情况 ====="
bash "$S" acquire MTM-170 "甲" "上线忘了放" 1 >/dev/null 2>&1
# 把开始时间改成 3 小时前，模拟「早就该结束了」
sed -i "s/^START_TS=.*/START_TS=$(( $(date +%s) - 10800 ))/" "$FASTPAY_OPS_DIR/prod-deploy.lock/holder"
t "过期锁：别人 acquire 仍然拦住（不静默放行）" 1 bash "$S" acquire MTM-210 "乙" "接着干" 20
t "过期锁：本人 check 仍然放行（长部署不能被自己踢掉）" 0 bash "$S" check MTM-170
t "过期锁：确认过之后可以被接管"               0 bash "$S" steal MTM-210 "乙" "上一把已过期且本人确认结束"
t "接管后新持有人 check 放行"                  0 bash "$S" check MTM-210
t "接管后原持有人 check 被拦"                  1 bash "$S" check MTM-170
bash "$S" release MTM-210 >/dev/null 2>&1

echo "===== 五、并发：20 个人同时抢，必须只有 1 个占到 ====="
# 这一条是这把锁的全部意义所在。mkdir 的原子性一旦被换成「先判断再创建」，
# 这里就会出现 2 个甚至更多赢家 —— 而线上表现就是两个人同时以为锁是自己的。
for i in $(seq 1 20); do ( bash "$S" acquire "MTM-$i" "并发$i" "抢锁测试" 5 >/dev/null 2>&1 ) & done
wait
WINS=$(bash "$S" status 2>&1 | grep -c '为了哪条')
if [ "$WINS" = "1" ]; then P=$((P+1)); printf '  ✅ 20 个并发只有 1 个占到锁\n'
else F=$((F+1)); printf '  ❌ 并发结果异常：占到锁的有 %s 个（必须是 1）\n' "$WINS"; fi
bash "$S" release "$(sed -n 's/^TOKEN=//p' "$FASTPAY_OPS_DIR/prod-deploy.lock/holder" 2>/dev/null)" >/dev/null 2>&1

echo "===== 六、参数校验 ====="
t "缺参数 —— 打用法，退出码 2"                2 bash "$S" acquire MTM-210
t "预计分钟不是数字 —— 拒绝"                  1 bash "$S" acquire MTM-210 "甲" "干活" 三十
t "未知子命令 —— 打用法"                      2 bash "$S" 乱敲

echo "===== 七、操作流水：事后必须查得到是谁 ====="
LOGN=$(wc -l < "$FASTPAY_OPS_DIR/prod-deploy.log" 2>/dev/null || echo 0)
if [ "$LOGN" -ge 10 ]; then P=$((P+1)); printf '  ✅ 流水记下了 %s 条\n' "$LOGN"
else F=$((F+1)); printf '  ❌ 流水只有 %s 条，太少\n' "$LOGN"; fi

# 直接在机器控制台上敲的时候 SSH_CLIENT 是没有的。脚本开了 set -u，
# 少写一个 :- 就会把「谁在哪敲的」这一整格变成空的 —— 那正是事后追人唯一的线索。
( unset SSH_CLIENT
  D=$(mktemp -d); FASTPAY_OPS_DIR="$D" bash "$S" acquire MTM-210 "甲" "追人字段回归" 10 >/dev/null 2>&1
  LAST=$(tail -n1 "$D/prod-deploy.log" | cut -f4)
  FROM=$(sed -n 's/^FROM=//p' "$D/prod-deploy.lock/holder")
  rm -rf "$D"
  [ -n "$LAST" ] && [ -n "$FROM" ] )
if [ $? -eq 0 ]; then P=$((P+1)); printf '  ✅ 没有 SSH_CLIENT 时，「谁在哪敲的」也记得下来\n'
else F=$((F+1)); printf '  ❌ 没有 SSH_CLIENT 时「谁在哪敲的」是空的 —— 事后追不到人\n'; fi

echo "===== 八、脚本本身 ====="
if bash -n "$S" 2>/dev/null; then P=$((P+1)); printf '  ✅ bash -n 语法检查通过\n'
else F=$((F+1)); printf '  ❌ bash -n 语法检查没过\n'; fi

echo
echo "================================================"
if [ "$F" -eq 0 ]; then
  printf '全部通过：%d 项\n' "$P"; exit 0
else
  printf '有 %d 项没过（共 %d 项）—— 别把这个脚本装到服务器上，先修\n' "$F" "$((P+F))"; exit 1
fi
