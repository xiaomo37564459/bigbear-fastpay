#!/usr/bin/env bash
#
# pr-overlap.sh 的自测。改完那个脚本，先跑这个。
#
#     bash .github/scripts/pr-overlap.test.sh
#
# 全过退出码 0，有一条不过退出码 1。
#
# 这个测试**不联网、不需要 gh、不碰任何真的 PR**：只测 `report` 那一半 ——
# 输入一份 JSON，检查吐出来的评论正文对不对。联网的那一半（collect / run）留给真实 PR 去验。
#
# 重点盯两件事：
#
#  1. **该报的必须报出来。** 这个工具唯一的作用就是「让人当场知道撞了」。漏报是安安静静的，
#     没有任何报错 —— 而漏报一次的代价，就是又一次三个人干同一件事、全都干完了才发现（MTM-211）。
#
#  2. **任何情况下都不许拦。** 只要它敢以非 0 退出，接在后面的检查就会红，红的检查早晚会变成
#     「不许合并」，那就成了误伤正当改动的路障。这条在下面钉死了。

set -uo pipefail

S="${1:-$(cd "$(dirname "$0")" && pwd)/pr-overlap.sh}"
[ -r "$S" ] || { echo "找不到被测脚本 $S"; exit 2; }
command -v jq >/dev/null 2>&1 || { echo "机器上没装 jq，这个测试跑不了"; exit 2; }

D=$(mktemp -d -t pr-overlap-test-XXXXXX)
trap 'rm -rf "$D"' EXIT

P=0; F=0
ok()  { P=$((P+1)); printf '  ✅ %s\n' "$1"; }
bad() { F=$((F+1)); printf '  ❌ %s\n' "$1"; [ -n "${2:-}" ] && printf '%s\n' "$2" | sed 's/^/       /'; }

# 跑一次 report，把正文存进 $OUT，退出码存进 $RC
run_report() { # run_report <输入JSON字面量>
  printf '%s' "$1" > "$D/in.json"
  OUT=$(bash "$S" report "$D/in.json" 2>"$D/err"); RC=$?
}

has()  { # has <说明> <该出现的字符串>
  case "$OUT" in *"$2"*) ok "$1" ;; *) bad "$1" "实际输出：
$OUT" ;; esac
}
hasnt() { # hasnt <说明> <不该出现的字符串>
  case "$OUT" in *"$2"*) bad "$1" "不该出现「$2」，实际输出：
$OUT" ;; *) ok "$1" ;; esac
}
rc0() { [ "$RC" -eq 0 ] && ok "$1" || bad "$1" "退出码 $RC，stderr：$(cat "$D/err")"; }

echo "===== 一、没撞的时候 ====="
run_report '{"repo":"o/r","self":{"number":10,"files":["a.txt","b.txt"]},
             "others":[{"number":11,"title":"别的事","author":"lee","draft":false,"url":"u","files":["c.txt"]}]}'
rc0   "没撞也以退出码 0 结束"
has   "第一行的记号里写着 overlap=0" "overlap=0 -->"
has   "说了一句「没跟别人撞」"        "没跟别人撞"
hasnt "没撞就别摆警告"               "⚠️ 你改的文件"

echo "===== 二、撞了一个 ====="
run_report '{"repo":"o/r","self":{"number":34,"files":["deploy/verify-release.sh","docs/DEPLOY.md"]},
             "others":[{"number":33,"title":"发版脚本加固","author":"liang","draft":false,
                        "url":"https://github.com/o/r/pull/33","files":["deploy/verify-release.sh","README.md"]}]}'
rc0 "撞了也必须以退出码 0 结束 —— 这个工具永远不许拦人"
has "记号里写着 overlap=1"       "overlap=1 -->"
has "标题说清是「别人也在改」"   "还有别人也在改"
has "报出了撞的是几号"           "#33"
has "带上了对方的标题（判断是不是同一件事全靠它）" "发版脚本加固"
has "带上了对方是谁开的"         "@liang"
has "点得进去（带链接）"         "https://github.com/o/r/pull/33"
has "报出了撞在哪个文件上"       '`deploy/verify-release.sh`'
hasnt "没撞的文件不许算进来"     '`README.md`'
hasnt "自己独有的文件也不许算进来" '`docs/DEPLOY.md`'
has "写明了「不拦你合并」"       "不会挡住你合并"
has "给了下一步怎么办"           "三选一"

