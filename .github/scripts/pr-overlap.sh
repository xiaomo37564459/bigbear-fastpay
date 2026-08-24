#!/usr/bin/env bash
#
# 撞车提醒：一个 PR 开出来（或者推了新代码）的时候，把它改动的文件跟**其他所有还开着的 PR**
# 比一遍，重叠了就在这个 PR 上留一条评论，写清楚跟哪几号撞了、撞在哪几个文件上。
#
# 为什么要有这东西（MTM-211）：2026-08-20 那天，同一件事被不同的人重复开工了三次，
# 而且三次都是各自把活干完、开了 PR 之后才被人事后巡查发现的：
#   - 「数据库脚本改名」开了 3 个 PR：#29 #33 #34，改的是同一组文件
#   - 「发版验证脚本加固」三分钟之内开了 3 个 PR：#30 #31 #32，改的是同一个脚本
# 每个人自己都没做错，问题是中间没有任何东西提醒他们「这件事已经有人在做了」。
#
# ⚠️ 它**只提示，不拦截**。有时候两路人正当地改同一个文件（比如都往同一份配置里加一行），
#    一刀切拦死会误伤。所以这个脚本任何情况下都以退出码 0 结束，也绝不去动分支保护。
#
# 用法：
#   pr-overlap.sh report <输入.json>   纯文本处理：输入 JSON → 输出评论正文。不联网，可离线自测
#   pr-overlap.sh collect <PR号>       联网：用 gh 把数据抓下来，输出上面那份 JSON
#   pr-overlap.sh run <PR号>           联网：collect + report + 发评论（已有就改，没撞过就不发）
#   pr-overlap.sh run <PR号> --dry-run 同上，但只把评论正文打出来，不真的发
#
# 需要：bash、jq、gh（gh 只有 collect / run 用得上）。
# 环境变量：
#   GH_REPO           仓库（owner/name）。不设就问 gh 当前目录是哪个仓库
#   GH_TOKEN          gh 的凭据，CI 里由 GITHUB_TOKEN 提供
#   MAX_OTHER_PRS     最多跟多少个还开着的 PR 比（默认 60，防止仓库里堆了几百个 PR 时把 API 打爆）
#
# 自测：bash .github/scripts/pr-overlap.test.sh

set -uo pipefail

# 评论用这个记号认领。找回上一条提醒、原地改掉它，靠的就是它 —— 不能改，
# 改了就认不出旧评论，每推一次代码就会多出一条新的，PR 底下很快糊成一片。
MARKER_PREFIX='<!-- pr-overlap-check'

# 一条 PR 最多列几个撞掉的文件、最多列几条撞车的 PR。超了就写明「另外还有 N 个」，
# 不许悄悄截断 —— 看的人必须知道自己没看全。
MAX_FILES_PER_PR=15
MAX_PRS_LISTED=15

MAX_OTHER_PRS="${MAX_OTHER_PRS:-60}"

die() { printf '%s\n' "$*" >&2; exit 2; }
note() { printf '%s\n' "$*" >&2; }

need() { command -v "$1" >/dev/null 2>&1 || die "机器上没装 $1，这个脚本跑不了"; }

usage() {
  sed -n '3,28p' "$0" | sed 's/^# \{0,1\}//'
}

