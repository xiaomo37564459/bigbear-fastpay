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
# 还有一件事只有它拦得住（见第八之四节）：**新加了一版迁移脚本，却忘了给
# verify-release.sh 的检查表补一行。** 这一节会去 fastpay-server/.../db/migration/ 目录里
# 现数有几版，跟检查表两边一对，对不上就点名报失败。所以这个测试要在仓库里跑
# （目录结构在，它才数得到），别只把这一个文件拷到别处跑。
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
MIG_SRC=$(cut_block '^# =* 迁移检查（migration）开始' '^# =* 迁移检查（migration）结束')
DB_SRC=$(cut_block '^  if \[ "\$DB_OUT" = "PARSE_FAIL" \]; then$' '^  fi$')
LOG_SRC=$(cut_block '^if \[ -r "\$LOG_FILE" \]; then$' '^fi$')
LOCK_SRC=$(cut_block '^# =* 操作锁检查（lock）开始' '^# =* 操作锁检查（lock）结束')
CNT_SRC=$(grep -E '^(ok|no|warn)\(\)' "$SRC")

for pair in "遮蔽段:$MASK_SRC" "迁移检查段:$MIG_SRC" "数据库段:$DB_SRC" "日志段:$LOG_SRC" "操作锁段:$LOCK_SRC" "计数函数:$CNT_SRC"; do
  [ -n "${pair#*:}" ] || { echo "抠不到「${pair%%:*}」—— verify-release.sh 的结构变了，先修这个测试"; exit 2; }
done

C_OK=''; C_NO=''; C_WARN=''; C_B=''; C_OFF=''
eval "$CNT_SRC"
# 迁移检查那张表和判定逻辑：数据库段会调用它们，所以得先加载进来
eval "$MIG_SRC"

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
hasnt "绝不能把人指去补跑迁移脚本"         '补跑 V1_'                 "$O"
has   "改指去查 env 里漏配的账号口令"       'DB_USERNAME'              "$O"
has   "说清迁移那几项这次也没查"            '这次也没查'               "$O"

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
# 假的查询结果照着脚本里那张迁移检查表现造，不手写死。
# 这样以后往表里加一版新迁移，下面这些测试会自动跟着覆盖到，不用回来补 fixture。
db_all_good() { # 造一份「每一版都跑过了」的结果
  printf 'PROBE=3\n'
  mig_rows | while IFS='|' read -r ver kind tbl col minw sev msg; do
    case "$kind" in
      table) printf 'T:%s=1\n'      "$tbl" ;;
      width) printf 'C:%s.%s=%s\n'  "$tbl" "$col" "$minw" ;;
      *)     printf 'C:%s.%s=n/a\n' "$tbl" "$col" ;;
    esac
  done
}
# 把某一行整个拿掉 = 那张表 / 那一列压根不存在 = 那一版漏跑了
db_without() { db_all_good | awk -v k="$1=" 'index($0,k) != 1'; }
# 把某一列的宽度改窄 = 「加宽」那一版漏跑了
db_narrow()  { db_all_good | awk -v k="$1=" -v v="$2" 'index($0,k)==1 { print k v; next } { print }'; }

O=$(try_db "$(db_all_good)")
has   "连得上"                            'PASS  数据库连得上'       "$O"
has   "V1_1 判定还在"                     'V1_1 已生效'              "$O"
has   "V1_2 判定还在"                     'V1_2 已生效'              "$O"
hasnt "全跑过了不许报 FAIL"                '❌ FAIL'                  "$O"
hasnt "全跑过了也不许瞎提醒"                '⚠️'                       "$O"

O=$(try_db "$(db_without 'C:fp_admin.token_version')")
has   "缺 token_version 查得出来"          'V1_1 没生效'              "$O"
# MTM-227 起 V1_2 也报红：同一节检查里留一项只提醒，看的人会慢慢学会「这一节黄一下没关系」
O=$(try_db "$(db_narrow 'C:fp_admin.password' 60)")
has   "password 太窄：报出了 V1_2"          'V1_2 没生效'              "$O"
has   "password 太窄：判 FAIL 不再是提醒"   '❌ FAIL'                  "$O"
hasnt "password 太窄：不许再只提醒一句"     'V1_2 可能没跑'            "$O"

