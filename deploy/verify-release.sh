#!/usr/bin/env bash
#
# 发版验证脚本 —— 发完新版本之后跑这一个命令，它把「机器能替你确认的事」全部确认一遍。
#
# 用法（在自己电脑上跑，不用先登服务器）：
#
#     ssh root@<服务器> 'bash -s v1.5.0' < deploy/verify-release.sh
#
# 已经登在服务器上了也可以直接跑：
#
#     bash /root/fastpay-ops/verify-release.sh v1.5.0
#
# 参数：这次发的版本号（git tag），例如 v1.5.0。不传就跳过版本号比对。
#
# 退出码：全绿 = 0；有任何一项 FAIL = 1。可以直接串在部署命令后面。
#
# ⚠️ 这个脚本只读，不改任何东西：不写数据库、不改配置、不重启服务。
# ⚠️ 它读 /etc/fastpay/fastpay-server.env 拿数据库地址，但绝不会把里面的值打印出来。
#
# 这个脚本验不了什么，别误以为它全包了：
#   它只能确认「发上去的是对的版本、系统活着、结构对得上」，
#   确认不了「这一版新改的功能到底对不对」—— 那个还得人登进去点一遍。
#   完整说明见 docs/RELEASE_VERIFY.md。

set -uo pipefail

EXPECT_VER="${1:-}"

APP_DIR=/opt/fastpay
JAR="$APP_DIR/fastpay-server-1.0.0.jar"
ENV_FILE=/etc/fastpay/fastpay-server.env
VER_FILE="$APP_DIR/DEPLOYED_VERSION"
LOG_FILE="$APP_DIR/logs/fastpay-server.log"
SERVICE=fastpay-server
LOCAL_BASE="http://127.0.0.1:7001/fastpay-server"
PUBLIC_BASE="https://pay.copliot.cloud"

PASS_N=0
FAIL_N=0
WARN_N=0

# 通过 ssh 跑的时候 stdout 不是终端，这时不输出颜色码，免得日志里全是乱码
if [ -t 1 ]; then
  C_OK=$'\033[32m'; C_NO=$'\033[31m'; C_WARN=$'\033[33m'; C_B=$'\033[1m'; C_OFF=$'\033[0m'
else
  C_OK=''; C_NO=''; C_WARN=''; C_B=''; C_OFF=''
fi

ok()   { PASS_N=$((PASS_N+1)); printf '%s  ✅ PASS%s  %s\n' "$C_OK" "$C_OFF" "$1"; }
no()   { FAIL_N=$((FAIL_N+1)); printf '%s  ❌ FAIL%s  %s\n' "$C_NO" "$C_OFF" "$1"; }
warn() { WARN_N=$((WARN_N+1)); printf '%s  ⚠️  注意%s  %s\n' "$C_WARN" "$C_OFF" "$1"; }
head_() { printf '\n%s%s%s\n' "$C_B" "$1" "$C_OFF"; }

printf '\n===== FastPay 发版验证 =====\n'
printf '服务器：%s\n' "$(hostname)"
printf '时间：  %s\n' "$(date '+%F %T %Z')"
[ -n "$EXPECT_VER" ] && printf '本次要发的版本：%s\n' "$EXPECT_VER" \
                     || printf '本次要发的版本：（没传，跳过版本比对）\n'

# ---------------------------------------------------------------- 1. 后端服务
head_ '一、后端服务'

if systemctl is-active --quiet "$SERVICE"; then
  ok "systemd 服务 $SERVICE 正在运行"
else
  no "systemd 服务 $SERVICE 没在运行 —— 先看 'journalctl -u $SERVICE -n 100'"
fi

# 换了 jar 却忘了重启，是最常见也最难发现的一种「发了个寂寞」。
# 服务启动时间必须晚于 jar 的修改时间，否则跑的还是旧代码。
if [ -f "$JAR" ]; then
  JAR_TS=$(stat -c '%Y' "$JAR")
  SVC_RAW=$(systemctl show "$SERVICE" -p ActiveEnterTimestamp --value)
  SVC_TS=$(date -d "$SVC_RAW" +%s 2>/dev/null || echo 0)
  if [ "$SVC_TS" -eq 0 ]; then
    warn "读不到服务启动时间，没法确认「换完 jar 有没有重启」，请手工看一眼"
  elif [ "$SVC_TS" -gt "$JAR_TS" ]; then
    ok "服务是在 jar 更新之后启动的（jar $(date -d @"$JAR_TS" '+%F %T') → 启动 $(date -d @"$SVC_TS" '+%F %T')）"
  else
    no "jar 比服务启动时间还新 —— 说明换了 jar 没重启，现在跑的还是旧代码！执行 'systemctl restart $SERVICE'"
  fi
