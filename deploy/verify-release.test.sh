#!/usr/bin/env bash
#
# verify-release.sh 的自测。改完那个脚本，先跑这个，再拿去服务器上跑。
#
#     bash deploy/verify-release.test.sh
#
# 全过退出码 0，有一条不过退出码 1。
#
# 这个测试**不需要服务器、不需要数据库、不需要 root**：它把 verify-release.sh 里
# 真正的那几段代码原样抠出来，喂进真实格式的输入，看它吐出什么。抠代码而不是另抄
# 一份，是为了避免「测试测的是另一份代码」—— 脚本改了测试跟着一起变，改漏了就红。
#
# 重点盯的是 mask()：它是专门用来防泄露的，正则动一个字符就可能开一个口子，
# 而口子不会自己喊疼。MTM-208 就是靠人工复核才发现漏了一种句式，不能再指望运气。
#
# 文件里出现的地址全部是 RFC 5737 / RFC 2606 留给举例用的（192.0.2.x、.invalid），
# 不是任何真实内网地址 —— 测试文件本身也受 docs/SECRETS.md 约束。

set -uo pipefail

SRC="$(cd "$(dirname "$0")" && pwd)/verify-release.sh"
[ -r "$SRC" ] || { echo "找不到被测脚本 $SRC"; exit 2; }

TMPDIR_T=$(mktemp -d -t fastpay-verify-test-XXXXXX)
trap 'rm -rf "$TMPDIR_T"' EXIT

# ---------------------------------------------------------------- 抠真代码
cut_block() { # cut_block <起始行正则> <结束行正则>
  sed -n "/$1/,/$2/p" "$SRC"
}
MASK_SRC=$(cut_block '^# =* 遮蔽（mask）开始' '^# =* 遮蔽（mask）结束')
DB_SRC=$(cut_block '^  if \[ "\$DB_OUT" = "PARSE_FAIL" \]; then$' '^  fi$')
LOG_SRC=$(cut_block '^if \[ -r "\$LOG_FILE" \]; then$' '^fi$')
LOCK_SRC=$(cut_block '^# =* 操作锁检查（lock）开始' '^# =* 操作锁检查（lock）结束')
CNT_SRC=$(grep -E '^(ok|no|warn)\(\)' "$SRC")

for pair in "遮蔽段:$MASK_SRC" "数据库段:$DB_SRC" "日志段:$LOG_SRC" "操作锁段:$LOCK_SRC" "计数函数:$CNT_SRC"; do
  [ -n "${pair#*:}" ] || { echo "抠不到「${pair%%:*}」—— verify-release.sh 的结构变了，先修这个测试"; exit 2; }
done

C_OK=''; C_NO=''; C_WARN=''; C_B=''; C_OFF=''
eval "$CNT_SRC"

# ---------------------------------------------------------------- 断言
T=0; F=0
has()    { T=$((T+1)); case "$3" in *"$2"*) printf '  ✅ %s\n' "$1";;
           *) F=$((F+1)); printf '  ❌ %s\n       期望出现：%s\n       实际：%s\n' "$1" "$2" "$3";; esac; }
hasnt()  { T=$((T+1)); case "$3" in *"$2"*) F=$((F+1)); printf '  ❌ %s\n       不该出现：%s\n       实际：%s\n' "$1" "$2" "$3";;
           *) printf '  ✅ %s\n' "$1";; esac; }

# 第一层（拿 env 里的真主机名比对）默认是关的，这样测的就是「只靠模式规则能挡住多少」。
# 关掉它是故意的：模式规则必须自己扛得住，不能全靠第一层兜。
ENV_FILE="$TMPDIR_T/no-such-env"
eval "$MASK_SRC"
m() { printf '%s\n' "$1" | mask; }

echo "===== 一、遮蔽：连接串和 IP（有 jdbc: 前缀的老口子）====="
O=$(m '2026-08-20 10:11:12.345 ERROR 1 --- [main] com.zaxxer.hikari.pool.HikariPool : HikariPool-1 - Exception during pool initialization: Failed to obtain JDBC Connection; url=jdbc:postgresql://192.0.2.10:5432/fastpay?ssl=false user=fastpay')
hasnt "jdbc 串里的 IP 不见了"            '192.0.2.10'               "$O"
hasnt "jdbc 串里的库名不见了"            '5432/fastpay'             "$O"
has   "还看得出是 JDBC 连接出的事"       'jdbc:postgresql://<地址已隐去>' "$O"