O=$(try_db 'PARSE_FAIL')
has   "DB_URL 格式不对仍判 FAIL"           'DB_URL 格式不对'          "$O"

echo
echo "===== 八之二、迁移：后几版漏跑了必须报出来（MTM-214 就是栽在这儿）====="
# 出事的场景：脚本里手写的 if 只认 V1_1 / V1_2，后来加的 V1_3 / V1_4 / V1_5 没人回来补。
# 于是这几版漏跑了脚本照样一路全绿，发版的人以为万事大吉 —— 尤其 V1_4 漏跑，等于那个
# 「两人同价撞单认错人」的赔钱 bug 修复根本没生效，而且没有任何迹象会让人察觉。
# 每一条都同时钉死两件事：报出了是哪一版，而且真的判了 FAIL（不是只提醒一句）。
mig_missing() { # mig_missing <说明> <要拿掉的键> <期望报出的版本>
  local o; o=$(try_db "$(db_without "$2")")
  has   "$1：报出了 $3"          "$3 没生效"  "$o"
  has   "$1：判 FAIL"            '❌ FAIL'    "$o"
  hasnt "$1：不许说这一版已生效"  "$3 已生效"  "$o"
}
mig_missing '漏跑 V1_3（回调结果列）'     'C:fp_pay_order.notify_result' 'V1_3'
mig_missing '漏跑 V1_3（回调报错列）'     'C:fp_pay_order.notify_error'  'V1_3'
mig_missing '漏跑 V1_4（待支付金额占位表）' 'T:fp_pending_pay_amount'      'V1_4'
mig_missing '漏跑 V1_4（未匹配收款通知表）' 'T:fp_unmatched_notify'        'V1_4'

# V1_1 除了加 token_version 列，还把管理员用户名从 50 放宽到 100（能存邮箱格式的账号）。
# 这一项以前没查 —— 文档里写着「每一版改过的表和列都查到了」，那就得说到做到（MTM-227）
O=$(try_db "$(db_narrow 'C:fp_admin.username' 50)")
has   "漏跑 V1_1（用户名还是 50）：报出了 V1_1" 'V1_1 没生效'          "$O"
has   "漏跑 V1_1（用户名还是 50）：判 FAIL"     '❌ FAIL'              "$O"

O=$(try_db "$(db_narrow 'C:fp_merchant.password' 64)")
has   "漏跑 V1_2（商户密码还是 64）：报出了 V1_2" 'V1_2 没生效'        "$O"
has   "漏跑 V1_2：判 FAIL"                        '❌ FAIL'            "$O"

O=$(try_db "$(db_narrow 'C:fp_pay_order.notify_url' 255)")
has   "漏跑 V1_5（URL 列还是 255）：报出了 V1_5" 'V1_5 没生效'          "$O"
has   "漏跑 V1_5：判 FAIL"                       '❌ FAIL'              "$O"

mig_missing '漏跑 V1_6（登录限次表）'     'T:fp_login_attempt'          'V1_6'

# 只漏一版，别的版本不能跟着一起被冤枉 —— 冤枉了，人就会去重跑一堆本来不用跑的脚本
O=$(try_db "$(db_without 'T:fp_pending_pay_amount')")
has   "只漏 V1_4 时：V1_1 照样报已生效"          'V1_1 已生效'          "$O"
has   "只漏 V1_4 时：V1_2 照样报已生效"          'V1_2 已生效'          "$O"
has   "只漏 V1_4 时：V1_3 照样报已生效"          'V1_3 已生效'          "$O"
has   "只漏 V1_4 时：V1_5 照样报已生效"          'V1_5 已生效'          "$O"
has   "只漏 V1_4 时：V1_6 照样报已生效"          'V1_6 已生效'          "$O"

