# 部署说明（pay.copliot.cloud）

这份文档写给要上线、要更新版本、要出事时把系统救回来的人。照着做就行。

> 🔐 **动这份文档之前先看一眼 [敏感信息怎么处理](SECRETS.md)。**
> 那一页写清了：**什么能写进这个公开仓库、什么绝对不能、真实的密码密钥该放哪、上线验证要进后台用哪个账号。**
> 一句话版本：域名、端口、占位符随便写；**账号密码、各种密钥、内网地址、任何人的真实邮箱，一个字都不许写进来。**

> ✅ **发完版别就这么走了 —— 怎么确认「真的发好了」，见 [发完版怎么确认真的发好了](RELEASE_VERIFY.md)。**
> 那一页是一条命令 + 一份人工点检清单，能查出「换了 jar 忘了重启」「传错版本」「迁移漏跑」「前端权限没收紧」这类光看页面看不出来的问题。
>
> 配套的一件小事，**部署最后一步别忘了**：把这次发的版本号写进服务器的版本标记文件，验证脚本靠它比对版本。
>
> ```bash
> ssh root@<服务器> "echo v1.5.0 > /opt/fastpay/DEPLOYED_VERSION"
> ```

当前线上环境：

| 项 | 值 |
| --- | --- |
| 网址 | https://pay.copliot.cloud |
| 管理后台 | https://pay.copliot.cloud/fastpay-admin/ |
| 商户端 | https://pay.copliot.cloud/fastpay-merchant/ |
| 服务器 | `150.158.99.251`（OpenCloudOS 9.4） |
| 后端进程 | systemd 服务 `fastpay-server`，监听 `127.0.0.1:7001`（不对外） |
| 程序目录 | `/opt/fastpay/` |
| 前端文件 | `/opt/fastpay/www/fastpay-admin/`、`/opt/fastpay/www/fastpay-merchant/`（目录 755、文件 644，**不能全局可写**） |
| 日志 | `/opt/fastpay/logs/fastpay-server.log` |
| 敏感配置 | `/etc/fastpay/fastpay-server.env`（权限 640，只有 root 和 fastpay 账号能读） |
| nginx 配置 | `/etc/nginx/conf.d/pay.copliot.conf`（**只有这一个文件属于本项目**） |
| 数据库 | PostgreSQL 15，库名 `fastpay` |

## ⚠️ 这台服务器上还跑着别的东西

同一台机器上还有 **Multica 平台本身**（`multica.copliot.cloud`）、`copliot.cloud`、`token.copliot.cloud`，以及一套 sub2api 的 Docker 服务。nginx 是共用的。

所以：

1. 本项目**只能动** `/etc/nginx/conf.d/pay.copliot.conf`。`copliot.conf`、`multica.conf` 一个字都不要改。
2. 改完 nginx **必须先 `nginx -t`**，通过了才 `systemctl reload nginx`。
3. **用 `reload`，不要用 `restart`** —— restart 会让所有站点瞬断。
4. 不要动 5432 端口上的 PostgreSQL 服务本身（好几个服务共用）。
5. 不要碰任何 Docker 容器。

## 一、构建（在本地做，不要在服务器上编译）

服务器上**没有装 Maven 和 Node**，故意的 —— 编译在本地做，只把产物传上去。

本地需要：JDK 17、Maven 3.6+、Node 20+。

```bash
# 后端 -> fastpay-server/target/fastpay-server-1.0.0.jar
mvn -f fastpay-server/pom.xml clean package -DskipTests

# 管理后台 -> fastpay-admin/dist/
cd fastpay-admin && npm ci && npm run build && cd ..

# 商户端 -> fastpay-merchant/dist/
cd fastpay-merchant && npm ci && npm run build && cd ..
```

`fastpay-demo` 是给商户看的对接示例，不部署。

### 构建完先在本地试着起一下

```bash
export SPRING_PROFILES_ACTIVE=prod
export DB_URL='jdbc:postgresql://<数据库地址>:5432/fastpay'
export DB_USERNAME='<用户名>'
export DB_PASSWORD='<密码>'
java -jar fastpay-server/target/fastpay-server-1.0.0.jar
```

看到 `Started FastPayApplication` 就算起来了。起不来别往服务器上传。