else
  no "找不到后端程序 $JAR"
fi

# ---------------------------------------------------------------- 2. 版本号
head_ '二、发上去的到底是哪一版'

if [ -f "$VER_FILE" ]; then
  DEPLOYED_VER=$(head -n1 "$VER_FILE" | tr -d '[:space:]')
  if [ -z "$EXPECT_VER" ]; then
    ok "服务器记录的当前版本：$DEPLOYED_VER"
  elif [ "$DEPLOYED_VER" = "$EXPECT_VER" ]; then
    ok "版本对得上：$DEPLOYED_VER"
  else
    no "版本对不上 —— 服务器上记的是 $DEPLOYED_VER，你说要发的是 $EXPECT_VER"
  fi
else
  warn "还没有版本标记文件 $VER_FILE。部署时加一句就有了：echo '<版本号>' > $VER_FILE"
fi

# ---------------------------------------------------------------- 3. 接口通不通
head_ '三、接口通不通'

code=$(curl -s -o /dev/null -w '%{http_code}' --max-time 15 "$LOCAL_BASE/api/health" || echo 000)
[ "$code" = "200" ] && ok "后端本机健康检查 200（$LOCAL_BASE/api/health）" \
                    || no "后端本机健康检查返回 $code，期望 200 —— 后端没起来或起在别的端口"

for path in /fastpay-admin/ /fastpay-merchant/; do
  code=$(curl -s -o /dev/null -w '%{http_code}' --max-time 20 "$PUBLIC_BASE$path" || echo 000)
  [ "$code" = "200" ] && ok "公网页面 $path 返回 200" \
                      || no "公网页面 $path 返回 $code，期望 200"
done

code=$(curl -s -o /dev/null -w '%{http_code}' --max-time 20 "$PUBLIC_BASE/fastpay-server/api/health" || echo 000)
[ "$code" = "200" ] && ok "公网健康检查 200（说明 nginx 转发也是通的）" \
                    || no "公网健康检查返回 $code，期望 200 —— 后端活着但 nginx 转发有问题"

# 接口文档必须是关着的：它会把全部接口结构暴露出去。
code=$(curl -s -o /dev/null -w '%{http_code}' --max-time 20 "$PUBLIC_BASE/fastpay-server/v3/api-docs" || echo 000)
[ "$code" = "404" ] && ok "接口文档对外是关着的（返回 404，符合预期）" \
                    || no "接口文档返回 $code，期望 404 —— 排查完忘了关回去？检查 SPRINGDOC_API_DOCS_ENABLED 和 nginx"

# ---------------------------------------------------------------- 4. 文件权限
head_ '四、前端文件权限'

# Windows 打的 tar 包解压到 Linux 会变成全局可写，等于谁都能改支付页和登录页。
WRITABLE=$(find "$APP_DIR" -perm /o+w 2>/dev/null | wc -l)
if [ "$WRITABLE" -eq 0 ]; then
  ok "没有任何全局可写的文件"
else
  no "有 $WRITABLE 个文件是全局可写的（谁都能改支付页）！补跑：chmod -R go-w $APP_DIR/www && chmod 755 $APP_DIR/www/fastpay-admin $APP_DIR/www/fastpay-merchant"
  find "$APP_DIR" -perm /o+w 2>/dev/null | head -5 | sed 's/^/         /'
fi

# ---------------------------------------------------------------- 5. 数据库
head_ '五、数据库'

if [ ! -r "$ENV_FILE" ]; then
  no "读不到配置文件 $ENV_FILE（要用 root 跑这个脚本）"