echo
echo "===== 八之三、连上了但库是空的：不许一版版报「没生效」，得指去核对库名 ====="
# 库名写错、连到别的库上去了，业务表一张都没有。这时候老老实实一版版报「没生效」，
# 会把人指去对着错的库跑迁移脚本 —— 又是「方向指反」那类坑（MTM-204 / MTM-218 同款）。
O=$(try_db 'PROBE=0')
has   "判 FAIL"                        '❌ FAIL'          "$O"
has   "连得上这句照说"                  '数据库连得上'      "$O"
has   "指去核对库名"                    '库名写错'          "$O"
hasnt "不许指去跑迁移脚本"              '补跑 V1_'          "$O"
hasnt "不许一版版报没生效"              '没生效'            "$O"
has   "说清这几项这次也没查"            '这次也没查'        "$O"

echo
echo "===== 八之四、检查表必须和迁移目录对得上（加了新迁移忘了补行，机器当场拦住）====="
# 这一条盯的是 MTM-214 那个坑的**根因**。
#
# 老写法认的是写死的 V1_1~V1_5：所以「有人把表改瘦了」拦得住，但「以后加了 V1_6，
# 忘了往表里补一行」拦不住 —— 而后者恰恰就是当初出事的方式。文档里立了规矩，可规矩
# 靠人记、机器不拦，等于没拦。
#
# 所以改成不写死任何版本号：直接去 db/migration/ 目录里现数有几版，跟表里的版本号两边
# 对一遍。目录里有、表里没有的，当场点名报失败；反过来表里有、目录里没有的，也报出来
# （说明表里留着已经删掉或改过名的版本）。（MTM-227）
MIG_DIR="$(cd "$(dirname "$0")/.." && pwd)/fastpay-server/src/main/resources/db/migration"

# 目录里到底有哪几版。同一版有 _pg / _mysql 两个文件，去重后只算一版
mig_dir_versions() {
  ls "$MIG_DIR" 2>/dev/null | sed -nE 's#^(V[0-9]+_[0-9]+)__.*\.sql$#\1#p' | sort -u
}

# 极少数迁移脚本确实一行结构都不改（只补一批数据、只建索引），这种版本没有「去数据库里
# 看什么在不在」可查。**只有这一种情况**才允许它不出现在检查表里：把版本号写进下面这行，
# 理由写进紧跟着的 MIG_NO_STRUCTURE_WHY。默认是空的 —— 往里加东西等于给自己开一个口子，
# 加之前先确认清楚，「暂时没想好查什么」不算理由。
MIG_NO_STRUCTURE_CHECK=''
# 上面那行真填了版本号，这里就必须写清楚为什么可以不查。两行都会被打印出来给人看（见下）。
MIG_NO_STRUCTURE_WHY=''

in_list() { case " $2 " in *" $1 "*) return 0 ;; *) return 1 ;; esac; }

# 逃生口一旦真被用上，必须让人一眼看见：那一版从此不进检查表，漏跑了这里也不会拦。
# 悄悄少查一版比查出问题危险得多 —— 报错还有人看见，「少查了」是安安静静的（MTM-230）。
# 默认空着时一个字都不打印，别给正常输出添噪音。
# 注意：这里只负责把话说出来，不改任何一条判定 —— 判定还是上面那两条比对说了算。
mig_exempt_notice() { # mig_exempt_notice <被豁免的版本列表> <理由>
  local vers="$1" why="$2"
  [ -n "$(printf '%s' "$vers" | tr -d '[:space:]')" ] || return 0
  [ -n "$(printf '%s' "$why"  | tr -d '[:space:]')" ] || why='没写 —— 请在本文件的 MIG_NO_STRUCTURE_WHY 里补上'
  printf '  ⚠️  以下版本被豁免不查：%s（理由：%s）\n' "$vers" "$why"
  printf '       豁免 = 这一版没进 verify-release.sh 的检查表，本测试也不会拦。\n'
  printf '       确认它确实一行结构都没改（只补数据、只建索引），否则请把豁免撤掉、补上检查行。\n'
}
mig_exempt_notice "$MIG_NO_STRUCTURE_CHECK" "$MIG_NO_STRUCTURE_WHY"

DIR_VERS=$(mig_dir_versions | tr '\n' ' ')
TAB_VERS=$(mig_rows | cut -d'|' -f1 | sort -u | tr '\n' ' ')