## 二、配置怎么给（重要）

**仓库里不保存任何真实的数据库地址和密码。** `application-prod.yml` 里全部写成环境变量占位符，`DB_URL` / `DB_USERNAME` / `DB_PASSWORD` 故意不设默认值 —— 没配就直接启动失败，避免连错库或用默认密码上线。

真实值放在服务器上的 `/etc/fastpay/fastpay-server.env`：

```ini
SPRING_PROFILES_ACTIVE=prod
SERVER_ADDRESS=127.0.0.1
SERVER_PORT=7001
DB_DRIVER=org.postgresql.Driver
DB_URL=jdbc:postgresql://<数据库地址>:<端口>/fastpay
DB_USERNAME=<用户名>
DB_PASSWORD=<密码>
FASTPAY_JWT_SECRET=<一串足够长的随机字符串，用 openssl rand -hex 48 生成>
FASTPAY_PAGE_DOMAIN=https://pay.copliot.cloud/fastpay-merchant
FASTPAY_NOTIFY_CALLBACK_URL=https://pay.copliot.cloud/fastpay-server/api/notify/callback
SPRINGDOC_API_DOCS_ENABLED=false
SPRINGDOC_SWAGGER_UI_ENABLED=false
```

权限必须是 `640 root:fastpay`：

```bash
chown root:fastpay /etc/fastpay/fastpay-server.env
chmod 640 /etc/fastpay/fastpay-server.env
```

上面每个 `<...>` 都是占位符，要换成真实值。真实值只有一个来源：**服务器上这个文件本身**。需要拿的人自己 SSH 上去看 —— **不要写进本文档、issue、聊天记录、附件或任何提交**（本仓库是公开的，Multica 工作区也没有真正私密的通道）。完整口径见 [敏感信息怎么处理](SECRETS.md)。

- `<数据库地址>` / `<端口>`：数据库实例的地址和端口。这台机器上的 PostgreSQL 是**几个服务共用**的，地址以运维交接为准
- `<用户名>` / `<密码>`：数据库账号
- `FASTPAY_JWT_SECRET`：随机串，用 `openssl rand -hex 48` 自己生成，换服务器时重新生成

**这个文件绝对不能提交进仓库。**

> 🔎 **后面所有需要连数据库的命令，都从这个 env 文件里读地址，不要在命令里手写。**
> 下面第三节的备份/迁移命令就是这么写的 —— 好处有两个：命令可以照抄不用改，文档里也永远不会出现真实地址。

### 三个容易踩的坑

1. **`bootstrap.yml` 不生效。** 本项目没有引入 spring-cloud，Spring Boot 3 根本不读 `bootstrap.yml`。改那里面的 `active: prod` 没有任何作用。真正决定用哪套配置的是环境变量 `SPRING_PROFILES_ACTIVE`。
2. **`DB_URL` / `DB_USERNAME` / `DB_PASSWORD` / `FASTPAY_JWT_SECRET` 这四个没有默认值，漏配就直接启动失败。** 这是故意的 —— 比连错库、或者悄悄用一个公开的弱密钥要安全得多。换服务器、重建这个文件时四个都要带上。
3. **接口文档默认关闭。** `application-prod.yml` 里 `springdoc.api-docs.enabled` 默认 `false`，nginx 里也挡了一层。要临时排查接口时把 `SPRINGDOC_API_DOCS_ENABLED=true` 打开并重启，**用完记得关回去**（还要把 nginx 里那条 `return 404` 一并临时注释掉才看得到）。

## 三、部署 / 更新版本

每次发新版本，按下面顺序走。**如果这一版包含数据库结构变更（`fastpay-server/src/main/resources/db/migration/` 下有新的 `Vx_x__*.sql`），必须先跑迁移脚本再重启后端**，否则后端能起来但一登录就 500，谁都进不去。

