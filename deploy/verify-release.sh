#!/usr/bin/env bash
#
# 发版验证脚本 —— 发完新版本之后跑这一个命令，它把「机器能替你确认的事」全部确认一遍。
#
# 用法（在自己电脑上跑，不用先登服务器）：
#
#     ssh root@<服务器> 'bash -s v1.6.0 MTM-210 prod' < deploy/verify-release.sh
#
# 已经登在服务器上了也可以直接跑：
#
#     bash /root/fastpay-ops/verify-release.sh v1.6.0 MTM-210 prod
#
# 参数 1：这次发的版本号（git tag），例如 v1.6.0。不传就跳过版本号比对。
# 参数 2：这次动生产用的操作锁令牌（就是你的 issue 编号），例如 MTM-210。
#         传了它，脚本会顺便确认「这台机器现在是不是只有你一个人在动」——
#         有别人占着锁就直接判失败。不传的话，默认只是把当前谁在动打出来看看
#         （写了参数 3 的 prod 时更严，见下）。操作锁是什么，见 docs/DEPLOY.md 第零节。
# 参数 3：这台机器是不是生产环境。动生产就写 prod，别的环境写 test / staging / dev，
#         不传就是「没说」。写了 prod 之后，第七节这两种情况会直接判失败，而不是只提醒：
#           · 这台机器上压根没装操作锁 —— 生产上少了这层保护不能悄悄放过去
#           · 你没传参数 2（手上没锁），而这会儿确实有别人占着锁在动这台机器
#         没人占锁时照常放行：这个脚本只读，纯粹上来体检一下的人不该被拦（MTM-220）。
#         这三档以外的值一律判失败：拼错了就等于保护失效，而失效是没有任何动静的。
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
LOCK_TOKEN="${2:-}"
ENV_DECL="${3:-}"      # 这台机器是不是生产环境：prod / test / staging / dev / 不传

OPS_DIR=/root/fastpay-ops
LOCK_INFO="$OPS_DIR/prod-deploy.lock/holder"
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
[ -n "$ENV_DECL" ] && printf '这台机器是：    %s\n' "$ENV_DECL" \
                   || printf '这台机器是：    （没说是不是生产，第 7 项按宽松处理）\n'

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

