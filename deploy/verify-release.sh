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
#    日志片段这类原样透出来的内容，也会先把连接串和 IP 换成占位符再打印（见 mask）。
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

# ======================= 遮蔽（mask）开始 =======================
# 这份输出是要被贴回 issue 给人看的，所以凡是把外部内容原样透出来的地方（目前是日志片段）
# 都先过一遍 mask。docs/SECRETS.md 明令：内网 IP 和内网机器名，一个字都不许进这个工作区。
#
# 三层叠着用，不指望任何一层单独管用：
#   第一层（最强，不认句式）：开机时把 $ENV_FILE 里配的真实数据库主机名取出来，
#     输出里只要出现这个字符串就换掉。不管哪个驱动、用哪句话把地址打出来都挡得住，
#     以后数据库挪到别的机器、改用机器名连，也是这一层兜住。
#   第二层（认已知句式）：jdbc 连接串，以及 pgjdbc / libpq 报连不上时那几句固定话术。
#     这几句里的主机既没有 jdbc: 前缀、又不一定是数字 IP，只靠第一、三层会漏。
#   第三层（兜底猜形状）：IPv6、数字 IP，以及「带点的机器名:端口」这种形状。
#     形状规则是跳出「又发现一种写法会漏 → 再加一条后缀」那个死循环的办法：
#     不去猜句式，只认地址长什么样。
#
# 两处不许遮（遮过头日志就没人看得懂了，也不该遮）：
#   · 127.0.0.1 是本机地址，人人都一样，不是内网拓扑 —— 先换成占位符躲开，最后换回来
#   · pay.copliot.cloud 是公开域名，SECRETS.md 里明写「放心写」
#
# 改这里之前先跑一遍 deploy/verify-release.test.sh —— 这个函数是专门用来防泄露的，
# 正则动一个字符都可能开一个口子，而口子不会自己喊疼。

# 取真实数据库主机名。整个取值过程关在子 shell 里，只把主机名带出来，口令绝不外泄。
DB_HOST_REAL=$(
  [ -r "$ENV_FILE" ] || exit 0
  set -a; . "$ENV_FILE" 2>/dev/null; set +a
  printf '%s' "${DB_URL:-}" | sed -nE 's#^jdbc:[a-z]+://([^:/?,]+).*#\1#p'
)
# 太短的名字（比如 db）拿去全局替换会把正常文字也误伤掉，短于 4 个字符就不用这一层。
[ "${#DB_HOST_REAL}" -ge 4 ] || DB_HOST_REAL=''
# 主机名里的点在正则里是通配符，替换前先转义掉
DB_HOST_RE=$(printf '%s' "$DB_HOST_REAL" | sed 's#[][\.*^$/&]#\\&#g')

