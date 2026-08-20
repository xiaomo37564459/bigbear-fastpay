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
CNT_SRC=$(grep -E '^(ok|no|warn)\(\)' "$SRC")

for pair in "遮蔽段:$MASK_SRC" "数据库段:$DB_SRC" "日志段:$LOG_SRC" "计数函数:$CNT_SRC"; do
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
try_db() { PASS_N=0; FAIL_N=0; WARN_N=0; DB_OUT="$1"; eval "$DB_SRC" 2>&1; }
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
O=$(try_db 'username=64
password=255
token_version=n/a
merchant_password=255')
has   "连得上"                            'PASS  数据库连得上'       "$O"
has   "V1_1 判定还在"                     'V1_1 已生效'              "$O"
has   "V1_2 判定还在"                     'V1_2 已生效'              "$O"
hasnt "没有误报 FAIL"                     '❌ FAIL'                  "$O"

O=$(try_db 'username=64
password=60
merchant_password=60')
has   "缺 token_version 查得出来"          'V1_1 没生效'              "$O"
has   "password 太窄会提醒"                'V1_2 可能没跑'            "$O"

O=$(try_db 'PARSE_FAIL')
has   "DB_URL 格式不对仍判 FAIL"           'DB_URL 格式不对'          "$O"

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
echo "===== 十、脚本本身 ====="
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