# ======================= 迁移检查（migration）开始 =======================
# 「数据库脚本（迁移脚本）漏跑了没有」这一项，靠下面这张表判。
#
# 以前这里是手写的两段 if，只认 V1_1 和 V1_2。后来加了 V1_3 / V1_4 / V1_5，没人记得
# 回来改这里，于是这三版漏跑了脚本照样报绿 —— 而 V1_4 漏跑就等于那个「两人同价撞单
# 认错人」的赔钱 bug 修复根本没生效，跑完验证的人还以为一切正常（MTM-214）。
# 所以改成一张表：
#
#     ★ 以后每加一版迁移脚本，只在这张表里加一行，下面的代码一个字都不用动。★
#
# 每行七段，用 | 隔开。为了对齐加的空格会被自动去掉；`#` 开头的行是注释，会被跳过。
#
#   1 版本号     写成 V1_3 这样，直接出现在给人看的结果里
#   2 怎么查     table  = 这张表在不在
#                column = 这张表有没有这一列
#                width  = 这一列够不够宽
#   3 表名
#   4 列名       table 这一行留空
#   5 最小宽度   只有 width 用得上，其余留空
#   6 判多重     fail = 判失败，脚本退出码 1；warn = 只提醒，不拦
#   7 说人话     没通过时打给人看的那句话。写清楚「缺什么、补跑哪个脚本、不补会怎样」
#
# 第 6 段现在**每一行都是 fail**。以前 V1_2 那两条是 warn（bcrypt 只有 60 字符，老的
# 64 位宽度眼下还装得下，不会当场出故障），但同一节检查里留一项只提醒，看的人会慢慢
# 学会「这一节黄一下没关系」，那整节的拦截力就没了。而且这一项本来就不会误报：线上早
# 就跑过 V1_2 了，真亮起来说明数据库处在没人预期的状态，那本来就该停下来看一眼（MTM-227）。
#
# warn 这一档代码里还留着，但**眼下一行都没在用**。将来真要用它，门槛是：漏跑了当天
# 不会出任何故障，而且能说清楚「什么时候必须补上」—— 说不清楚就写 fail。
#
# 改这一段之前先跑一遍 deploy/verify-release.test.sh。这里最要命的错法是「该判 FAIL
# 的判成了 PASS」—— 那等于告诉发版的人「迁移都跑过了」，而他不会再去核对第二遍。
MIGRATION_CHECKS='
# 版本 | 怎么查 | 表名                  | 列名          | 最小宽度 | 判多重 | 没通过时说什么
  V1_1 | column | fp_admin              | token_version |          | fail | fp_admin 缺 token_version 列 —— 补跑 V1_1，漏了后端根本起不来
  V1_1 | width  | fp_admin              | username      | 100      | fail | fp_admin.username 不够宽（要 ≥100）—— 补跑 V1_1，漏了邮箱格式的管理员账号存不进去
  V1_2 | width  | fp_admin              | password      | 255      | fail | fp_admin.password 不够宽（要 ≥255）—— 补跑 V1_2，漏了以后换更长的加密算法会写不进去，而且说明这个库不在预期状态
  V1_2 | width  | fp_merchant           | password      | 255      | fail | fp_merchant.password 不够宽（要 ≥255）—— 补跑 V1_2，同上
  V1_3 | column | fp_pay_order          | notify_result |          | fail | fp_pay_order 缺 notify_result 列 —— 补跑 V1_3，漏了谁点开订单详情或订单列表都会报错
  V1_3 | column | fp_pay_order          | notify_error  |          | fail | fp_pay_order 缺 notify_error 列 —— 补跑 V1_3，同上
  V1_4 | table  | fp_pending_pay_amount |               |          | fail | 缺 fp_pending_pay_amount 表 —— 补跑 V1_4，漏了那个「两人同价撞单认错人」的赔钱 bug 原样还在
  V1_4 | table  | fp_unmatched_notify   |               |          | fail | 缺 fp_unmatched_notify 表 —— 补跑 V1_4，漏了「钱到了但认不到订单」的记录存不下来，后端会直接报错
  V1_5 | width  | fp_pay_order          | notify_url    | 2000     | fail | fp_pay_order.notify_url 不够宽（要 ≥2000）—— 补跑 V1_5，漏了长回调地址写不进去，用户看到「支付通道错误」
  V1_5 | width  | fp_pay_order          | return_url    | 2000     | fail | fp_pay_order.return_url 不够宽（要 ≥2000）—— 补跑 V1_5，同上
  V1_5 | width  | fp_merchant           | notify_url    | 2000     | fail | fp_merchant.notify_url 不够宽（要 ≥2000）—— 补跑 V1_5，同上
  V1_5 | width  | fp_merchant           | return_url    | 2000     | fail | fp_merchant.return_url 不够宽（要 ≥2000）—— 补跑 V1_5，同上
'

# 把表整理成干净的一行行：去掉对齐用的空格、去掉注释行和空行
mig_rows() {
  printf '%s\n' "$MIGRATION_CHECKS" |
    sed -e 's#[[:space:]]*|[[:space:]]*#|#g' -e 's#^[[:space:]]*##' -e 's#[[:space:]]*$##' |
    grep -v -e '^$' -e '^#'
}

# 表里一共管着哪几版，按出现顺序，用于「这次没查成」时把话说全
mig_versions() {
  mig_rows | cut -d'|' -f1 | awk '!seen[$0]++ { printf "%s%s", (n++ ? " / " : ""), $0 }'
}

# 把表翻译成一条 SQL。每条检查查回来一行「键=值」；表/列不存在就没有那一行，
# 判定时按「缺」处理。全部查的是 information_schema，只读，且表不存在也不会报错。
mig_sql() {
  # 开头这行是探针：只要 psql 真连上并把查询跑完了，它一定有一行返回。
  # 「数据库连得上」认的就是这一行 —— 不跟任何一张业务表绑在一起，
  # 免得「库是空的」被误报成「连不上」，把人往错的方向指。
  printf "SELECT 'PROBE=' || COUNT(*) FROM information_schema.tables"
  printf " WHERE table_schema='public' AND table_name IN ('fp_admin','fp_merchant','fp_pay_order')"
  mig_rows | while IFS='|' read -r ver kind tbl col minw sev msg; do
    if [ "$kind" = table ]; then
      printf " UNION ALL SELECT 'T:%s=' || COUNT(*) FROM information_schema.tables" "$tbl"
      printf " WHERE table_schema='public' AND table_name='%s'" "$tbl"
    else
      printf " UNION ALL SELECT 'C:%s.%s=' || COALESCE(character_maximum_length::text,'n/a')" "$tbl" "$col"
      printf " FROM information_schema.columns"
      printf " WHERE table_schema='public' AND table_name='%s' AND column_name='%s'" "$tbl" "$col"
    fi
  done
  printf ';\n'
}