O=$(m 'ERROR url=jdbc:postgresql://db-host.example.invalid:5432/fastpay')
hasnt "jdbc 串里的机器名不见了"          'db-host.example.invalid'  "$O"

O=$(m 'ERROR url=jdbc:postgresql://192.0.2.10:5432,192.0.2.11:5432/fastpay?targetServerType=primary')
hasnt "多主机写法：第一个主机不见了"      '192.0.2.10'               "$O"
hasnt "多主机写法：第二个主机也不见了"    '192.0.2.11'               "$O"

echo
echo "===== 二、遮蔽：没有 jdbc: 前缀的驱动原话（MTM-208 第一次验收挑出的口子）====="
# pgjdbc 里写死的句式，出处 org/postgresql/core/v3/ConnectionFactoryImpl：
# "Connection to {0} refused. Check that the hostname and port are correct..."
O=$(m '2026-08-20 10:11:12 ERROR o.p.util.PSQLException: Connection to db-host.example.invalid:5432 refused. Check that the hostname and port are correct and that the postmaster is accepting TCP/IP connections.')
hasnt "Connection to 后面的机器名不见了"  'db-host.example.invalid'  "$O"
has   "还看得出是连不上"                  'refused'                  "$O"

O=$(m 'ERROR PSQLException: Connection to 192.0.2.10:5432 refused.')
hasnt "同一句式里的 IP 也不见了"          '192.0.2.10'               "$O"

O=$(m '2026-08-20 10:11:12 ERROR psql: error: connection to server at "db-host.example.invalid", port 5432 failed: FATAL: password authentication failed')
hasnt "libpq 句式里的机器名不见了"        'db-host.example.invalid'  "$O"

O=$(m 'ERROR could not translate host name "db-host.example.invalid" to address: Name or service not known')
hasnt "解析不了时打出的机器名不见了"      'db-host.example.invalid'  "$O"

O=$(m 'ERROR java.net.UnknownHostException: db-host.example.invalid')
hasnt "UnknownHostException 后面的机器名不见了" 'db-host.example.invalid' "$O"

O=$(m 'ERROR Failed to connect to cache-01.prod.example.invalid:6379, falling back to local')
hasnt "别的服务的内网机器名也兜住了"      'cache-01.prod.example.invalid' "$O"

echo
echo "===== 三、遮蔽：不能误伤正常内容（挡过头就没人看得懂日志了）====="
O=$(m '2026-08-20 10:11:12 ERROR c.b.f.s.NotifyService : 订单 20260820001 回调处理失败: 商户签名不匹配，重试 3 次后放弃')
has   "订单号还在"                        '20260820001'              "$O"
has   "中文报错还在"                      '商户签名不匹配'           "$O"
has   "打日志的类名还在"                  'NotifyService'            "$O"

O=$(m 'ERROR at com.zaxxer.hikari.pool.HikariPool.checkFailFast(HikariPool.java:554) ~[HikariCP-5.0.1.jar:na]')
has   "堆栈里的源码行号没被当成地址"      'HikariPool.java:554'      "$O"

O=$(m 'ERROR Connection to the payment gateway timed out after 30s')
has   "Connection to 后面不是地址时不动它" 'the payment gateway'     "$O"

O=$(m '2026-08-20 10:11:12.345 ERROR 1 --- [http-nio-7001-exec-9] c.b.f.Handler : 处理超时')
has   "时间戳没被当成 主机:端口"          '10:11:12.345'             "$O"
has   "线程名没被误伤"                    'http-nio-7001-exec-9'     "$O"

# 下面这条盯的是「本机地址不许被遮」。127.0.0.1 人人都一样，不是内网拓扑，
# 遮掉之后本机健康检查那行日志就没人看得懂了。这一条曾经真的被遮过头，所以钉死。
O=$(m '后端本机健康检查 http://127.0.0.1:7001/fastpay-server/api/health 返回 200')
has   "本机地址 127.0.0.1 照常显示"       '127.0.0.1'                "$O"
has   "本机地址后面的端口也没被吃掉"      '127.0.0.1:7001'           "$O"
O=$(m 'ERROR Connection to 127.0.0.1:7001 refused')
has   "连本机失败时也不遮本机地址"        '127.0.0.1'                "$O"