```bash
# 0. 先备份，再跑迁移
#    ⚠️ 备份（紧接着下面这一段 ssh）每次发版都要做，跟这一版有没有迁移脚本无关。
#       只有再往下的"传迁移脚本""跑迁移"那两段，才是"这一版带迁移才做"。
#    备份统一放服务器的 /root/fastpay-backups/（不要放 /opt/fastpay，会被后续部署覆盖）。
#    地址/账号/密码全部从 env 文件读 —— 命令里不出现任何真实值，可以直接照抄。
#    下面的 VER 换成本次要发的版本号（例如 v1.2.0），其余原样。
ssh root@150.158.99.251 '
  set -a; . /etc/fastpay/fastpay-server.env; set +a
  export PGPASSWORD="$DB_PASSWORD"
  # 从 DB_URL 里拆出 host / port / dbname，不要手写
  eval $(echo "$DB_URL" | sed -nE "s#^jdbc:postgresql://([^:/]+):([0-9]+)/([^?]+).*#DBH=\1 DBP=\2 DBN=\3#p")
  # 拆不出来就当场停住。少了这一句，万一 env 里的 DB_URL 没写端口，上面会静默拆出三个空值，
  # 下面的 pg_dump 会报一个跟真实原因八竿子打不着的错，排查起来很浪费时间。
  # （sed 用 -n ... p 是配套的：没匹配上就什么都不输出，eval 不会拿原串当命令去执行，报错才干净）
  [ -n "$DBH" ] && [ -n "$DBP" ] && [ -n "$DBN" ] || { echo "DB_URL 解析失败，请检查 /etc/fastpay/fastpay-server.env 里的 DB_URL 是否形如 jdbc:postgresql://主机:端口/库名"; exit 1; }
  VER=<本次版本>
  TS=$(date +%Y%m%d-%H%M%S)
  mkdir -p /root/fastpay-backups
  pg_dump -h "$DBH" -p "$DBP" -U "$DB_USERNAME" -d "$DBN" -F c \
    -f /root/fastpay-backups/fastpay-${TS}-before-${VER}.dump
  cp -a /opt/fastpay/fastpay-server-1.0.0.jar /root/fastpay-backups/fastpay-server-1.0.0.jar.pre-${VER}-${TS}
  tar -czf /root/fastpay-backups/www-pre-${VER}-${TS}.tar.gz -C /opt/fastpay/www .
  # 备份完先验一下能读，别等到要回退时才发现是坏的（应打印出表的张数）
  pg_restore -l /root/fastpay-backups/fastpay-${TS}-before-${VER}.dump | grep -c "TABLE DATA"
'

# 传本次要跑的迁移脚本（下面示例是 V1.1，如果这版没有迁移就跳过这一步）
scp fastpay-server/src/main/resources/db/migration/V1_1__admin_account_management_pg.sql \
    root@150.158.99.251:/root/fastpay-ops/

# 跑迁移（这版没有迁移就跳过这一步）
# 生产用 PostgreSQL 走 pg 版本；如果哪台机器用的是 MySQL，换 mysql 版本
# 同样从 env 读连接信息；ON_ERROR_STOP=1 保证出错立刻停，不会跑一半还往下走
ssh root@150.158.99.251 '
  set -a; . /etc/fastpay/fastpay-server.env; set +a
  export PGPASSWORD="$DB_PASSWORD"
  eval $(echo "$DB_URL" | sed -nE "s#^jdbc:postgresql://([^:/]+):([0-9]+)/([^?]+).*#DBH=\1 DBP=\2 DBN=\3#p")
  [ -n "$DBH" ] && [ -n "$DBP" ] && [ -n "$DBN" ] || { echo "DB_URL 解析失败，请检查 /etc/fastpay/fastpay-server.env 里的 DB_URL 是否形如 jdbc:postgresql://主机:端口/库名"; exit 1; }
  psql -h "$DBH" -p "$DBP" -U "$DB_USERNAME" -d "$DBN" -v ON_ERROR_STOP=1 -e \
    -f /root/fastpay-ops/V1_1__admin_account_management_pg.sql
'

# 1. 传后端 jar
scp fastpay-server/target/fastpay-server-1.0.0.jar root@150.158.99.251:/opt/fastpay/

# 2. 传前端（先打包再解压，避免半截文件被用户访问到）
tar -czf admin.tar.gz -C fastpay-admin/dist .
tar -czf merchant.tar.gz -C fastpay-merchant/dist .
scp admin.tar.gz merchant.tar.gz root@150.158.99.251:/opt/fastpay/

ssh root@150.158.99.251 '
  cd /opt/fastpay
  rm -rf www/fastpay-admin/* www/fastpay-merchant/*
  tar -xzf admin.tar.gz    -C www/fastpay-admin
  tar -xzf merchant.tar.gz -C www/fastpay-merchant
  rm -f admin.tar.gz merchant.tar.gz
  chown -R fastpay:fastpay /opt/fastpay
  # ⚠️ 这两行不能省，原因见下
  chmod -R go-w /opt/fastpay/www
  chmod 755 /opt/fastpay/www/fastpay-admin /opt/fastpay/www/fastpay-merchant
'

# 3. 重启后端
ssh root@150.158.99.251 'systemctl restart fastpay-server && sleep 15 && systemctl status fastpay-server --no-pager'

# 4. 把这次发的版本号记到服务器上（把 v1.5.0 换成本次发的版本号）
#    别跳过：第 5 步的验证脚本靠这个文件核对「发上去的到底是哪一版」。
#    你传了版本号给脚本、这个文件却不在，脚本会直接判失败。
ssh root@150.158.99.251 'echo v1.5.0 > /opt/fastpay/DEPLOYED_VERSION'

# 5. 验一下
curl -s -o /dev/null -w '%{http_code}\n' https://pay.copliot.cloud/fastpay-admin/
curl -s -o /dev/null -w '%{http_code}\n' https://pay.copliot.cloud/fastpay-merchant/
```