# 从查回来的结果里取某个键的值；这个键压根没查回来就是空字符串
mig_value() { # mig_value <键>
  printf '%s\n' "$DB_OUT" | awk -v k="$1" 'index($0, k "=") == 1 { print substr($0, length(k) + 2); exit }'
}

# 单条检查过没过，过了返回 0
mig_ok() { # mig_ok <怎么查> <查回来的值> <最小宽度>
  case "$1" in
    table)  [ "${2:-0}" -ge 1 ] 2>/dev/null ;;
    column) [ -n "$2" ] ;;
    width)  if   [ -z "$2" ];      then return 1   # 列压根不在
            elif [ "$2" = 'n/a' ]; then return 0   # 不限长度的类型（TEXT 之类），算过
            else [ "$2" -ge "$3" ] 2>/dev/null; fi ;;
    *)      return 1 ;;
  esac
}

# 一版打一行结论：这一版的检查全过 → PASS；有没过的 → 按表里写的判 FAIL 或提醒
mig_report() {
  local vers ver v kind tbl col minw sev msg key val good bad_hard bad_soft
  vers=$(mig_rows | cut -d'|' -f1 | awk '!seen[$0]++')
  for ver in $vers; do
    good=''; bad_hard=''; bad_soft=''
    while IFS='|' read -r v kind tbl col minw sev msg; do
      [ "$v" = "$ver" ] || continue
      if [ "$kind" = table ]; then key="T:$tbl"; else key="C:$tbl.$col"; fi
      val=$(mig_value "$key")
      if mig_ok "$kind" "$val" "$minw"; then
        good="$good ${key#*:}"
      elif [ "$sev" = fail ]; then
        bad_hard="$bad_hard；$msg"
      else
        bad_soft="$bad_soft；$msg"
      fi
    done < <(mig_rows)

    if [ -n "$bad_hard" ]; then
      no "$ver 没生效：${bad_hard#；}"
    elif [ -n "$bad_soft" ]; then
      warn "$ver 可能没跑：${bad_soft#；}"
    else
      ok "$ver 已生效：${good# } 都对得上"
    fi
  done
}
# ======================= 迁移检查（migration）结束 =======================

# ---------------------------------------------------------------- 5. 数据库
head_ '五、数据库'

if [ ! -r "$ENV_FILE" ]; then
  no "读不到配置文件 $ENV_FILE（要用 root 跑这个脚本）"