echo
echo "===== 三之二、遮蔽：IPv6 也得挡住（只挡 IPv4 是不够的）====="
# IPv6 既不是点分四段、也不带 jdbc: 前缀，前面那些规则一条都不认它。
O=$(m 'ERROR conn to fd00:1234:5678::17 port 5432 failed')
hasnt "压缩写法的 IPv6 被挡住了"          'fd00:1234:5678::17'       "$O"
has   "看得出这里本来是个地址"            '<IPv6已隐去>'             "$O"

O=$(m 'ERROR host=2001:0db8:85a3:0000:0000:8a2e:0370:7334 unreachable')
hasnt "完整 8 段写法的 IPv6 也被挡住了"   '2001:0db8:85a3'           "$O"

O=$(m 'ERROR [fd00::1]:5432 connection dropped')
hasnt "带方括号的 IPv6 写法也挡住了"      'fd00::1'                  "$O"

echo
echo "===== 四、遮蔽：先遮后截，别让半截地址从 160 字的切口漏出去 ====="
# 这条的机器名正好横跨第 160 个字节：先截断的话，规则要认的「:端口」被切掉了，
# 剩下半截机器名就那么留在输出里。所以顺序必须是「先遮蔽，后截断」。
LONG='2026-08-20 10:11:12.345 ERROR 1 --- [http-nio-7001-exec-9] c.b.f.server.common.GlobalExceptionHandler : payment callback failed, could not reach cache-01.prod.example.invalid:6379 after 3 tries'
GOOD=$(printf '%s\n' "$LONG" | mask | cut -c1-160)
BAD=$(printf '%s\n'  "$LONG" | cut -c1-160 | mask)
hasnt "脚本现在的顺序（先遮后截）不残留机器名" 'cache-01.prod'         "$GOOD"
# 反过来跑一遍，确认这条测试不是在空转 —— 顺序错了就该漏，漏了这条才算测到东西
has   "顺序倒过来确实会漏（说明这条真在测东西）" 'cache-01.prod'       "$BAD"

echo
echo "===== 五、遮蔽第一层：拿 env 里配的真主机名兜底（句式不认识也挡得住）====="
ENV_FILE="$TMPDIR_T/fastpay-server.env"
cat > "$ENV_FILE" <<'EOF'
DB_URL=jdbc:postgresql://pgbox.example.invalid:5432/fastpay?sslmode=disable
DB_USERNAME=fastpay
DB_PASSWORD=DoNotLeakThisPassword
EOF
eval "$MASK_SRC"          # 重新跑一遍遮蔽段，这次 env 读得到
has   "从 env 里认出了真实主机名"         'pgbox.example.invalid'    "$DB_HOST_REAL"
hasnt "口令没被带进主 shell"              'DoNotLeakThisPassword'    "${DB_PASSWORD:-空}"

O=$(m 'ERROR 数据库挂了: cannot reach pgbox.example.invalid, giving up')
hasnt "谁都没见过的句式，靠第一层挡住"     'pgbox.example.invalid'    "$O"
O=$(m 'ERROR The connection attempt failed [host=pgbox.example.invalid]')
hasnt "换个写法照样挡得住"                 'pgbox.example.invalid'    "$O"
O=$(m 'ERROR 口令是 DoNotLeakThisPassword')
has   "第一层只认主机名，不会顺手动别的"   'DoNotLeakThisPassword'    "$O"

ENV_FILE="$TMPDIR_T/no-such-env"
eval "$MASK_SRC"
T=$((T+1))
if [ -z "$DB_HOST_REAL" ]; then printf '  ✅ env 读不到时第一层自动让路，不报错\n'
else F=$((F+1)); printf '  ❌ env 读不到却认出了主机名 [%s]\n' "$DB_HOST_REAL"; fi

echo
echo "===== 六、数据库：没装 psql 客户端不能谎报「连得上」====="
# 第二个参数是这台机器连的数据库类型，不传就当 PostgreSQL（生产用的就是它）。
try_db() { PASS_N=0; FAIL_N=0; WARN_N=0; DB_OUT="$1"; DB_KIND="${2:-postgresql}"; eval "$DB_SRC" 2>&1; }