> 💡 **忘了跑 `v1.2.0` 那个 `V1_1` 会怎样？**
> 从 `v1.2.0` 起，后端启动时会自检 `fp_admin` 表结构。如果发现缺 `token_version` 列，会**直接启动失败**并在日志里指名要跑哪个脚本。宁可服务不起来（`systemctl status` 立刻报红，运维当场就知道），也不要服务起来了但一登录就 500 —— 后者要等真实用户来投诉才能发现。
> 出现"启动失败 + 日志提示缺列"时，回去补跑 0 步骤里的迁移那两段，再重启即可，**不要**去改 `application-prod.yml` 或 jar。

> 📌 **别把两套编号搞混**：`v1.2.0` 是**程序的发布版本号**（git tag），`V1_1` 是**数据库结构的版本号**（迁移脚本文件名）。
> 两者不是一一对应的 —— 有的程序版本不带数据库变更，就没有对应的迁移脚本。看部署要跑哪个脚本，以下面这张表和 `fastpay-server/src/main/resources/db/migration/` 目录为准。

### 哪个版本要跑迁移 —— 发版前先查这张表

**按你「这次要发的版本号」查这一行**，看它有没有新增脚本：

> 表里只写文件名。**脚本都在仓库的 `fastpay-server/src/main/resources/db/migration/` 下面**，`scp` 的时候要带上这一整段路径（第三节的命令里是全路径，可以直接照抄）。

| 发布版本 | 这一版新增的迁移脚本 | 这个脚本做了什么 |
| --- | --- | --- |
| `v1.0.0` | **无** | 上线基线，库是用 `fastpay-server/src/main/resources/db/init-pg.sql` 直接建的 |
| `v1.1.0` | **无** | 只修了订单详情显示，不动表结构 |
| `v1.2.0` | `V1_1__admin_account_management_pg.sql` | `fp_admin.username` 放宽到 100 字符；新增 `token_version INT DEFAULT 0` 列 |
| `v1.3.0` | **无** | 这一版都是文档和前端改动，不动表结构 |
| `v1.4.0` | `V1_2__password_hash_bcrypt_pg.sql` | `fp_admin.password`、`fp_merchant.password` 从 64 放宽到 255 字符，容纳 bcrypt |

几条配套说明：

- **表里写「无」的版本**：第三节 0 步骤里「传本次要跑的迁移脚本」「跑迁移」那两段跳过；**0 步骤开头那段备份（备份库 + 备份 jar + 备份前端）照做**，做完直接跳到第 1 步传 jar。
- **本地或老装机用 MySQL 的**：把文件名里的 `_pg` 换成 `_mysql`，两个文件内容等价，只是数据库方言不同。
- **跨版本升级**（比如线上还停在 `v1.2.0`，这次直接发 `v1.4.0`）：把中间每一版「新增」那一列的脚本**按版本从小到大依次跑一遍**。所有脚本重复跑都不会出问题（用了 `IF NOT EXISTS`），已经跑过的再跑一次不会中断部署。除了 `V1_1` 里有一句把新加的 `token_version` 列的空值补成 0（`UPDATE ... WHERE token_version IS NULL`，重复跑也没事），其余都只是放宽字段宽度，**不动任何已有数据**。
- 跑法就是第三节 0 步骤里「传本次要跑的迁移脚本」「跑迁移」那两段命令，把 `scp` 和 `psql` 里的文件名换成本次要跑的即可，其余照抄。

