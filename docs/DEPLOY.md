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
| 前端文件 | `/opt/fastpay/www/fastpay-admin/`、`/opt/fastpay/www/fastpay-merchant/` |
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
```

权限必须是 `640 root:fastpay`：

```bash
chown root:fastpay /etc/fastpay/fastpay-server.env
chmod 640 /etc/fastpay/fastpay-server.env
```

**这个文件绝对不能提交进仓库。**

### 两个容易踩的坑

1. **`bootstrap.yml` 不生效。** 本项目没有引入 spring-cloud，Spring Boot 3 根本不读 `bootstrap.yml`。改那里面的 `active: prod` 没有任何作用。真正决定用哪套配置的是环境变量 `SPRING_PROFILES_ACTIVE`。
2. **`FASTPAY_JWT_SECRET` 丢了不会报错，但会退回仓库里那个公开的默认密钥。** 换服务器或重建这个文件时，一定记得把它带上。

## 三、部署 / 更新版本

每次发新版本，重复下面 4 步就行。

```bash
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
'

# 3. 重启后端
ssh root@150.158.99.251 'systemctl restart fastpay-server && sleep 15 && systemctl status fastpay-server --no-pager'

# 4. 验一下
curl -s -o /dev/null -w '%{http_code}\n' https://pay.copliot.cloud/fastpay-admin/
curl -s -o /dev/null -w '%{http_code}\n' https://pay.copliot.cloud/fastpay-merchant/
```

前端是纯静态文件，替换完立刻生效，不用重启 nginx。

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

## 六、证书

`pay.copliot.cloud` 复用服务器上已有的 `*.copliot.cloud` 泛域名证书：

- 证书：`/etc/nginx/ssl/copliot.cloud/fullchain.cer`
- 私钥：`/etc/nginx/ssl/copliot.cloud/copliot.cloud.key`

这张证书是几个站点共用的，续期由服务器上原有的机制负责，本项目不单独管。续期后记得 `systemctl reload nginx`。

## 七、已知坑（改代码前先看这里）

1. **`fastpay-server/pom.xml` 里的 `commons-lang3` 不能写 `<scope>test</scope>`。**
   springdoc 在运行时要用 `org.apache.commons.lang3.StringUtils`。如果把 commons-lang3 声明成 test 作用域，Maven 的就近原则会把 springdoc 传递进来的那份 compile 依赖一起降级成 test，打出来的可执行 jar 里就没有这个包，服务一启动就 `ClassNotFoundException`。
   注意：**跑 `mvn test` 是发现不了这个问题的**，必须真的 `java -jar` 起一次才能发现。

2. **`fastpay-merchant` 依赖 `less`。**
   支付页和支付结果页用了 `lang="less"` 的样式，`package.json` 里必须保留 `less` 这个 devDependency，否则 `npm run build` 直接失败。

3. **仓库里的 JWT 密钥和默认管理员密码是公开的。**
   `application-prod.yml` 里 `fastpay.jwt.secret` 的默认值、以及数据库里初始的 `admin / 123456`，在公开仓库里都能看到。生产环境必须用环境变量覆盖密钥，并且第一时间改掉管理员密码。