# 一份「V1_1 到 V1_5 全跑过」的完整查询结果，当作下面所有用例的底板。
# 要测「漏跑某一项」就从这份底板上删掉对应的行，而不是另手写一份短的 ——
# 手写短的那种写法，等哪天再加一个检查项，老用例会悄悄变成「几乎什么都没跑」，
# 却还顶着「原来该过的还得过」的名字，看上去一切正常。（MTM-215）
DB_ALL_OK='username=64
password=255
token_version=n/a
merchant_password=255
notify_result=1000
notify_error=500
table=fp_pending_pay_amount
table=fp_unmatched_notify
order_notify_url=2000
order_return_url=2000
merchant_notify_url=2000
merchant_return_url=2000'
# without <正则> —— 从底板里抽掉匹配的行，模拟「这一项没跑」
without() { printf '%s\n' "$DB_ALL_OK" | grep -v "$1"; }
O=$(try_db 'bash: psql: command not found')
has   "判 FAIL"                           '❌ FAIL'                  "$O"
hasnt "绝不能说「数据库连得上」"           '数据库连得上'             "$O"
has   "告诉人去装客户端"                   'postgresql-client'        "$O"
has   "说清迁移那几项这次也没查"           '这次也没查'               "$O"

echo
echo "===== 六之二、数据库：一个字都没查回来时，绝不许把人指去动数据库 ====="
# MTM-204 修好的就是这个方向指反的坑，MTM-218 补上守门的这一段。
# 出事的场景：env 里漏配了 DB_PASSWORD，子 shell 被 set -u 当场打断，DB_OUT 是空的。
# 老写法的判据是「没看见我认识的报错就算连上了」，于是打出「数据库连得上」，
# 紧接着又报「V1_1 没生效 —— 补跑 V1_1 迁移脚本」。库本身好好的，人却被指去改生产库：
# 方向正好指反，而且指向一个会写库的动作。
# 判据改成「必须真把 username= 那一行查回来才算连上」之后，这个坑没了，
# 但一直没有测试守着 —— 谁把判据改回老写法都不会有人拦。下面这几条就是那个拦路的。
O=$(try_db '')
has   "判 FAIL，而且判的就是「查不了」"     'FAIL  数据库这一项查不了' "$O"
hasnt "一条都不许判通过"                   '✅ PASS'                  "$O"
hasnt "绝不能说「数据库连得上」"           '数据库连得上'             "$O"
hasnt "绝不能说「V1_1 没生效」"            'V1_1 没生效'              "$O"
hasnt "绝不能把人指去补跑迁移脚本"         '补跑 V1_1 迁移脚本'       "$O"
has   "改指去查 env 里漏配的账号口令"       'DB_USERNAME'              "$O"
has   "说清迁移那两项这次也没查"            '这次也没查'               "$O"

# 同一个坑的另一副面孔：机器是中文的，psql 没装时报的是「未找到命令」。
# 老写法只认英文 command not found，认不出来就当没事 —— 照样谎报「连得上」。
O=$(try_db 'bash: psql：未找到命令')
has   "中文报错也判 FAIL"                  'FAIL  这台机器上没装 psql 客户端' "$O"
hasnt "中文机器上也不许说「数据库连得上」" '数据库连得上'             "$O"
hasnt "中文机器上也不许说「V1_1 没生效」"  'V1_1 没生效'              "$O"
has   "认得出是没装客户端"                 'postgresql-client'        "$O"

echo
echo "===== 七、数据库：连不上的原因分类，且提示里不带地址账号 ====="
reason() { # reason <DB_OUT> <期望分类> <不许出现的串...>
  local out exp; out=$(try_db "$1"); exp="$2"; shift 2
  has "分类：$exp" "$exp" "$out"
  local leak; for leak in "$@"; do hasnt "  └ 提示里没有「$leak」" "$leak" "$out"; done
}
reason 'psql: error: connection to server at "192.0.2.10", port 5432 failed: FATAL:  password authentication failed for user "fastpay"' '账号或口令不对' '192.0.2.10' 'for user'
reason 'psql: error: connection to server at "192.0.2.10", port 5432 failed: FATAL:  database "fastpay_x" does not exist' '库名或账号不存在' '192.0.2.10' 'fastpay_x'
reason 'psql: error: connection to server at "192.0.2.10", port 5432 failed: timeout expired' '连接超时' '192.0.2.10'
reason 'psql: error: connection to server at "192.0.2.10", port 5432 failed: Connection refused' '端口连不上' '192.0.2.10'
reason 'psql: error: could not translate host name "db-host.example.invalid" to address: Name or service not known' '主机名解析不了' 'db-host.example.invalid'
reason 'psql: error: something nobody has seen before' '原因不明'