> ✅ **这张表要是没跟上，用命令现查，别猜。**
> 线上现在跑的是 `<旧版本>`、这次要发 `<新版本>`，那么下面这条命令列出来的，就是本次要跑的全部脚本：
>
> ```bash
> git diff --name-only --diff-filter=A <旧版本>..<新版本> -- fastpay-server/src/main/resources/db/migration/
> ```
>
> **没有任何输出 = 这次不用跑迁移。**
>
> 📌 **打完 tag 之后，顺手回来给这张表补一行。** 这张表跟着 tag 一起走，别再出现「文档写着要跑、实际根本不用跑」这种对不上的情况。

> 💡 **`v1.4.0` 那个 `V1_2` 漏跑会怎样？** 没有 `V1_1` 那么严重。新密码用的 bcrypt 哈希是 60 个字符，老字段 `VARCHAR(64)` 其实还装得下，所以即使忘了放宽，也不会一登录就 500。放宽到 255 是为了给以后（比如换更强的 Argon2）留余量，属于该做的保险，**请照常跑**，别省。
>
> 📌 老账号（历史 MD5 密码）不需要任何人重置：后端新旧格式都认，用户照常登录，**登录成功后系统会自动把这条记录重算成 bcrypt 存回去**，老格式随大家陆续登录自然消失，全程无感。

**「订单回调结果落库」版本需要执行的迁移脚本清单**（`V1_3`）：

| 数据库 | 脚本 | 做了什么 |
| --- | --- | --- |
| PostgreSQL（生产） | `db/migration/V1_3__order_notify_result_pg.sql` | `fp_pay_order` 新增 `notify_result VARCHAR(1000)` 和 `notify_error VARCHAR(500)` 两列，把商户回调返回的内容和失败原因存进库 |
| MySQL（本地/老装机） | `db/migration/V1_3__order_notify_result_mysql.sql` | 同上，MySQL 方言 |

步骤和上面 `V1_1` / `V1_2` 完全一样：把 `scp` 和 `psql` 命令里的文件名换成 `V1_3__order_notify_result_pg.sql`，其余照抄。

**迁移脚本的执行顺序是 `V1_1` → `V1_2` → `V1_3`。** 库落后好几版时按顺序补跑，三个脚本都是幂等的，已经跑过的再跑一遍不会出事。

> 💡 **上线前实测过**（PostgreSQL 14，一张 20 万行订单的表）：加这两列**耗时个位数毫秒**，不锁表、不需要停机窗口；20 万行老订单一行没动，两个新列是 `NULL`（不会在后台误显示成「回调成功」）。回退脚本也实测过：执行完订单数据一行没丢，退完还能再升回去。
>
> ⚠️ **顺序不能反**：必须先跑脚本、再换 jar。反过来做，中间那段时间订单接口会全挂，日志报「字段不存在」。
>
> 💡 **漏跑迁移会怎样？** 比 `V1_1` 温和一些（不会启动失败），但**只要有人点开订单详情或订单列表就会报错** —— 因为新版 jar 会去查这两列。所以别省。
>
> ⚠️ **原厂 MySQL 上要改一处**：脚本里的 `ADD COLUMN IF NOT EXISTS` 是 MariaDB 的写法，**原厂 MySQL 不支持**（MySQL 8.4 手册的 ALTER TABLE 语法里没有这个选项），照跑会报语法错 `ERROR 1064`。在原厂 MySQL 上执行时把两句里的 `IF NOT EXISTS` 去掉即可。生产用的是 PostgreSQL，走不到这里。

前端是纯静态文件，替换完立刻生效，不用重启 nginx。