# ---------------------------------------------------------------------------
# report：纯文本处理，不联网。输入 JSON 长这样：
#
#   {
#     "repo":  "owner/name",
#     "self":  { "number": 34, "files": ["a.txt", "b.txt"] },
#     "others": [
#       { "number": 33, "title": "...", "author": "...", "draft": false,
#         "url": "https://github.com/...", "files": ["a.txt"] }
#     ],
#     "truncated_others": 0
#   }
#
# 输出：评论正文（markdown）到 stdout。第一行是记号，里面带一个机器读的 overlap=N。
# 退出码永远是 0。
# ---------------------------------------------------------------------------
report() {
  local input="${1:-}"
  [ -n "$input" ] || die "report 要一个输入 JSON 文件：pr-overlap.sh report <输入.json>"
  [ -r "$input" ] || die "读不到输入文件 $input"
  need jq

  jq -e . "$input" >/dev/null 2>&1 || die "输入文件不是合法 JSON：$input"

  # 求交集：把自己的文件做成一个查找表，再逐个 PR 取重叠。
  # 用查找表而不是 index()，是因为 index() 在文件多的时候是 O(n²)，
  # 一个动了几百个文件的 PR 能把这一步拖到几十秒。
  local hits
  hits=$(jq -c '
    (.self.files // [] | unique)                                as $mine
    | ($mine | map({key: ., value: true}) | from_entries)       as $mineset
    | (.self.number)                                            as $selfnum
    | [ (.others // [])[]
        | . as $o
        | select(.number != $selfnum)
        | ((.files // []) | unique | map(select($mineset[.] == true))) as $shared
        | select($shared | length > 0)
        | { number: $o.number,
            title:  ($o.title // ""),
            author: ($o.author // ""),
            draft:  ($o.draft // false),
            url:    ($o.url // ""),
            shared: $shared }
      ]
    | sort_by(-(.shared | length), .number)
  ' "$input")

  local n_pr n_self truncated_others
  n_pr=$(printf '%s' "$hits" | jq 'length')
  n_self=$(jq '(.self.files // []) | length' "$input")
  truncated_others=$(jq '.truncated_others // 0' "$input")

  # ---- 没撞 ----
  if [ "$n_pr" -eq 0 ]; then
    printf '%s: overlap=0 -->\n' "$MARKER_PREFIX"
    printf '## ✅ 没跟别人撞\n\n'
    printf '你这次改的 %s 个文件，目前**没有**别的还开着的提交在动。\n' "$n_self"
    printf '（如果你之前在这里看到过撞车提醒，那说明已经解除了。）\n'
    footer "$truncated_others"
    return 0
  fi

  # ---- 撞了 ----
  local n_files
  n_files=$(printf '%s' "$hits" | jq '[.[].shared[]] | unique | length')

  printf '%s: overlap=%s -->\n' "$MARKER_PREFIX" "$n_pr"
  printf '## ⚠️ 你改的文件，还有别人也在改\n\n'
  if [ "$n_pr" -eq 1 ]; then
    printf '还有 **1 个**没合并的提交，跟你动了同一批文件（共 %s 个文件撞上）。\n\n' "$n_files"
  else
    printf '还有 **%s 个**没合并的提交，跟你动了同一批文件（共 %s 个文件撞上）。\n\n' "$n_pr" "$n_files"
  fi
  printf '**这只是提醒，不会挡住你合并。** 但请先花十秒看一眼下面这几条 —— 万一做的其实是同一件事，\n'
  printf '早说一声就不用两边都白干。（以前出过：同一件事三个人各干各的，都干完了才发现。）\n\n'

  # 逐条列出来。这里刻意用「#号 + 标题 + 谁开的 + 撞了哪些文件」四样，
  # 少一样都不够判断「是不是同一件事」。
  printf '%s' "$hits" | jq -r --argjson maxf "$MAX_FILES_PER_PR" --argjson maxp "$MAX_PRS_LISTED" '
    .[:$maxp][]
    | ( if .url != "" then "[#\(.number)](\(.url))" else "#\(.number)" end ) as $ref
    | ( if .draft then " `草稿`" else "" end ) as $draft
    | ( if .author != "" then " · @\(.author)" else "" end ) as $who
    | "### \($ref) \(.title)\($draft)\($who)",
      "撞了 \(.shared | length) 个文件：",
      ( .shared[:$maxf][] | "- `\(.)`" ),
      ( if (.shared | length) > $maxf
        then "- …另外还有 \((.shared | length) - $maxf) 个文件也撞了，点上面的 #\(.number) 进去看全部改动"
        else empty end ),
      ""
  '

  if [ "$n_pr" -gt "$MAX_PRS_LISTED" ]; then
    printf '> 撞车的提交太多，上面只列了撞得最狠的 %s 条，**另外还有 %s 条没列出来**。\n\n' \
      "$MAX_PRS_LISTED" "$((n_pr - MAX_PRS_LISTED))"
  fi

  printf -- '---\n\n'
  printf '**接下来怎么办（你自己判断，三选一）**\n\n'
  printf '1. **确实是同一件事** → 在这条下面回一句，商量好谁来做，另一个关掉。别两边都改\n'
  printf '2. **是两件不同的事，只是碰巧动到同一个文件** → 不用管，继续。合并的时候留意一下解冲突就行\n'
  printf '3. **拿不准** → 在对应的 issue 里问一句总指挥，别自己闷头往下做\n'
  footer "$truncated_others"
}

footer() {
  local truncated_others="${1:-0}"
  printf '\n'
  if [ "${truncated_others:-0}" -gt 0 ]; then
    printf '> ⚠️ 仓库里还开着的提交太多，这次只比了最近更新的那一批，**还有 %s 条没比到**。\n\n' \
      "$truncated_others"
  fi
  printf '<sub>这条是机器自动发的（`.github/workflows/pr-overlap.yml`），每次推代码会自动更新，'
  printf '不撞了会自己改成「没跟别人撞」。它不拦任何合并。说明见 `docs/CI.md`。</sub>\n'
}

# ---------------------------------------------------------------------------
# collect：联网把数据抓下来。输出上面那份输入 JSON 到 stdout。
# ---------------------------------------------------------------------------
collect() {
  local pr="${1:-}"
  [ -n "$pr" ] || die "collect 要一个 PR 号：pr-overlap.sh collect <PR号>"
  need jq
  need gh

  local repo="${GH_REPO:-}"
  if [ -z "$repo" ]; then
    repo=$(gh repo view --json nameWithOwner -q .nameWithOwner) || die "问不出当前是哪个仓库，请设 GH_REPO=owner/name"
  fi

  local meta base
  meta=$(gh api "repos/$repo/pulls/$pr") || die "读不到 $repo 的 #$pr"
  base=$(printf '%s' "$meta" | jq -r '.base.ref')

  local self_files
  self_files=$(gh api "repos/$repo/pulls/$pr/files" --paginate --jq '.[].filename' | jq -R . | jq -sc .)

  # 只跟「合到同一个目标分支」的 PR 比。合到不同分支的本来就是两条线，撞了也不叫撞。
  # 按最近更新排序，这样万一 PR 特别多、被 MAX_OTHER_PRS 截断了，留下的是最活跃的那批。
  local others_meta total
  others_meta=$(gh api "repos/$repo/pulls?state=open&base=$base&sort=updated&direction=desc&per_page=100" \
                  --paginate --jq '.[] | {number, title, author: .user.login, draft, url: .html_url}' \
                | jq -sc --argjson self "$pr" 'map(select(.number != $self))')
  total=$(printf '%s' "$others_meta" | jq 'length')

  local truncated=0
  if [ "$total" -gt "$MAX_OTHER_PRS" ]; then
    truncated=$((total - MAX_OTHER_PRS))
    note "还开着的提交有 $total 条，超过上限 $MAX_OTHER_PRS，只比最近更新的 $MAX_OTHER_PRS 条（$truncated 条没比）"
    others_meta=$(printf '%s' "$others_meta" | jq -c ".[:$MAX_OTHER_PRS]")
  fi

  local others='[]' num files one
  while IFS= read -r one; do
    [ -n "$one" ] || continue
    num=$(printf '%s' "$one" | jq -r '.number')
    files=$(gh api "repos/$repo/pulls/$num/files" --paginate --jq '.[].filename' | jq -R . | jq -sc .)
    others=$(printf '%s' "$others" | jq -c --argjson o "$one" --argjson f "${files:-[]}" '. + [$o + {files: $f}]')
  done < <(printf '%s' "$others_meta" | jq -c '.[]')

  jq -nc \
    --arg repo "$repo" \
    --argjson self_number "$pr" \
    --argjson self_files "${self_files:-[]}" \
    --argjson others "$others" \
    --argjson truncated "$truncated" \
    '{repo: $repo, self: {number: $self_number, files: $self_files}, others: $others, truncated_others: $truncated}'
}

# ---------------------------------------------------------------------------
# run：collect + report + 把评论发上去（有旧的就原地改，没有旧的且没撞就什么都不发）
# ---------------------------------------------------------------------------
run() {
  local pr="${1:-}" dry="${2:-}"
  [ -n "$pr" ] || die "run 要一个 PR 号：pr-overlap.sh run <PR号> [--dry-run]"
  need jq
  need gh

  local repo="${GH_REPO:-}"
  if [ -z "$repo" ]; then
    repo=$(gh repo view --json nameWithOwner -q .nameWithOwner) || die "问不出当前是哪个仓库，请设 GH_REPO=owner/name"
  fi

  local tmp; tmp=$(mktemp -d -t pr-overlap-XXXXXX)
  # shellcheck disable=SC2064
  trap "rm -rf '$tmp'" EXIT

  # 抓数据失败要**大声说出来**。这个工具最坏的坏法不是报错，是安安静静地什么都没做 ——
  # 大家以为「机器在盯着呢」，其实早就哑了，撞车照样没人提醒。
  if ! GH_REPO="$repo" collect "$pr" > "$tmp/in.json" 2>"$tmp/collect.err"; then
    note "::warning::撞车提醒没跑成：#$pr 的数据没抓下来。不影响任何检查结果，但这次没人替你比对。"
    sed 's/^/    /' "$tmp/collect.err" >&2
    return 0
  fi
  sed 's/^/    /' "$tmp/collect.err" >&2

  report "$tmp/in.json" > "$tmp/body.md" || {
    note "::warning::撞车提醒没跑成：#$pr 的比对结果生成失败。不影响任何检查结果。"
    return 0
  }

  local overlap
  overlap=$(head -n1 "$tmp/body.md" | sed -n 's/.*overlap=\([0-9]*\).*/\1/p')
  overlap="${overlap:-0}"

  if [ "$dry" = "--dry-run" ]; then
    cat "$tmp/body.md"
    note "（--dry-run：没有真的发评论。这次撞了 $overlap 条）"
    return 0
  fi

  local existing
  existing=$(gh api "repos/$repo/issues/$pr/comments" --paginate \
              --jq "[.[] | select(.body != null and (.body | startswith(\"$MARKER_PREFIX\")))] | .[-1].id // empty" 2>/dev/null)

  if [ -n "$existing" ]; then
    # 已经提醒过了，原地改掉那条。撞车解除时这条会变成「没跟别人撞」，不再留一条误导人的旧提醒。
    if jq -n --rawfile body "$tmp/body.md" '{body: $body}' \
        | gh api -X PATCH "repos/$repo/issues/comments/$existing" --input - >/dev/null; then
      note "已更新 #$pr 上的撞车提醒（评论 $existing，撞了 $overlap 条）"
    else
      note "⚠️ 改不动 #$pr 上那条评论（评论 $existing）。多半是这次跑的 token 没有写评论的权限（fork 过来的 PR 就是这样）。不影响任何检查结果。"
    fi
    return 0
  fi

  if [ "$overlap" -eq 0 ]; then
    note "#$pr 没跟别人撞，之前也没提醒过 —— 不发评论，省得刷屏"
    return 0
  fi

  if jq -n --rawfile body "$tmp/body.md" '{body: $body}' \
      | gh api -X POST "repos/$repo/issues/$pr/comments" --input - >/dev/null; then
    note "已在 #$pr 上留下撞车提醒（撞了 $overlap 条）"
  else
    note "⚠️ 评论发不出去。多半是这次跑的 token 没有写评论的权限（fork 过来的 PR 就是这样）。不影响任何检查结果。"
  fi
}

case "${1:-}" in
  report)  shift; report "$@" ;;
  collect) shift; collect "$@" ;;
  run)     shift; run "$@" ;;
  -h|--help|help|"") usage ;;
  *) usage; exit 2 ;;
esac