echo
echo "===== 八、回归：原来该过的还得过 ====="
O=$(try_db "$DB_ALL_OK")
has   "连得上"                            'PASS  数据库连得上'       "$O"
has   "V1_1 判定还在"                     'V1_1 已生效'              "$O"
has   "V1_2 判定还在"                     'V1_2 已生效'              "$O"
has   "V1_3 判定在"                       'V1_3 已生效'              "$O"
has   "V1_4 判定在"                       'V1_4 已生效'              "$O"
has   "V1_5 判定在"                       'V1_5 已生效'              "$O"
hasnt "没有误报 FAIL"                     '❌ FAIL'                  "$O"

O=$(try_db "$(without '^token_version=' | sed -e 's/^password=255/password=60/' -e 's/^merchant_password=255/merchant_password=60/')")
has   "缺 token_version 查得出来"          'V1_1 没生效'              "$O"
has   "password 太窄会提醒"                'V1_2 可能没跑'            "$O"

O=$(try_db 'PARSE_FAIL')
has   "DB_URL 格式不对仍判 FAIL"           'DB_URL 格式不对'          "$O"

echo
echo "===== 八之二、数据库：迁移漏跑必须报红，不能只是提醒（MTM-215）====="
# 这一段守的是这条脚本存在的理由：**漏跑迁移，它必须报红**。
# 之前 V1_3 / V1_4 压根没查，漏跑照样全绿 —— 而线上正好停在 V1_2，
# 下次上线 V1_3 / V1_4 两个都得补跑，恰恰全落在没查的那两项上。
#
# 下面每一条都是「除了这一项，别的都跑过了」，所以任何一条变绿，
# 都说明那一项的检查被人拿掉了或者写歪了。