> ⚠️ **上面那两行 `chmod` 每次更新都要跑，不是一次性的。**
> 在 Windows 上打的 `tar` 包不带 Unix 权限位，解压到 Linux 上会变成 `777` / `666` —— 也就是**这台机器上任何账号都能改支付页和登录页**。改掉之后页面看起来一模一样，但商户输入的账号密码会被送到别人手里，非常难发现。
> 页面目录 `755`、文件 `644` 就够了（nginx 只需要读）。改完可以用 `find /opt/fastpay -perm /o+w | wc -l` 确认结果是 `0`。

**升级前先留一份能退回去的东西。** 正经的完整备份就是上面 0 步骤开头那段（库 + jar + 前端，还会验一下备份读不读得出来），**每次发版都跑它**，别拿下面这条顶替。

下面这条只备份 jar，是给「临时只换了个 jar、库和前端都没碰」这种小改动兜底用的最低限度：

```bash
ssh root@150.158.99.251 'cp /opt/fastpay/fastpay-server-1.0.0.jar /opt/fastpay/fastpay-server-1.0.0.jar.bak-$(date +%F)'
```

### 发完必须逐条交代这次到底动了什么（硬性）

发布做完，在对应 issue 的结果评论里**照抄下面这张表并逐行填**。四行都要写，**没动的也要明写「没动」** —— 不许省略、不许只写一句「发布完成」。

| 动了什么 | 这次 | 补充说明 |
| --- | --- | --- |
| 后端 jar | 传了 / 没传 | 传了写清是哪个 tag 构建的 |
| 管理后台前端 | 传了 / 没传 | |
| 商户中心前端 | 传了 / 没传 | |
| 数据库 | 跑了迁移 / 没动 | 跑了写清脚本文件名 |

同一条评论里还要带上：**本次 tag**、**备份放在哪**（回退时要用的那个路径）、**验证结果**（至少两个前端页面的 HTTP 状态码）。

**为什么这是硬性的 —— 2026-08-20 真出过一次。**

上一版的发布记录里写着「前端这次没有改动，所以没有重新发布前端」。后来去服务器上一查文件时间戳，**那天早上前端其实也一并重传了** —— 记录说没传，实际传了。

结果是：有人照着这份不准的记录推断「那前端上的署名肯定还是旧的」，专门开了一条 issue（MTM-198）派人去线上核查，核完发现线上本来就是对的，白跑一趟。

这次只是浪费一次人力。但发布记录不准这件事本身会让**后面所有人的判断跟着歪** —— 这回是虚惊，下回可能是真出了事却因为记录对不上而没人发现。

**判断标准就一句话：别人不该需要 ssh 上服务器看文件时间戳，才能知道你这次到底传了什么。**

（规矩来源：MTM-198，2026-08-20 由需求方拍板加入。）

## 四、出问题怎么办

### 服务挂了 / 起不来

```bash
systemctl status fastpay-server          # 现在什么状态
journalctl -u fastpay-server -n 100      # systemd 层面的日志
tail -n 200 /opt/fastpay/logs/fastpay-server.log   # 应用日志
```

常见原因：

| 日志里看到 | 说明 | 怎么修 |
| --- | --- | --- |
| `Could not resolve placeholder 'fastpay.jwt.secret'` | profile 没生效 | 检查 env 文件里有没有 `SPRING_PROFILES_ACTIVE=prod` |
| `Could not resolve placeholder 'DB_URL'` | 环境变量没读到 | 检查 `/etc/fastpay/fastpay-server.env` 和它的权限 |
| `password authentication failed` | 数据库账号密码不对 | 核对 env 文件里的 `DB_USERNAME` / `DB_PASSWORD` |
| `fp_admin 表缺少 token_version 列` | 数据库还是老版结构，缺 `V1_1` 迁移 | 补跑第三节 0 步骤里的迁移脚本，再重启后端 |
| `ClassNotFoundException: org.apache.commons.lang3.StringUtils` | jar 打漏了依赖 | 见下面「已知坑」 |

服务配了自动重启（`Restart=always`，5 秒后重试），进程被杀会自己起来；服务器重启也会自动启动（`systemctl is-enabled` 是 `enabled`）。

### 回退到上一个版本