echo "===== 三、撞了好几个：撞得最狠的排前面 ====="
run_report '{"repo":"o/r","self":{"number":32,"files":["x.sh","y.sh","z.sh"]},
             "others":[{"number":30,"title":"只撞一个","author":"a","draft":false,"url":"u30","files":["x.sh"]},
                       {"number":31,"title":"撞两个","author":"b","draft":false,"url":"u31","files":["y.sh","z.sh"]}]}'
rc0 "多条也退出码 0"
has "记号里写着 overlap=2" "overlap=2 -->"
has "两条都报了（#30）" "#30"
has "两条都报了（#31）" "#31"
# 撞两个文件的 #31 应该排在只撞一个的 #30 前面 —— 一眼先看到最像「重复开工」的那条
if [ "$(printf '%s' "$OUT" | grep -n '#31' | head -1 | cut -d: -f1)" \
     -lt "$(printf '%s' "$OUT" | grep -n '#30' | head -1 | cut -d: -f1)" ]; then
  ok "撞得多的排前面（#31 在 #30 上面）"
else
  bad "排序不对：撞两个文件的 #31 应该排在只撞一个的 #30 前面" "$OUT"
fi
has "算出了一共几个文件撞上" "共 3 个文件撞上"

echo "===== 四、草稿也要报 ====="
# 草稿状态的提交同样代表「有人已经在做这件事了」，漏掉它就等于漏掉最该提醒的那一类：
# 别人刚开个草稿占坑，你这边从零又做一遍。
run_report '{"repo":"o/r","self":{"number":40,"files":["a.txt"]},
             "others":[{"number":41,"title":"我先占个坑","author":"c","draft":true,"url":"u","files":["a.txt"]}]}'
has "草稿状态的提交照样报出来" "#41"
has "并且标出来这是草稿"       "草稿"

echo "===== 五、边界：空的、重的、自己 ====="
run_report '{"repo":"o/r","self":{"number":1,"files":[]},"others":[]}'
rc0 "自己没改文件、也没有别的提交 —— 不炸"
has "并且报 overlap=0" "overlap=0 -->"

run_report '{"repo":"o/r","self":{"number":1,"files":["a.txt"]}}'
rc0 "输入里干脆没有 others 这一项 —— 不炸"
has "并且报 overlap=0" "overlap=0 -->"

# 同一个文件在列表里出现两次（API 分页重叠之类），不能被数成撞了两次
run_report '{"repo":"o/r","self":{"number":1,"files":["a.txt","a.txt"]},
             "others":[{"number":2,"title":"t","author":"x","draft":false,"url":"u","files":["a.txt","a.txt"]}]}'
has "文件重复出现只算一次" "撞了 1 个文件"

# 万一数据里混进了自己（同号），绝不能报「你跟你自己撞了」—— 那是纯噪音，会让人不再看这条提醒
run_report '{"repo":"o/r","self":{"number":7,"files":["a.txt"]},
             "others":[{"number":7,"title":"就是我自己","author":"x","draft":false,"url":"u","files":["a.txt"]}]}'
has "不跟自己比" "overlap=0 -->"

echo "===== 六、文件名奇形怪状也不能崩 ====="
run_report '{"repo":"o/r","self":{"number":1,"files":["有 空格 的/中文名 $HOME `whoami`.md"]},
             "others":[{"number":2,"title":"标题里有 `反引号` 和 $VAR","author":"x","draft":false,"url":"u",
                        "files":["有 空格 的/中文名 $HOME `whoami`.md"]}]}'
rc0 "文件名带空格 / 中文 / \$ / 反引号 —— 不炸"
has "文件名原样报出来" '有 空格 的/中文名 $HOME `whoami`.md'
has "标题原样报出来"   '标题里有 `反引号` 和 $VAR'

echo "===== 七、多到列不下时，必须说明没列全 ====="
# 悄悄截断是最坏的一种错：看的人以为「就撞了这几个」，其实底下还有一堆。
BIG=$(jq -nc '{repo:"o/r",
               self:{number:1, files:[range(0;40) | "f\(.).txt"]},
               others:[{number:2,title:"改了一大片",author:"x",draft:false,url:"u",
                        files:[range(0;40) | "f\(.).txt"]}]}')
run_report "$BIG"
has "文件多的时候写明「另外还有 N 个」" "另外还有 25 个文件也撞了"

MANY=$(jq -nc '{repo:"o/r",
                self:{number:1, files:["a.txt"]},
                others:[range(2;25) | {number:., title:"第 \(.) 条", author:"x", draft:false, url:"u", files:["a.txt"]}]}')