# —— 线上现在的真实状态：停在 V1_2，后面三个都没跑
O=$(try_db 'username=64
password=255
token_version=n/a
merchant_password=255')
has   "停在 V1_2：V1_3 判 FAIL"            'FAIL  V1_3 没生效'        "$O"
has   "停在 V1_2：V1_4 判 FAIL"            'FAIL  V1_4 没生效'        "$O"
has   "停在 V1_2：V1_5 判 FAIL"            'FAIL  V1_5 没生效'        "$O"
hasnt "已经跑过的 V1_1 不许被连累"          'V1_1 没生效'              "$O"
has   "还是认得出库是连得上的"              'PASS  数据库连得上'       "$O"

# —— V1_3：两列缺任意一列都要红
O=$(try_db "$(without '^notify_result=')")
has   "V1_3 缺 notify_result：判 FAIL"     'FAIL  V1_3 没生效'        "$O"
has   "指名道姓说缺哪一列"                  'notify_result'            "$O"
has   "顺便说清漏跑的后果"                  '回调失败原因'             "$O"
hasnt "别的迁移项不受连累"                  'V1_4 没生效'              "$O"
O=$(try_db "$(without '^notify_error=')")
has   "V1_3 缺 notify_error：判 FAIL"      'FAIL  V1_3 没生效'        "$O"

# —— V1_4：两张表缺任意一张都要红
O=$(try_db "$(without '^table=fp_pending_pay_amount$')")
has   "V1_4 缺 fp_pending_pay_amount：判 FAIL" 'FAIL  V1_4 没生效'    "$O"
has   "指名道姓说缺哪张表"                  'fp_pending_pay_amount'    "$O"
hasnt "别的迁移项不受连累"                  'V1_3 没生效'              "$O"
O=$(try_db "$(without '^table=fp_unmatched_notify$')")
has   "V1_4 缺 fp_unmatched_notify：判 FAIL"   'FAIL  V1_4 没生效'    "$O"
O=$(try_db "$(without '^table=')")
has   "V1_4 两张表都没有：判 FAIL"          'FAIL  V1_4 没生效'        "$O"

# —— V1_5：字段还是窄的要红。这一项跟 V1_2 不一样，不许只是「注意」：
#    回调地址被截断 = 商户回调直接打不通，是当场就坏的事。
O=$(try_db "$(printf '%s\n' "$DB_ALL_OK" | sed 's/^order_notify_url=2000/order_notify_url=255/')")
has   "V1_5 字段还是窄的：判 FAIL"          'FAIL  V1_5 没生效'        "$O"
has   "说清是哪个字段、现在多宽"            'order_notify_url=255'     "$O"
hasnt "不许降级成「注意」糊弄过去"          'V1_5 可能没跑'            "$O"
O=$(try_db "$(without '^merchant_return_url=')")
has   "V1_5 字段整个查不到：判 FAIL"        'FAIL  V1_5 没生效'        "$O"
has   "查不到就照实说查不到"                'merchant_return_url=查不到' "$O"

echo
echo "===== 八之三、数据库：MySQL 机器不能被指去装 PostgreSQL 的客户端 ====="
# 生产是 PostgreSQL，但本地和老装机上有 MySQL/MariaDB（每个迁移脚本都有 _mysql 那一份）。
# 以前这一段是写死 psql 的：MySQL 机器上查不了库，脚本却让人去装 postgresql-client，
# 装完照样查不了，人还以为自己装错了。
O=$(try_db 'bash: mysql: command not found' mysql)
has   "判 FAIL"                            '❌ FAIL'                  "$O"
has   "说的是 mysql 客户端"                 '没装 mysql 客户端'        "$O"
has   "给的是 MySQL 的装法"                 'default-mysql-client'     "$O"
hasnt "不该让人去装 postgresql-client"      'postgresql-client'        "$O"
hasnt "也不许谎报「数据库连得上」"          '数据库连得上'             "$O"

# MariaDB 走同一条路
O=$(try_db 'bash: mysql: command not found' mariadb)
has   "MariaDB 也认得出来"                  '没装 mysql 客户端'        "$O"

# MySQL 的报错措辞跟 PostgreSQL 完全不同，只认 PostgreSQL 那套的话每次都落到「原因不明」
mysql_reason() { local out; out=$(try_db "$1" mysql); has "分类：$2" "$2" "$out"; hasnt "  └ 提示里没有「$3」" "$3" "$out"; }
mysql_reason "ERROR 1045 (28000): Access denied for user 'fastpay'@'192.0.2.10' (using password: YES)" '账号或口令不对' '192.0.2.10'
mysql_reason "ERROR 1049 (42000): Unknown database 'fastpay_x'"                                        '库名或账号不存在' 'fastpay_x'
mysql_reason "ERROR 2005 (HY000): Unknown MySQL server host 'db-host.example.invalid' (-2)"            '主机名解析不了' 'db-host.example.invalid'
mysql_reason "ERROR 2003 (HY000): Can't connect to MySQL server on 'db-host.example.invalid:3306' (111)" '端口连不上' 'db-host.example.invalid'

# 查得回来的时候，MySQL 和 PostgreSQL 走的是同一套判断 —— 方言差别在查询那一层就抹平了
O=$(try_db "$DB_ALL_OK" mysql)
has   "MySQL 上也认得出五项全跑过"          'V1_5 已生效'              "$O"
hasnt "MySQL 上不许误报 FAIL"               '❌ FAIL'                  "$O"

echo
echo "===== 九、日志段：拿真日志文件跑真代码，从头到尾走一遍 ====="
LOG_FILE="$TMPDIR_T/fastpay-server.log"
cat > "$LOG_FILE" <<'EOF'
2026-08-20 10:00:01.001  INFO 1 --- [main] c.b.f.ServerApplication : Started ServerApplication in 8.2 seconds
2026-08-20 10:11:12.345 ERROR 1 --- [main] com.zaxxer.hikari.pool.HikariPool : HikariPool-1 - Exception during pool initialization: Failed to obtain JDBC Connection; url=jdbc:postgresql://192.0.2.10:5432/fastpay user=fastpay
2026-08-20 10:11:13.001 ERROR 1 --- [main] o.p.util.PSQLException : Connection to db-host.example.invalid:5432 refused. Check that the hostname and port are correct.
2026-08-20 10:12:00.777 ERROR 1 --- [http-nio] c.b.f.s.NotifyService : 订单 20260820001 回调处理失败: 商户签名不匹配
EOF
PASS_N=0; FAIL_N=0; WARN_N=0
O=$(eval "$LOG_SRC" 2>&1)
has   "数出了 3 条 ERROR"                 '有 3 条 ERROR'            "$O"
hasnt "打出来的片段里没有 IP"              '192.0.2.10'               "$O"
hasnt "打出来的片段里没有机器名"           'db-host.example.invalid'  "$O"
has   "正常那条业务报错照样看得懂"         '20260820001'              "$O"

PASS_N=0; FAIL_N=0; WARN_N=0
: > "$LOG_FILE"
O=$(eval "$LOG_SRC" 2>&1)
has   "日志干净时报没有 ERROR"             '没有 ERROR'               "$O"

echo
echo "===== 十、操作锁段：有别人在同时动这台机器，必须判 FAIL ====="
# 这一段最要命的错法是「该判 FAIL 的判成了 PASS」—— 那等于把并发上线放行了，
# 而放行是安安静静的，没有任何报错。所以每一条都同时断言「话说对了」和「计数对了」。
# 背景见 MTM-210：2026-08-20 两路人同时动生产，服务被不相干的人重启了一次。
LOCK_OPS="$TMPDIR_T/ops"
mkdir -p "$LOCK_OPS/prod-deploy.lock"
OPS_DIR="$LOCK_OPS"
LOCK_INFO="$LOCK_OPS/prod-deploy.lock/holder"

# 造一把「谁占着」的锁；不传参数就是没人占
set_lock() {
  if [ -z "${1:-}" ]; then rm -f "$LOCK_INFO"
  else printf 'TOKEN=%s\nWHO=%s\nSTART_TS=1\n' "$1" "$2" > "$LOCK_INFO"; fi
}
# 假装锁脚本装好了 / 没装
install_lock_sh()   { printf '#!/bin/sh\nexit 0\n' > "$LOCK_OPS/prod-lock.sh"; chmod 755 "$LOCK_OPS/prod-lock.sh"; }
uninstall_lock_sh() { rm -f "$LOCK_OPS/prod-lock.sh"; }

run_lock() { # run_lock <传给脚本的令牌>
  LOCK_TOKEN="${1:-}"
  PASS_N=0; FAIL_N=0; WARN_N=0
  eval "$LOCK_SRC" 2>&1
  # 计数必须在这里打出来：调用方是 O=$(run_lock ...)，那是个子 shell，
  # PASS_N/FAIL_N 加完就随子 shell 一起没了，外面读到的永远是 0。
  # 光看输出里有没有「❌ FAIL」不够 —— 那验不出「一次判了两条」这种错。
  printf 'COUNTS:PASS=%d,FAIL=%d,WARN=%d\n' "$PASS_N" "$FAIL_N" "$WARN_N"
}

install_lock_sh

set_lock MTM-157 "另一路"
O=$(run_lock MTM-210)
has   "锁在别人手上：说清楚了是谁"          'MTM-157'                  "$O"
has   "锁在别人手上：判 FAIL"                '❌ FAIL'                  "$O"
has   "锁在别人手上：只判了一条 FAIL"        'COUNTS:PASS=0,FAIL=1'     "$O"

set_lock MTM-210 "梁运｜运维"
O=$(run_lock MTM-210)
has   "锁在自己手上：判 PASS"                '✅ PASS'                  "$O"
has   "锁在自己手上：提醒验完要放开"          'release MTM-210'          "$O"
has   "锁在自己手上：只判了一条 PASS"        'COUNTS:PASS=1,FAIL=0'     "$O"

set_lock ''
O=$(run_lock MTM-210)
has   "自称持锁但根本没人占锁：判 FAIL"      '❌ FAIL'                  "$O"
has   "自称持锁但没人占锁：只判了一条 FAIL"  'COUNTS:PASS=0,FAIL=1'     "$O"

set_lock MTM-157 "另一路"
O=$(run_lock '')
has   "没传令牌但有人在动：至少提醒一句"      '⚠️'                       "$O"
hasnt "没传令牌：不判 FAIL（老写法要照样能跑）" '❌ FAIL'                "$O"

set_lock ''
O=$(run_lock '')
has   "没传令牌且没人占锁：判 PASS"          '✅ PASS'                  "$O"

uninstall_lock_sh
set_lock ''
O=$(run_lock MTM-210)
has   "锁脚本没装：提醒去装"                 '还没装操作锁'              "$O"
hasnt "锁脚本没装：不冒充成 PASS"            '✅ PASS'                  "$O"
install_lock_sh

echo
echo "===== 十一、脚本本身 ====="
T=$((T+1))
if bash -n "$SRC" 2>/dev/null; then printf '  ✅ bash -n 语法检查通过\n'
else F=$((F+1)); printf '  ❌ bash -n 语法检查没过\n'; fi

echo
echo "================================================"
if [ "$F" -eq 0 ]; then
  printf '全部通过：%d 项\n' "$T"; exit 0
else
  printf '有 %d 项没过（共 %d 项）—— 别把这个脚本装到服务器上，先修\n' "$F" "$T"; exit 1
fi
