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
# ⚠️ 它读 /etc/fastpay/fastpay-server.env 拿数据库地址，但绝不会把里面的值打印出来；
#    日志片段这类原样透出来的内容，也会先把连接串和 IP 换成占位符再打印（见下面的 mask）。
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

# 这份输出是要被贴回 issue 给人看的，所以凡是把服务器上的原文原样透出来的内容
# （目前只有日志片段）都先过一遍这个，把地址换成占位符。
# docs/SECRETS.md 第 36 行那份禁止清单里，跟 10.x.x.x 并排的还有「内网机器名」，
# 所以四种形态都要挡，缺一种就等于没挡：
#   1) jdbc 连接串       后端报数据库的错时整段都带着它
#   2) IPv4
#   3) 内网域名的机器名   pg-prod-01.fastpay.internal 这种
#   4) IPv6              fd00:1234:5678::17 这种
# 顺序不能反：先把整条 jdbc 串换掉，后面的规则才不会在已经隐去的内容上再咬一口。
#
# 两条「不许误伤」的边界，改这里之前先想清楚：
#   · IPv6 那条要求至少 4 段，是为了避开行首的时间戳 —— 15:01:22 只有两个冒号，
#     卡在 3 段以下就伤不到它；而真 IPv6 地址都在 4 段以上。
#   · Java 包名（c.z.h.pool.HikariPool）里没有冒号、也不以内网域名结尾，两条都碰不到它。
# 注意 mask 只是个 sed 过滤器（没有 -i），不改任何文件，脚本仍然是只读的。
mask() {
  sed -E -e 's#(jdbc:[a-z]+://)[^[:space:],;"]*#\1<地址已隐去>#g' \
         -e 's#([0-9]{1,3}\.){3}[0-9]{1,3}#<IP已隐去>#g' \
         -e 's#[A-Za-z0-9_-]+(\.[A-Za-z0-9_-]+)*\.(internal|intranet|local|localdomain|lan|corp)\b#<内网机器名已隐去>#g' \
         -e 's#[0-9a-fA-F]{0,4}(:[0-9a-fA-F]{0,4}){3,}#<IPv6已隐去>#g'
}

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
  # 用 >= 不用 >：中间隔着 scp 和 sleep 15，正常不会撞在同一秒，
  # 但真撞上了也是"传完就重启了"，不该报成"换了 jar 没重启"。
  elif [ "$SVC_TS" -ge "$JAR_TS" ]; then
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
elif [ -n "$EXPECT_VER" ]; then
  # 你传了版本号，就是在要求「帮我核对是不是这一版」。核不了却报全绿，等于骗人 —— 判失败。
  no "没有版本标记文件 $VER_FILE，核对不了是不是 $EXPECT_VER —— 部署时漏了这一步：ssh root@<服务器> \"echo $EXPECT_VER > $VER_FILE\"（见 docs/DEPLOY.md 第三节第 4 步）"
else
  warn "还没有版本标记文件 $VER_FILE，你也没传版本号，这一项跳过。部署时加一句就有了：echo '<版本号>' > $VER_FILE"
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
  # 最常见的一种「psql 没跑成」单独拎出来，好给一句能直接照做的话（装客户端）。
  # 注意它只是锦上添花：底下那道正向断言才是真正兜底的，这里认不出来也漏不掉。
  elif echo "$DB_OUT" | grep -qi 'command not found'; then
    no "这台机器上没装 psql 客户端，数据库这一整节一项都没查到（这不代表数据库有问题，别去动数据库和迁移脚本）。装上客户端再重跑本脚本：Debian/Ubuntu 'apt-get install -y postgresql-client'；CentOS/Rocky 'dnf install -y postgresql'"
  elif echo "$DB_OUT" | grep -qiE 'error|failed|could not'; then
    # 故意不打印 psql 的原始报错：它里面带着数据库地址和账号名
    # （形如 connection to server at "10.0.x.x", port 5432 failed: ... for user "xxx"），
    # 而这份输出经常被贴回 issue 给人看，docs/SECRETS.md 明令内网地址一个字都不许进去。
    # 所以只给不含地址的归类提示，要看原文自己 SSH 上去手工跑一次 psql。
    case "$DB_OUT" in
      *"password authentication failed"*)  DB_HINT='账号或口令不对' ;;
      *"does not exist"*)                  DB_HINT='库名或账号不存在' ;;
      *"Connection refused"*|*"could not connect"*|*"could not translate"*|*"timeout expired"*|*"no route to host"*)
                                           DB_HINT='连不上（地址、端口、防火墙，或者数据库没起来）' ;;
      *)                                   DB_HINT='其他错误' ;;
    esac
    no "连不上数据库：$DB_HINT。原始报错里带着服务器地址，这里不打印；要看原文就自己 SSH 上去手工跑一次 psql（连接信息在 $ENV_FILE）"
  # ---- 兜底的正向断言：查到了预期的东西才算连上 ----
  # 上面几条都是「认得出的报错」，靠的是报错措辞。但同一件事在不同机器上说的话不一样：
  # 中文机器说「未找到命令」、日文机器说「コマンドが見つかりません」、装了但没执行权限说
  # 「Permission denied」、漏配 DB_PASSWORD 时 set -u 直接打断子 shell、DB_OUT 干脆是空的。
  # 这些措辞一条都对不上，靠「没看见我认识的报错就算过」必然漏，漏了就打出「数据库连得上」
  # 并接着叫人去补跑迁移脚本 —— 数据库好好的却把人指去动生产库，正是本脚本最该避免的事。
  # 所以最后这一道反过来判：psql 真跑成了，输出里一定有 fp_admin 的 username 这一行；
  # 没有它就一律判失败，不管报错是什么措辞、什么语言、还是一个字都没输出。
  elif ! echo "$DB_OUT" | grep -q '^username='; then
    no "psql 没跑成，数据库这一整节一项都没查到（这不代表数据库有问题，别去动数据库和迁移脚本）。原始报错可能带着地址，按规矩不打印；要看原文就自己 SSH 上去手工跑一次 psql（连接信息在 $ENV_FILE），也看一眼上面有没有 shell 报错"
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
    warn "最近 500 行日志里有 $ERR_N 条 ERROR，看一眼是不是这次带出来的（地址已隐去，要看原文上服务器）："
    # 先 mask 再 cut：顺序反了的话，160 字符正好把连接串截成两半，前半截地址照样露出来。
    tail -n 500 "$LOG_FILE" | grep ' ERROR ' | tail -3 | mask | cut -c1-160 | sed 's/^/         /'
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