```bash
ssh root@150.158.99.251 '
  cp /opt/fastpay/fastpay-server-1.0.0.jar.bak-<日期> /opt/fastpay/fastpay-server-1.0.0.jar
  chown fastpay:fastpay /opt/fastpay/fastpay-server-1.0.0.jar
  systemctl restart fastpay-server
  # 版本标记也要跟着退回去，换成你退到的那一版（跟第三节第 4 步写的是同一个文件）。
  # 不改的话，服务器上记的还是刚被你退掉的那一版，往后谁来查「现在线上是哪一版」都会被这行字骗一次；
  # 验证脚本也会拿这个假版本号去比对，比出一个「版本对得上」的假绿。
  echo <退回到的版本> > /opt/fastpay/DEPLOYED_VERSION
'
```

前端回退：把上一个版本的 `dist` 重新解压覆盖即可。

回退完照样跑一遍验证脚本，版本号写**你退回到的那一版**（不是刚退掉的那一版）：

```bash
ssh root@<服务器> 'bash -s <退回到的版本>' < deploy/verify-release.sh
```

> ⚠️ **回退时数据库要不要一起退回去？不用，也不要退。**
> 迁移脚本（比如 `v1.2.0` 带的 `V1_1__admin_account_management_pg.sql`）加的列，**旧版本 jar 完全不受影响**，留着就行。
> 原因：MyBatis 生成的 SQL 是按实体类逐个列出字段名的，表里多出来一列它根本不会去读。
> 这一条 2026-08-19 上线 1.2.0 时实测过：迁移脚本执行完、还没换 jar 的时候，仍在跑的旧版本 jar 登录接口返回 `code:200`，日志无异常。
> **所以回退 = 只换 jar + 换前端，10 分钟内能完成，不需要动数据库。**
> 反过来，如果手贱把列删了，再想滚回新版本就得重新跑一次迁移脚本，纯属自找麻烦。
> 只有确认要长期停留在旧版本、且要彻底清理时才考虑删列 —— 那种情况先从 `/root/fastpay-backups/` 里的整库备份恢复，别手工 `DROP COLUMN`。

**生产环境禁止直接改服务器上的代码或配置文件来"临时修一下"。** 要改就改仓库、重新发版。

### 网站整个打不开

先分清是本项目的问题还是 nginx 整体的问题：

```bash
systemctl status nginx
nginx -t
curl -s -o /dev/null -w '%{http_code}\n' https://multica.copliot.cloud   # 别的站点还活着吗
```

**如果确认是本项目的 nginx 配置把整台机器搞挂了，最快的止血方式是把本项目的配置文件挪走：**

```bash
mv /etc/nginx/conf.d/pay.copliot.conf /root/pay.copliot.conf.bak
nginx -t && systemctl reload nginx
```

这样 `pay.copliot.cloud` 会打不开，但其它三个站点立刻恢复。

## 五、nginx 配置里有几个地方不能删

`/etc/nginx/conf.d/pay.copliot.conf` 里：

- `location /fastpay-server/` 里的 `proxy_set_header Upgrade $http_upgrade;` 和 `proxy_set_header Connection $connection_upgrade;`
  —— 支付成功后页面自动跳转靠 WebSocket（`/fastpay-server/ws/pay/{商户号}/{订单号}`）实现，**删了以后付完款页面不会自动跳，但也不报错，非常难查**。
- 两个前端 location 里的 `try_files $uri $uri/ /fastpay-xxx/index.html;`
  —— 前端是单页应用，没有这行的话用户在内页按 F5 会 404。
- `client_max_body_size 20m;`
  —— 收款二维码是以 base64 存的，请求体比较大。
- 挡接口文档那条 `return 404;`
  —— 后端配置里已经关了一层，这是第二层保险。

### ⚠️ 换到新服务器时必看：`$connection_upgrade` 不是 nginx 内置变量

上面 WebSocket 那行用到的 `$connection_upgrade`，定义在 `/etc/nginx/nginx.conf` 的 `http` 块里，**不在本项目的配置文件里**：

```nginx
map $http_upgrade $connection_upgrade {
    default upgrade;
    ''      close;
}
```

当前这台服务器上有这段（原来就有），所以直接用没问题。但**如果照本文档在一台新机器上部署，新机器的 nginx.conf 里通常没有这段**，`nginx -t` 会直接报：

```
nginx: [emerg] unknown "connection_upgrade" variable
```