else
  # 查什么由上面那张迁移检查表自动生成，这里不再手写任何表名列名
  MIG_SQL=$(mig_sql)
  # 只在子 shell 里 source，避免把口令泄到后面的输出里
  DB_OUT=$(
    set -a; . "$ENV_FILE"; set +a
    export PGPASSWORD="$DB_PASSWORD"
    eval "$(echo "$DB_URL" | sed -nE 's#^jdbc:postgresql://([^:/]+):([0-9]+)/([^?]+).*#DBH=\1 DBP=\2 DBN=\3#p')"
    if [ -z "${DBH:-}" ] || [ -z "${DBP:-}" ] || [ -z "${DBN:-}" ]; then
      echo "PARSE_FAIL"; exit 0
    fi
    psql -h "$DBH" -p "$DBP" -U "$DB_USERNAME" -d "$DBN" -Atq -c "$MIG_SQL" 2>&1
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
  # 所以反过来：psql 必须真的把探针那一行查回来，才算连上。其余一律算查不了。
  elif echo "$DB_OUT" | grep -q '^PROBE='; then
    ok "数据库连得上"
    # 连上了，但库里一张业务表都没有 = 多半连错库了。这时候把「各版迁移都没生效」
    # 一条条报出来毫无意义，还会把人指去对着错的库跑迁移脚本 —— 又是方向指反。
    if [ "$(mig_value PROBE)" = "0" ]; then
      no "连是连上了，但这个库里一张业务表都没有（fp_admin / fp_merchant / fp_pay_order 全不在）—— 十有八九是 $ENV_FILE 里 DB_URL 的库名写错了，连到别的库上去了。先把库名核对清楚，别急着跑迁移脚本"
      printf '         「数据库脚本漏跑了没有」（%s）这几项这次也没查。\n' "$(mig_versions)"
    else
      # 各版迁移到位没有，由上面那张表自动判，一版一行
      mig_report
    fi

  else
    # 走到这里 = psql 没把预期的结果查回来。可能是没装客户端、连不上、env 漏配口令
    # 让子 shell 直接断掉（DB_OUT 是空的），也可能是报错是中文、我们认不出来。
    # 不管是哪种，一律只说「查不了」：不说「连得上」，也绝不说「V1_1 没生效」。
    # 下面只是尽量把话说具体一点，认不出来就照实说认不出来。
    if echo "$DB_OUT" | grep -qiE 'command not found|未找到命令|没有那个文件或目录|not found'; then
      no "这台机器上没装 psql 客户端，数据库这一项查不了 —— 「数据库脚本漏跑了没有」（$(mig_versions)）这几项这次也没查"
      printf '         装上再跑一遍这个脚本：Debian/Ubuntu  apt-get install -y postgresql-client\n'
      printf '                               CentOS/Rocky   dnf install -y postgresql\n'
      printf '         ⚠️ 这不代表数据库有问题 —— 脚本压根没连过库。别去动数据库，也别去补跑迁移脚本。\n'
    elif [ -z "$DB_OUT" ]; then
      # 一个字都没有：多半是 env 里少了 DB_PASSWORD / DB_USERNAME，
      # set -u 把子 shell 当场打断，报错走的是脚本自己的 stderr，进不到 DB_OUT 里。
      no "数据库这一项查不了：psql 一个字都没查回来 —— 「数据库脚本漏跑了没有」（$(mig_versions)）这几项这次也没查"
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
      no "数据库这一项查不了：$DB_HINT —— 「数据库脚本漏跑了没有」（$(mig_versions)）这几项这次也没查"
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

# ---------------------------------------------------------------- 7. 有没有别人也在动这台机器
head_ '七、同一时间只有你一个人在动这台机器吗'

# 为什么要查这一项：2026-08-20 那次上线，两路人几乎同时在动同一台生产服务器，
# 线上服务被重启了一次，重启的人不是当时正在操作的那个人。那次运气好没出事，
# 但一边在换文件、另一边在重启，真出问题会极难排查。（背景：MTM-210）
# 上面所有检查都有个隐含前提：**这段时间里没有别人在改这台机器**。
# 前提不成立的话，前面全绿也说明不了什么 —— 你验的可能已经不是你发的那一版了。
#
# 改这一段之前先跑一遍 deploy/verify-release.test.sh。这里最要命的是「该判 FAIL 的
# 判成了 PASS」—— 那等于把并发上线放行了，而放行是不会有任何报错的。
# ======================= 操作锁检查（lock）开始 =======================
# 第 3 个参数说了这是不是生产环境。为什么要有这个参数（MTM-220）：
# 机器上没装 prod-lock.sh 时，这一节原来只提醒一句就过去了。好处是老机器、老流程
# 不会被硬卡住，坏处是万一哪天有人漏装了锁脚本，这层保护就悄悄失效了，没人会发现。
# 折中办法：只有明说了「这是生产」才判失败，其他环境和没说的情况照旧只提醒。
IS_PROD=0; ENV_BAD=''
case "$(printf '%s' "${ENV_DECL:-}" | tr 'A-Z' 'a-z')" in
  '')                          IS_PROD=0 ;;   # 没说，宽松处理
  prod|production|生产)         IS_PROD=1 ;;
  test|staging|dev|测试|预发)    IS_PROD=0 ;;   # 明说了不是生产，宽松处理
  *)                           ENV_BAD="$ENV_DECL" ;;
esac

LOCK_WHO=''; LOCK_TOK=''
if [ -f "$LOCK_INFO" ]; then
  LOCK_TOK=$(sed -n 's/^TOKEN=//p' "$LOCK_INFO" | head -n1)
  LOCK_WHO=$(sed -n 's/^WHO=//p'   "$LOCK_INFO" | head -n1)
fi