T=$((T+1))
if [ -n "$DIR_VERS" ]; then
  printf '  ✅ 迁移目录里数到这几版：%s\n' "$DIR_VERS"
else
  F=$((F+1))
  printf '  ❌ 在 %s 里一个迁移脚本都没数到 —— 目录挪位置或改名了？先把本文件里的 MIG_DIR 改对，别让这条检查空转\n' "$MIG_DIR"
fi

# ① 目录里有、表里没有 —— 「加了新迁移忘了补检查」就是这一条拦住的
T=$((T+1)); MISS_TAB=''
for v in $DIR_VERS; do
  in_list "$v" "$TAB_VERS" && continue
  in_list "$v" "$MIG_NO_STRUCTURE_CHECK" && continue
  MISS_TAB="$MISS_TAB $v"
done
if [ -z "$MISS_TAB" ]; then
  printf '  ✅ 目录里每一版迁移脚本，检查表里都有对应的行\n'
else
  F=$((F+1))
  printf '  ❌ 这几版在 db/migration/ 里有，但 verify-release.sh 的 MIGRATION_CHECKS 表里没有：%s\n' "$MISS_TAB"
  printf '       → 去 deploy/verify-release.sh 里给它们各补一行，格式说明就在那张表上面的注释里\n'
  printf '       → 不补的后果：这一版漏跑了，发版验证照样一路全绿 —— MTM-214 就是这么出的事\n'
  printf '       → 确实一行结构都没改（只补数据、只建索引），把版本号写进本文件的 MIG_NO_STRUCTURE_CHECK 并注明理由\n'
fi

# ② 表里有、目录里没有 —— 表里留着已经删掉或改过名的版本
T=$((T+1)); MISS_DIR=''
for v in $TAB_VERS; do
  in_list "$v" "$DIR_VERS" || MISS_DIR="$MISS_DIR $v"
done
if [ -z "$MISS_DIR" ]; then
  printf '  ✅ 检查表里没有目录中已经不存在的版本\n'
else
  F=$((F+1))
  printf '  ❌ 这几版在 MIGRATION_CHECKS 表里有，但 db/migration/ 目录里找不到对应脚本：%s\n' "$MISS_DIR"
  printf '       → 脚本被删了或改过名，表里那几行要跟着改；也可能只是版本号写错了\n'
fi

# ③ 反向验一次，确认上面 ① 不是在空转：塞一个目录里根本没有的假版本进去比对，必须被点名。
#    没有这一条，① 哪天写错成永远为空，红都红不起来，而「拦不住」是安安静静的。
T=$((T+1)); FAKE_MISS=''
for v in $DIR_VERS V9_9; do
  in_list "$v" "$TAB_VERS" || FAKE_MISS="$FAKE_MISS $v"
done
if in_list V9_9 "$FAKE_MISS"; then
  printf '  ✅ 拿假版本 V9_9 试了一次，确实会被点名（说明 ① 真在比对，不是空转）\n'
else
  F=$((F+1)); printf '  ❌ 假版本 V9_9 没被点出来 —— ① 那条比对是空转的，先修它\n'
fi

# 版本清单要打得出来给人看（连不上库时那句「这几项这次也没查」靠它列出是哪几项）
T=$((T+1)); VLIST=$(printf '%s' "$(mig_versions)" | tr '/' ' '); VMISS=''
for v in $TAB_VERS; do in_list "$v" "$VLIST" || VMISS="$VMISS $v"; done
if [ -z "$VMISS" ]; then printf '  ✅ 版本清单打得出来给人看：%s\n' "$(mig_versions)"
else F=$((F+1)); printf '  ❌ 版本清单里漏了：%s\n' "$VMISS"; fi

# 表里每一行都得真的进到查询语句里，否则等于写了没查
T=$((T+1))
SQL=$(mig_sql); MISS=''
while IFS='|' read -r v kind tbl col minw sev msg; do
  case "$SQL" in *"$tbl"*) ;; *) MISS="$MISS $tbl" ;; esac
  [ "$kind" = table ] || case "$SQL" in *"$col"*) ;; *) MISS="$MISS $tbl.$col" ;; esac
