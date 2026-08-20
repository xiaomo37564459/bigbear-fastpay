#!/usr/bin/env bash
#
# 在本地跑一遍和 CI 上一模一样的检查：先把最新的主干（master）合进来，再跑全部测试。
#
# 用法（在仓库任意目录下）：
#   bash scripts/ci-local.sh
#
# 它不会动你现在的工作目录 —— 合并是在一个临时目录里做的，跑完就删掉。
# 想让依赖（node_modules / target）留着下次接着用，加个环境变量指定固定目录：
#   CI_LOCAL_DIR=../fastpay-ci-local bash scripts/ci-local.sh
#
set -euo pipefail

BASE="${BASE_BRANCH:-master}"
REPO_ROOT="$(git rev-parse --show-toplevel)"
cd "$REPO_ROOT"

echo "==> 抓一下最新的主干（$BASE）"
git fetch --no-tags origin "$BASE"
MASTER_SHA="$(git rev-parse "origin/$BASE")"
HEAD_SHA="$(git rev-parse HEAD)"
echo "    主干最新版本号 : $MASTER_SHA"
echo "    你当前分支     : $HEAD_SHA"

if [ -n "${CI_LOCAL_DIR:-}" ]; then
  WORKDIR="$CI_LOCAL_DIR"
  KEEP=1
else
  WORKDIR="$(mktemp -d -t fastpay-ci-local-XXXXXX)"
  KEEP=0
fi

cleanup() {
  cd "$REPO_ROOT"
  if [ "$KEEP" = "0" ]; then
    git worktree remove --force "$WORKDIR" >/dev/null 2>&1 || true
    rm -rf "$WORKDIR" >/dev/null 2>&1 || true
  fi
}
trap cleanup EXIT

echo "==> 在临时目录里把「你的分支 + 主干最新」合出来（不动你现在的工作目录）"
git worktree remove --force "$WORKDIR" >/dev/null 2>&1 || true
rm -rf "$WORKDIR" >/dev/null 2>&1 || true
git worktree add --detach "$WORKDIR" "$HEAD_SHA" >/dev/null
cd "$WORKDIR"

if ! git -c user.name=ci-local -c user.email=ci-local@example.com \
        merge --no-ff --no-edit "$MASTER_SHA"; then
  echo ""
  echo "❌ 跟主干冲突了，下面这些文件打架："
  git diff --name-only --diff-filter=U | sed 's/^/     - /'
  echo ""
  echo "   怎么办：回到你自己的分支，执行"
  echo "     git fetch origin $BASE && git merge origin/$BASE"
  echo "   把冲突解掉、提交，再跑一次本脚本。"
  exit 1
fi
echo "    ✅ 主干代码合进来了"

echo "==> 后端测试 fastpay-server（含 MySQL + PostgreSQL 两套端到端）"
( cd fastpay-server && mvn -B test )

for app in fastpay-admin fastpay-merchant; do
  echo "==> 前端 $app：跑测试 + 打包"
  ( cd "$app" && npm ci && npm test && npm run build )
done

echo ""
echo "✅ 全部通过 —— 基于主干版本 $MASTER_SHA"