# 认不出来的环境值不能当成「没说」放过去 —— 把 prod 拼错了，保护就跟着没了，
# 而这种失效是安安静静的。所以宁可在这儿当场拦下来，让人把参数写对再跑。
if [ -n "$ENV_BAD" ]; then
  no "第 3 个参数「$ENV_BAD」认不出来 —— 只认 prod / production / 生产（生产环境），或者 test / staging / dev（非生产），不传就是没说。拼错了这一项的保护会悄悄失效，所以这里先拦住，改对再跑"
elif [ ! -x "$OPS_DIR/prod-lock.sh" ]; then
  if [ "$IS_PROD" = 1 ]; then
    no "这台机器上还没装操作锁（$OPS_DIR/prod-lock.sh），而你说了这是生产环境 —— 没法确认有没有人跟你同时在动，生产上不能就这么过。装法见 docs/DEPLOY.md 第零节"
  else
    warn "这台机器上还没装操作锁（$OPS_DIR/prod-lock.sh），没法确认有没有人跟你同时在动。安装办法见 docs/DEPLOY.md 第零节（这次没说明是生产环境，所以只提醒；动生产时请把第 3 个参数写成 prod，那时这一项会直接判失败）"
  fi
elif [ -n "$LOCK_TOKEN" ]; then
  if [ -z "$LOCK_TOK" ]; then
    no "你说你这次是拿着 $LOCK_TOKEN 这把锁在动生产，但机器上根本没有人占锁 —— 要么你压根没上锁就动了，要么锁被别人放掉了。两种情况都得先弄清楚，见 docs/DEPLOY.md 第零节"
  elif [ "$LOCK_TOK" = "$LOCK_TOKEN" ]; then
    # 这句话的口径跟 docs/DEPLOY.md 第零节「什么时候放锁」是一套的：锁只保护「机器正在被换」
    # 那一小段，这个脚本是最后一件要占着机器做的事。跑完就放，别占着做后面的人工验证。
    # （原来这里写的是「验完记得放开」，跟新规矩正好相反 —— 人照着文档做，脚本却说反话，
    #   规矩会被脚本自己拆台。MTM-247）
    ok "这台机器现在只有你在动（锁在 $LOCK_TOK / $LOCK_WHO 手上）。这条脚本跑完机器就不用再动了，立刻放开：$OPS_DIR/prod-lock.sh release $LOCK_TOKEN —— 后面登进后台点一遍、截图、写报告都不用占着机器；万一要回退，重新占一次锁就行（见 docs/DEPLOY.md 第零节「什么时候放锁」）"
  else
    no "有别人正在动这台机器！锁在 $LOCK_TOK（$LOCK_WHO）手上，不是你的 $LOCK_TOKEN。两路人同时动同一台生产服务器 = 上面这些检查结果都不可信，立刻停手，去 $LOCK_TOK 那条 issue 下面对一下"
  fi
elif [ -n "$LOCK_TOK" ]; then
  # 你没传锁令牌，但机器上有人占着锁。判不判死，看这是不是生产（MTM-220）：
  # 这个脚本是只读体检，纯粹上来看看服务活着没的人不该被拦 —— 所以不是「写了 prod
  # 又没传令牌」就判死。真危险的只有这一种组合：别人正动着这台生产机器，你却两眼
  # 一抹黑也摸上来了，那上面每一项检查结果都随时会被他改掉。
  # 非生产、或者没说环境，照旧只提醒 —— 一刀切卡死无害操作，大家只会学会「别写 prod」。
  if [ "$IS_PROD" = 1 ]; then
    no "有别人正在动这台生产机器：$LOCK_TOK（$LOCK_WHO），而你手上没锁 —— 上面那些检查结果都不可信，你看到的状态随时会被他改掉。立刻停手，去 $LOCK_TOK 那条 issue 下面对一下；确实轮到你动生产，就先按 docs/DEPLOY.md 第零节上锁，再把锁令牌当第 2 个参数传进来"
  else
    warn "现在有人正在动这台机器：$LOCK_TOK（$LOCK_WHO）。你看到的状态随时可能在变。要让脚本帮你把这一项判死，跑的时候把你的锁令牌当第 2 个参数传进来"
  fi
else
  ok "现在没有人占着操作锁，这台机器是空的"
fi
# ======================= 操作锁检查（lock）结束 =======================

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