else
  # 只在子 shell 里 source，避免把口令泄到后面的输出里
  DB_OUT=$(
    set -a; . "$ENV_FILE"; set +a
    export PGPASSWORD="$DB_PASSWORD"
    eval "$(echo "$DB_URL" | sed -nE 's#^jdbc:postgresql://([^:/]+):([0-9]+)/([^?]+).*#DBH=\1 DBP=\2 DBN=\3#p')"
    if [ -z "${DBH:-}" ] || [ -z "${DBP:-}" ] || [ -z "${DBN:-}" ]; then
      echo "PARSE_FAIL"; exit 0
    fi
    psql -h "$DBH" -p "$DBP" -U "$DB_USERNAME" -d "$DBN" -Atq -c \
      "SELECT column_name || '=' || COALESCE(character_maximum_length::text,'n/a')
         FROM information_schema.columns
        WHERE table_schema='public' AND table_name='fp_admin'
          AND column_name IN ('username','password','token_version')
        UNION ALL
       SELECT 'merchant_password=' || COALESCE(character_maximum_length::text,'n/a')
         FROM information_schema.columns
        WHERE table_schema='public' AND table_name='fp_merchant' AND column_name='password';" 2>&1
  )

  if [ "$DB_OUT" = "PARSE_FAIL" ]; then
    no "$ENV_FILE 里的 DB_URL 格式不对，应形如 jdbc:postgresql://主机:端口/库名"
  elif echo "$DB_OUT" | grep -qiE 'error|failed|could not'; then
    no "连不上数据库。原始报错：$(echo "$DB_OUT" | head -2 | tr '\n' ' ')"
  else
    ok "数据库连得上"
    # V1_1：token_version 列必须在（少了后端根本起不来，这里再确认一次）
    echo "$DB_OUT" | grep -q '^token_version=' \
      && ok "V1_1 已生效：fp_admin.token_version 列存在" \
      || no "V1_1 没生效：fp_admin 缺 token_version 列 —— 补跑 V1_1 迁移脚本"
    # V1_2：两个 password 字段都要放宽到 255，容纳 bcrypt
    ADM_PW=$(echo "$DB_OUT" | sed -n 's/^password=//p')
    MCH_PW=$(echo "$DB_OUT" | sed -n 's/^merchant_password=//p')
    if [ "${ADM_PW:-0}" -ge 255 ] 2>/dev/null && [ "${MCH_PW:-0}" -ge 255 ] 2>/dev/null; then
      ok "V1_2 已生效：两个 password 字段都已放宽到 $ADM_PW / $MCH_PW"
    else
      warn "V1_2 可能没跑：password 字段宽度是 ${ADM_PW:-?} / ${MCH_PW:-?}，期望 ≥255。bcrypt 是 60 字符，暂时不会出故障，但请补跑 V1_2"
    fi
  fi
fi

# ---------------------------------------------------------------- 6. 日志
head_ '六、日志'

if [ -r "$LOG_FILE" ]; then
  ERR_N=$(tail -n 500 "$LOG_FILE" | grep -c ' ERROR ' || true)
  if [ "$ERR_N" -eq 0 ]; then
    ok "最近 500 行日志里没有 ERROR"
  else
    warn "最近 500 行日志里有 $ERR_N 条 ERROR，看一眼是不是这次带出来的："
    tail -n 500 "$LOG_FILE" | grep ' ERROR ' | tail -3 | cut -c1-160 | sed 's/^/         /'
  fi
else
  warn "读不到日志文件 $LOG_FILE"
fi

# ---------------------------------------------------------------- 汇总
printf '\n===== 结果：%d 项通过，%d 项失败，%d 项要注意 =====\n' "$PASS_N" "$FAIL_N" "$WARN_N"

if [ "$FAIL_N" -gt 0 ]; then
  printf '\n%s这一版不能算发好了。%s 上面标 ❌ 的每一条都得处理掉，处理完再跑一遍这个脚本。\n' "$C_NO" "$C_OFF"
  printf '实在修不好就回退到上一个 tag，回退步骤见 docs/DEPLOY.md 第四节。\n\n'
  exit 1
fi

printf '\n%s机器能替你确认的部分，全部正常。%s\n' "$C_OK" "$C_OFF"
printf '还差最后一步（机器验不了，得人来）：登进去把这一版改的功能点一遍。\n'
printf '用哪个账号、点什么，见 docs/RELEASE_VERIFY.md。\n\n'
exit 0