run_report "$MANY"
has "撞车提交多的时候写明还有几条没列" "另外还有 8 条没列出来"
has "记号里的数字是真实总数，不是列出来的条数" "overlap=23 -->"

run_report '{"repo":"o/r","self":{"number":1,"files":["a.txt"]},
             "others":[{"number":2,"title":"t","author":"x","draft":false,"url":"u","files":["a.txt"]}],
             "truncated_others":5}'
has "开着的提交太多、没全比到的时候，也要说明白" "还有 5 条没比到"

echo "===== 八、发评论那一环靠的东西，不许被改坏 ====="
# 「找回上一条提醒、原地改掉」全靠正文第一行的这个记号。记号一变，旧评论就认不出来，
# 每推一次代码 PR 底下就会多一条新的，很快糊成一片，然后大家就不看了。
run_report '{"repo":"o/r","self":{"number":1,"files":["a.txt"]},
             "others":[{"number":2,"title":"t","author":"x","draft":false,"url":"u","files":["a.txt"]}]}'
FIRST=$(printf '%s' "$OUT" | head -n1)
case "$FIRST" in
  '<!-- pr-overlap-check'*) ok "正文第一行就是认领记号（发评论时靠它找回旧的那条）" ;;
  *) bad "正文第一行不是认领记号，实际是：$FIRST" ;;
esac
if printf '%s' "$FIRST" | grep -qE 'overlap=[0-9]+ -->$'; then
  ok "记号里带着机器能读的 overlap=N，并且正确闭合"
else
  bad "记号格式不对：$FIRST"
fi
# 脚本里写死的记号，必须和这里断言的一模一样
if grep -q "MARKER_PREFIX='<!-- pr-overlap-check'" "$S"; then
  ok "脚本里的记号和测试里断言的是同一个"
else
  bad "脚本里的 MARKER_PREFIX 被改了，但测试没跟着改 —— 旧评论会认不出来"
fi

echo "===== 九、参数不对的时候，话要说清楚 ====="
OUT=$(bash "$S" report 2>&1); RC=$?
[ "$RC" -eq 2 ] && ok "report 不给输入文件 —— 退出码 2" || bad "report 不给输入文件时退出码应为 2，实际 $RC"
OUT=$(bash "$S" report "$D/根本没有这个文件.json" 2>&1); RC=$?
[ "$RC" -eq 2 ] && ok "输入文件不存在 —— 退出码 2" || bad "输入文件不存在时退出码应为 2，实际 $RC"
printf '这不是 json' > "$D/bad.json"
OUT=$(bash "$S" report "$D/bad.json" 2>&1); RC=$?
[ "$RC" -eq 2 ] && ok "输入不是合法 JSON —— 退出码 2 并说明原因" || bad "输入不是合法 JSON 时退出码应为 2，实际 $RC"
has "并且说清了是 JSON 的问题" "不是合法 JSON"
OUT=$(bash "$S" 乱敲 2>&1); RC=$?
[ "$RC" -eq 2 ] && ok "不认识的子命令 —— 打用法，退出码 2" || bad "不认识的子命令退出码应为 2，实际 $RC"
OUT=$(bash "$S" --help 2>&1); RC=$?
[ "$RC" -eq 0 ] && ok "--help 打用法，退出码 0" || bad "--help 退出码应为 0，实际 $RC"
has "用法里写了三个子命令" "collect"

echo "===== 十、脚本本身 ====="
if bash -n "$S" 2>"$D/syn"; then ok "bash -n 语法检查通过"; else bad "bash -n 语法检查没过" "$(cat "$D/syn")"; fi
if bash -n "$0" 2>"$D/syn2"; then ok "测试脚本自己 bash -n 也通过"; else bad "测试脚本语法没过" "$(cat "$D/syn2")"; fi
# 这个脚本的定位是「只提示不拦截」。report 这一路要是哪天被加了 exit 1，
# CI 上就会多出一项会红的检查，红检查早晚会被收紧成「不许合并」—— 那就跟本意反了。
if grep -nE '^\s*(exit 1|return 1)\b' "$S" >"$D/exits"; then
  bad "脚本里出现了 exit 1 / return 1 —— 它必须永远不拦人" "$(cat "$D/exits")"
else
  ok "脚本里没有任何 exit 1 / return 1（永远不拦人）"
fi

echo
echo "================================================"
if [ "$F" -eq 0 ]; then
  printf '全部通过：%d 项\n' "$P"; exit 0
else
  printf '有 %d 项没过（共 %d 项）—— 先修好再合\n' "$F" "$((P+F))"; exit 1
fi