done < <(mig_rows)
if [ -z "$MISS" ]; then printf '  ✅ 表里每一行都进了查询语句\n'
else F=$((F+1)); printf '  ❌ 这些没进查询语句：%s\n' "$MISS"; fi

# 逃生口（MIG_NO_STRUCTURE_CHECK）被用上时必须有动静，空着时必须一个字都不打印（MTM-230）。
# 没有这几条，「打印」哪天写坏了也没人知道 —— 而它坏了的表现恰恰是「什么都没打印」，
# 跟「本来就没人用逃生口」长得一模一样，光看输出根本分不出来。
O=$(mig_exempt_notice 'V9_8' '只补了一批历史数据，一行结构都没改')
has   "逃生口被用上：打出了是哪几版"        '被豁免不查：V9_8'                 "$O"
has   "逃生口被用上：打出了理由"            '理由：只补了一批历史数据'          "$O"
O=$(mig_exempt_notice 'V9_8' '   ')
has   "填了版本没写理由：明说理由没写"       'MIG_NO_STRUCTURE_WHY 里补上'      "$O"
O=$(mig_exempt_notice '' '')
hasnt "没人用逃生口：不给正常输出添噪音"     '被豁免不查'                       "$O"

echo
echo "===== 八之五、warn 这一档：眼下一行都没在用，但机制得是活的 ====="
# MTM-227 把五项口径统一成了「查不到一律拦住」，所以表里现在一行 warn 都没有。
# 但 warn 这条代码分支还留着，将来真遇到「漏跑了当天不会出任何事」的版本还得靠它。
# 没有一行用它 = 没有一条测试走过它 = 哪天它坏了没人会知道。所以这里拿一张假表走一遍。
MIG_SAVE="$MIGRATION_CHECKS"
MIGRATION_CHECKS='
  V0_0 | width | fp_admin | password | 255 | warn | 假的一行，只为确认 warn 这条路还通