mask() {
  # 开头先把本机地址藏起来，免得被第三层的 IP / 「名字:端口」规则顺手吃掉。
  # 占位符里不带点，所以后面任何一条规则都不会认领它。
  sed -E 's#\b127\.0\.0\.1\b#@@LOOPBACK@@#g' |
    # 第一层：换掉 env 里配的那个真实主机名。取不到就原样放行，交给下面两层
    #（QA 把这个函数单独抠出去测的时候走的就是 cat 这条路，第二三层照样得管用）
    { if [ -n "${DB_HOST_RE:-}" ]; then sed -E "s#${DB_HOST_RE}#<地址已隐去>#gI"; else cat; fi; } |
    # 第二层 a：jdbc 连接串。吃到空白或引号为止，不在逗号处收手 —— 多主机写法
    # jdbc:postgresql://主机A:5432,主机B:5432/库 在逗号处停下会把后半个主机漏出去
    sed -E 's#(jdbc:[a-z]+://)[^[:space:]"]*#\1<地址已隐去>#g' |
    # 第二层 b：pgjdbc 连不上时的固定话术 "Connection to 主机:端口 refused. ..."。
    # 只吃「带点的名字」或「名字:端口」，免得把 "Connection to the gateway timed out" 里的 the 也吃掉
    sed -E 's#([Cc]onnection to )([A-Za-z0-9_-]+(\.[A-Za-z0-9_-]+)+|[A-Za-z0-9_.-]+:[0-9]{1,5})#\1<地址已隐去>#g' |
    # 第二层 c：libpq 的 connection to server at "主机", port 5432 failed
    sed -E 's#(onnection to server at )"[^"]*"#\1"<地址已隐去>"#g' |
    # 第二层 d：主机名解析不了时会把名字带引号打出来；Java 侧则是 UnknownHostException
    sed -E -e 's#(host name )"[^"]*"#\1"<地址已隐去>"#gI' \
           -e 's#(UnknownHostException:? )[^[:space:],;]+#\1<地址已隐去>#g' |
    # 第三层 a：IPv6。要求出现 :: 或者满 8 段，才不会把日志时间戳 10:11:12.345 当成地址
    sed -E -e 's#\b[0-9a-fA-F]{1,4}(:[0-9a-fA-F]{1,4}){0,6}::([0-9a-fA-F]{1,4}(:[0-9a-fA-F]{1,4}){0,6})?#<IPv6已隐去>#g' \
           -e 's#\b[0-9a-fA-F]{1,4}(:[0-9a-fA-F]{1,4}){7}\b#<IPv6已隐去>#g' |
    # 第三层 b：数字 IP
    sed -E 's#([0-9]{1,3}\.){3}[0-9]{1,3}#<IP已隐去>#g' |
    # 第三层 c：兜底的「带点的机器名:端口」。堆栈里的 HikariPool.java:554 是源码行号不是地址，
    # 先把这种冒号临时换成 \x01 躲开，兜底规则跑完再换回来
    sed -E -e 's#\.(java|kt|scala|groovy|js|mjs|ts|tsx|py|rb|go|php|cs|c|cpp|h|xml|yml|yaml|sql|json|properties):([0-9]+)#.\1\x01\2#g' \
           -e 's#\b[A-Za-z0-9_-]+(\.[A-Za-z0-9_-]+)+:[0-9]{1,5}#<地址已隐去>#g' \
           -e 's#\x01#:#g' |
    # 最后把本机地址换回来
    sed -E 's#@@LOOPBACK@@#127.0.0.1#g'
}
# ======================= 遮蔽（mask）结束 =======================

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

  # 判断依据是「**看见了预期的东西才算过**」，不是「没看见我认识的报错就算过」。
  #
  # 以前是按报错措辞匹配（error / failed / could not…）。那等于默认「查不出毛病就是好的」，
  # 而同一件事在不同机器上会说出不同的话：中文机器报「未找到命令」、env 漏配口令时
  # 子 shell 被 set -u 打断、DB_OUT 干脆是空的 —— 这些都匹配不上，于是打出
  # 「数据库连得上」，紧接着又报「V1_1 没生效，补跑迁移脚本」。
  # 数据库明明好好的，却把人指去重跑迁移 —— 方向正好指反，而且指向一个会改库的动作。
  #
  # 所以反过来：psql 必须真的把 username= 那一行查回来，才算连上。其余一律算查不了。
  elif echo "$DB_OUT" | grep -q '^username='; then
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

  else
    # 走到这里 = psql 没把预期的结果查回来。可能是没装客户端、连不上、env 漏配口令
    # 让子 shell 直接断掉（DB_OUT 是空的），也可能是报错是中文、我们认不出来。
    # 不管是哪种，一律只说「查不了」：不说「连得上」，也绝不说「V1_1 没生效」。
    # 下面只是尽量把话说具体一点，认不出来就照实说认不出来。
    if echo "$DB_OUT" | grep -qiE 'command not found|未找到命令|没有那个文件或目录|not found'; then
      no "这台机器上没装 psql 客户端，数据库这一项查不了 —— 「V1_1 到位没」「V1_2 到位没」这两项这次也没查"
      printf '         装上再跑一遍这个脚本：Debian/Ubuntu  apt-get install -y postgresql-client\n'
      printf '                               CentOS/Rocky   dnf install -y postgresql\n'
      printf '         ⚠️ 这不代表数据库有问题 —— 脚本压根没连过库。别去动数据库，也别去补跑迁移脚本。\n'
    elif [ -z "$DB_OUT" ]; then
      # 一个字都没有：多半是 env 里少了 DB_PASSWORD / DB_USERNAME，
      # set -u 把子 shell 当场打断，报错走的是脚本自己的 stderr，进不到 DB_OUT 里。
      no "数据库这一项查不了：psql 一个字都没查回来 —— 「V1_1 到位没」「V1_2 到位没」这两项这次也没查"
      printf '         先检查 %s 里 DB_USERNAME / DB_PASSWORD 是不是漏配了，补全再跑一遍。\n' "$ENV_FILE"
      printf '         ⚠️ 这不代表数据库有问题 —— 脚本没查成任何东西。别去动数据库，也别去补跑迁移脚本。\n'
    else
      # 故意不打印 psql 的原始报错：它里面带着数据库地址和账号名
      # （形如 connection to server at "10.0.x.x", port 5432 failed: ... for user "xxx"），
      # 而这份输出经常被贴回 issue 给人看，docs/SECRETS.md 明令内网地址一个字都不许进去。
      # 所以只给不含地址的归类提示，要看原文自己 SSH 上去手工跑一次 psql。
      # 分得细一点，人一眼能知道该去查哪儿。中文报错也认几句，认不出来就照实说认不出来。
      case "$DB_OUT" in
        *"password authentication failed"*|*"身份验证失败"*)   DB_HINT='账号或口令不对' ;;
        *"does not exist"*|*"不存在"*)                         DB_HINT='库名或账号不存在' ;;
        *"could not translate host name"*|*"Name or service not known"*|*"UnknownHostException"*|*"无法解析"*)
                                                               DB_HINT='主机名解析不了（DNS 或 /etc/hosts）' ;;
        *"timeout expired"*|*"timed out"*|*"超时"*)            DB_HINT='连接超时（多半是网络或防火墙）' ;;
        *"Connection refused"*|*"could not connect"*|*"no route to host"*|*"拒绝连接"*)
                                                               DB_HINT='端口连不上（数据库可能没起来）' ;;
        *)                                                     DB_HINT='原因不明（报错认不出来，可能是中文或别的语言）' ;;
      esac
      no "数据库这一项查不了：$DB_HINT —— 「V1_1 到位没」「V1_2 到位没」这两项这次也没查"
      printf '         原始报错里带着服务器地址和账号，按规矩不打印；要看原文自己 SSH 上去手工跑一次 psql（连接信息在 %s）。\n' "$ENV_FILE"
      printf '         ⚠️ 这不代表迁移脚本没跑 —— 脚本没连上库，什么都没查到。别去补跑迁移脚本。\n'
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
    warn "最近 500 行日志里有 $ERR_N 条 ERROR，看一眼是不是这次带出来的（地址已隐去，完整原文上服务器看）："
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