解决办法：先把上面那段 `map` 加进新机器的 `/etc/nginx/nginx.conf` 的 `http {}` 块，再放本项目的配置文件。

## 六、证书

`pay.copliot.cloud` 复用服务器上已有的 `*.copliot.cloud` 泛域名证书：

- 证书：`/etc/nginx/ssl/copliot.cloud/fullchain.cer`
- 私钥：`/etc/nginx/ssl/copliot.cloud/copliot.cloud.key`

这张证书是几个站点共用的，续期由服务器上原有的机制负责，本项目不单独管。续期后记得 `systemctl reload nginx`。

## 七、已知行为（看起来像坏了，其实是设计如此）

1. **管理员在后台点「确认支付」，用户那边的支付页不会自动跳转。**
   支付页的自动跳转只由**真实的到账通知**（监听软件回调 `/fastpay-server/api/notify/callback`）触发，后台的人工确认走的是另一条路，不发这条推送。
   所以**不要用「后台手动确认」去测自动跳转功能** —— 那样测出来永远是"不跳"，会误判成故障。要验这个功能，得模拟一次真实的到账回调。

2. **支付页上的倒计时走完（默认 3 分钟），订单会被自动关掉。**
   有个定时任务在扫超时订单。测试时如果隔太久再去看订单，状态会变成已关闭，这是正常的。

## 八、已知坑（改代码前先看这里）

1. **`fastpay-server/pom.xml` 里的 `commons-lang3` 不能写 `<scope>test</scope>`。**
   springdoc 在运行时要用 `org.apache.commons.lang3.StringUtils`。如果把 commons-lang3 声明成 test 作用域，Maven 的就近原则会把 springdoc 传递进来的那份 compile 依赖一起降级成 test，打出来的可执行 jar 里就没有这个包，服务一启动就 `ClassNotFoundException`。
   注意：**跑 `mvn test` 是发现不了这个问题的**，必须真的 `java -jar` 起一次才能发现。

2. **`fastpay-merchant` 依赖 `less`。**
   支付页和支付结果页用了 `lang="less"` 的样式，`package.json` 里必须保留 `less` 这个 devDependency，否则 `npm run build` 直接失败。

3. **首次部署时，管理员账号密码从哪来。**（`v1.2.0` 起，公开仓库里不再有默认的 `admin/123456`）
   - 生产 yml 里只配了 `FASTPAY_ADMIN_INITIAL_USERNAME`（默认 `admin`），**故意不设 `FASTPAY_ADMIN_INITIAL_PASSWORD`**。
   - 首次启动、库里没有任何管理员时，`InitConfig` 会随机生成一个 16 位密码，明文只写到 warn 日志里一次，形如：
     ```
     WARN c.fastpay.service.impl.AdminServiceImpl - ==========================...
     WARN c.fastpay.service.impl.AdminServiceImpl - 首次启动，已自动创建管理员账号：
     WARN c.fastpay.service.impl.AdminServiceImpl -   账号：admin
     WARN c.fastpay.service.impl.AdminServiceImpl -   初始密码：xxxxxxxxxxxxxxxx
     ```
     运维在 `journalctl -u fastpay-server | grep '初始密码'` 里拿到之后，**立刻登进后台改成自己的密码**，之后就再也拿不到了。
   - 如果需求方偏要指定一个初始密码（例如迁移场景），在 `/etc/fastpay/fastpay-server.env` 里加一行 `FASTPAY_ADMIN_INITIAL_PASSWORD=<你想要的密码>`，然后首次启动。用完这一次记得**立刻删掉这一行**（不然下次重装库时又会被人在 env 文件里看到）。
   - `application-prod.yml` 里的 JWT 密钥同样没有默认值，必须由环境变量注入；`application-dev.yml` 里那个开发用的默认密钥仍然是公开的，本地开发无所谓，但**不要拿 dev 配置上生产**。
   - **拿到之后放哪、上线验证该用哪个账号登后台**，见 [敏感信息怎么处理](SECRETS.md) 第四、五节。**别用需求方本人的账号去做验收。**

4. **前端目录权限每次更新都要重新收紧。** 见上面「三、部署 / 更新版本」里的说明 —— Windows 打的 tar 包解压到 Linux 会变成全局可写。
