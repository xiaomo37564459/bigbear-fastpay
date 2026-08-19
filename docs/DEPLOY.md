# 部署说明（pay.copliot.cloud）

这份文档写给要上线、要更新版本、要出事时把系统救回来的人。照着做就行。

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
DB_URL=jdbc:postgresql://10.0.0.11:5432/fastpay
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

**这个文件绝对不能提交进仓库。**

### 三个容易踩的坑

1. **`bootstrap.yml` 不生效。** 本项目没有引入 spring-cloud，Spring Boot 3 根本不读 `bootstrap.yml`。改那里面的 `active: prod` 没有任何作用。真正决定用哪套配置的是环境变量 `SPRING_PROFILES_ACTIVE`。
2. **`DB_URL` / `DB_USERNAME` / `DB_PASSWORD` / `FASTPAY_JWT_SECRET` 这四个没有默认值，漏配就直接启动失败。** 这是故意的 —— 比连错库、或者悄悄用一个公开的弱密钥要安全得多。换服务器、重建这个文件时四个都要带上。
3. **接口文档默认关闭。** `application-prod.yml` 里 `springdoc.api-docs.enabled` 默认 `false`，nginx 里也挡了一层。要临时排查接口时把 `SPRINGDOC_API_DOCS_ENABLED=true` 打开并重启，**用完记得关回去**（还要把 nginx 里那条 `return 404` 一并临时注释掉才看得到）。

## 三、部署 / 更新版本

每次发新版本，按下面顺序走。**如果这一版包含数据库结构变更（`fastpay-server/src/main/resources/db/migration/` 下有新的 `Vx_x__*.sql`），必须先跑迁移脚本再重启后端**，否则后端能起来但一登录就 500，谁都进不去。

```bash
# 0. 【只有本次或后续版本带迁移脚本时才做】先备份库、再跑迁移
#    - 备份放本地，别放到 /opt/fastpay 里，免得占空间和被后续部署覆盖
ssh root@150.158.99.251 \
  'PGPASSWORD=$DB_PASSWORD pg_dump -h <数据库host> -U <用户> -d fastpay -F c' \
  > /path/to/backups/fastpay-$(date +%F).dump

# 传本次要跑的迁移脚本（下面示例是 V1.1，如果这版没有迁移就跳过这一步）
scp fastpay-server/src/main/resources/db/migration/V1_1__admin_account_management_pg.sql \
    root@150.158.99.251:/tmp/

# 跑迁移（生产用 PostgreSQL 走 pg 版本；如果哪台机器用的是 MySQL，换 mysql 版本）
ssh root@150.158.99.251 \
  'PGPASSWORD=$DB_PASSWORD psql -h <数据库host> -U <用户> -d fastpay \
     -f /tmp/V1_1__admin_account_management_pg.sql && rm /tmp/V1_1__admin_account_management_pg.sql'

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

# 4. 验一下
curl -s -o /dev/null -w '%{http_code}\n' https://pay.copliot.cloud/fastpay-admin/
curl -s -o /dev/null -w '%{http_code}\n' https://pay.copliot.cloud/fastpay-merchant/
```

> 💡 **忘了跑迁移脚本会怎样？**
> 从 1.1 版本起，后端启动时会自检 `fp_admin` 表结构。如果发现缺 `token_version` 列，会**直接启动失败**并在日志里指名要跑哪个脚本。宁可服务不起来（`systemctl status` 立刻报红，运维当场就知道），也不要服务起来了但一登录就 500 —— 后者要等真实用户来投诉才能发现。
> 出现"启动失败 + 日志提示缺列"时，回去补跑 0 步骤，再重启即可，**不要**去改 `application-prod.yml` 或 jar。

**这一版（1.1）的迁移脚本清单**：

| 数据库 | 脚本 | 做了什么 |
| --- | --- | --- |
| PostgreSQL（生产） | `db/migration/V1_1__admin_account_management_pg.sql` | `fp_admin.username` 放宽到 100 字符；新增 `token_version INT DEFAULT 0` 列 |
| MySQL（本地/老装机） | `db/migration/V1_1__admin_account_management_mysql.sql` | 同上，MySQL 方言 |

两个脚本都是幂等的（用了 `IF NOT EXISTS`），误跑两次不会中断部署。

前端是纯静态文件，替换完立刻生效，不用重启 nginx。

> ⚠️ **上面那两行 `chmod` 每次更新都要跑，不是一次性的。**
> 在 Windows 上打的 `tar` 包不带 Unix 权限位，解压到 Linux 上会变成 `777` / `666` —— 也就是**这台机器上任何账号都能改支付页和登录页**。改掉之后页面看起来一模一样，但商户输入的账号密码会被送到别人手里，非常难发现。
> 页面目录 `755`、文件 `644` 就够了（nginx 只需要读）。改完可以用 `find /opt/fastpay -perm /o+w | wc -l` 确认结果是 `0`。

**升级前先留一份能退回去的东西**：

```bash
ssh root@150.158.99.251 'cp /opt/fastpay/fastpay-server-1.0.0.jar /opt/fastpay/fastpay-server-1.0.0.jar.bak-$(date +%F)'
```

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
| `fp_admin 表缺少 token_version 列` | 数据库还是老版结构，缺 1.1 迁移 | 补跑第三节 0 步骤里的迁移脚本，再重启后端 |
| `ClassNotFoundException: org.apache.commons.lang3.StringUtils` | jar 打漏了依赖 | 见下面「已知坑」 |

服务配了自动重启（`Restart=always`，5 秒后重试），进程被杀会自己起来；服务器重启也会自动启动（`systemctl is-enabled` 是 `enabled`）。

### 回退到上一个版本

```bash
ssh root@150.158.99.251 '
  cp /opt/fastpay/fastpay-server-1.0.0.jar.bak-<日期> /opt/fastpay/fastpay-server-1.0.0.jar
  chown fastpay:fastpay /opt/fastpay/fastpay-server-1.0.0.jar
  systemctl restart fastpay-server
'
```

前端回退：把上一个版本的 `dist` 重新解压覆盖即可。

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

3. **首次部署时，管理员账号密码从哪来。**（1.1 起，公开仓库里不再有默认的 `admin/123456`）
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

4. **前端目录权限每次更新都要重新收紧。** 见上面「三、部署 / 更新版本」里的说明 —— Windows 打的 tar 包解压到 Linux 会变成全局可写。