'
O=$(try_db 'PROBE=3
C:fp_admin.password=60')
has   "warn 那一档：报的是「可能没跑」"     'V0_0 可能没跑'            "$O"
hasnt "warn 那一档：不判 FAIL"              '❌ FAIL'                  "$O"
MIGRATION_CHECKS="$MIG_SAVE"

# 假表必须换回来，否则后面的测试测的就是假表了
O=$(try_db "$(db_all_good)")
hasnt "假表已经换回真表"                    'V0_0'                     "$O"
has   "换回来之后真表照常判定"              'V1_5 已生效'              "$O"

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

run_lock() { # run_lock <传给脚本的令牌> [第 3 个参数：这台机器是不是生产]
  LOCK_TOKEN="${1:-}"
  ENV_DECL="${2:-}"
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

echo
echo "===== 十之二、机器上没装锁脚本：说了是生产就判 FAIL，没说才只提醒（MTM-220）====="
# 原来不管什么环境都只提醒一句。老机器、老流程不被硬卡住是好事，但代价是：
# 万一哪天有人漏装了锁脚本，这层保护就悄悄失效了，而失效是没有任何动静的。
# 折中办法（MTM-220 选的 C）：只有明说了「这是生产」才判死，其余照旧只提醒。
uninstall_lock_sh
set_lock ''

O=$(run_lock MTM-210)
has   "没说环境：提醒去装"                    '还没装操作锁'             "$O"
hasnt "没说环境：不冒充成 PASS"               '✅ PASS'                  "$O"
hasnt "没说环境：不判 FAIL（老写法照样能跑）"  '❌ FAIL'                  "$O"
has   "没说环境：只记一条「注意」"             'COUNTS:PASS=0,FAIL=0,WARN=1' "$O"

O=$(run_lock MTM-210 prod)
has   "说了是生产：判 FAIL"                   '❌ FAIL'                  "$O"
has   "说了是生产：说清楚是因为没装锁"          '还没装操作锁'             "$O"
has   "说了是生产：只判了一条 FAIL"            'COUNTS:PASS=0,FAIL=1'     "$O"
hasnt "说了是生产：不许还冒充成 PASS"          '✅ PASS'                  "$O"

O=$(run_lock MTM-210 PROD)
has   "大写 PROD 也算生产"                    '❌ FAIL'                  "$O"
O=$(run_lock MTM-210 生产)
has   "中文「生产」也算生产"                   '❌ FAIL'                  "$O"

O=$(run_lock MTM-210 staging)
hasnt "明说了不是生产：不判 FAIL"              '❌ FAIL'                  "$O"
has   "明说了不是生产：还是提醒一句"            '还没装操作锁'             "$O"

# 这几条盯的是「把 prod 拼错了」。拼错要是被当成「没说」放过去，人以为自己开了严格模式，
# 实际上没开 —— 保护失效了却一点动静都没有，正是这一段最怕的错法。
O=$(run_lock MTM-210 prd)
has   "环境值拼错：判 FAIL"                   '❌ FAIL'                  "$O"
has   "环境值拼错：把写错的值原样指出来"        'prd'                      "$O"
has   "环境值拼错：只判了一条 FAIL"            'COUNTS:PASS=0,FAIL=1'     "$O"
hasnt "环境值拼错：绝不许当成没说放过去"        '⚠️'                       "$O"

install_lock_sh

# 锁脚本装好的情况下，第 3 个参数不该改变原来任何一条判定 —— 这几条是防回归的
set_lock MTM-157 "另一路"
O=$(run_lock MTM-210 prod)
has   "锁装好了+生产+锁在别人手上：照样判 FAIL" '❌ FAIL'                  "$O"
has   "锁装好了+生产+锁在别人手上：只判一条"    'COUNTS:PASS=0,FAIL=1'     "$O"
set_lock MTM-210 "梁运｜运维"
O=$(run_lock MTM-210 prod)
has   "锁装好了+生产+锁在自己手上：照样判 PASS" '✅ PASS'                  "$O"
has   "锁装好了+生产+锁在自己手上：只判一条"    'COUNTS:PASS=1,FAIL=0'     "$O"

echo
echo "===== 十之三、说了是生产、你手上没锁、别人却正占着锁：判 FAIL（MTM-220 丙）====="
# 这个脚本是只读体检，纯粹上来看看服务活着没的人不该被拦，所以判据不是「写了 prod
# 又没传令牌」。真危险的只有这一种组合：别人正动着这台生产机器，你却两眼一抹黑也摸上来了。
# 没人占锁时照旧放行 —— 一刀切卡死无害操作，大家学到的只会是「别写 prod，写了麻烦」，
# 那第 3 个参数就白加了，保护反而更没了。
install_lock_sh

set_lock MTM-157 "另一路"
O=$(run_lock '' prod)
has   "生产+手上没锁+别人占着锁：判 FAIL"        '❌ FAIL'                  "$O"
has   "生产+手上没锁+别人占着锁：说清是谁占着"    'MTM-157'                  "$O"
has   "生产+手上没锁+别人占着锁：只判了一条 FAIL" 'COUNTS:PASS=0,FAIL=1'     "$O"
hasnt "生产+手上没锁+别人占着锁：不许还只是提醒"  '⚠️'                       "$O"

set_lock ''
O=$(run_lock '' prod)
has   "生产+手上没锁+没人占锁：判 PASS（纯体检不该被拦）" '✅ PASS'         "$O"
hasnt "生产+手上没锁+没人占锁：不判 FAIL"        '❌ FAIL'                  "$O"
has   "生产+手上没锁+没人占锁：只判了一条 PASS"   'COUNTS:PASS=1,FAIL=0'     "$O"

# 非生产和「没说环境」这两条路一个字都不该变，老流程照样跑得过 —— 这几条是防回归的
set_lock MTM-157 "另一路"
O=$(run_lock '' staging)
hasnt "非生产+手上没锁+别人占着锁：不判 FAIL"    '❌ FAIL'                  "$O"
has   "非生产+手上没锁+别人占着锁：还是提醒一句"  '⚠️'                       "$O"
O=$(run_lock '')
hasnt "没说环境+手上没锁+别人占着锁：不判 FAIL"  '❌ FAIL'                  "$O"
has   "没说环境+手上没锁+别人占着锁：还是提醒一句" '⚠️'                      "$O"

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
