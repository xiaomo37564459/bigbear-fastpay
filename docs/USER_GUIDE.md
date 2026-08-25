# FAST 易支付 - 使用说明文档

## 一、快速开始

### 1.1 环境要求

| 环境 | 版本要求 |
|------|----------|
| JDK | 17+ |
| Node.js | 18+ |
| MySQL | 8.0+ |
| Maven | 3.8+ |

### 1.2 安装部署

#### 步骤一：克隆项目

```bash
git clone https://github.com/xiaomo37564459/bigbear-fastpay.git
cd bigbear-fastpay
```

#### 步骤二：初始化数据库

数据库名必须叫 `bigbear_fastpay` —— 这是 `application-dev.yml` 默认连的库名（步骤三里能看到）；起别的名字，步骤四启动服务时连不上。

```sql
-- 创建数据库
CREATE DATABASE bigbear_fastpay DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;

-- 使用数据库
USE bigbear_fastpay;

-- 执行初始化脚本
source fastpay-server/src/main/resources/db/init.sql
```

#### 步骤三：配置后端服务

`fastpay-server/src/main/resources/` 底下有两份配置，看你要跑哪种模式：

- `application-dev.yml` —— **本地开发用这份**。数据库、JWT、支付页面域名都写好了本地能直接用的默认值，一行不改也能起来。
- `application-prod.yml` —— **生产部署用**。里面所有值都要靠环境变量注入，仓库里不留任何真实连接信息；漏配就直接启动失败，避免默认密码上线。

**本地开发就编辑 `fastpay-server/src/main/resources/application-dev.yml`。**里面**要跟着环境切换的项**（数据库地址、账号密码、JWT 密钥、支付页面域名、CORS 白名单等）都写成 `${环境变量名:默认值}` 的形式：改冒号后面的默认值本地立刻生效，上生产走环境变量覆盖，两条路互不打架。**固定不变的普通值**（端口 `7001`、令牌有效期、管理员初始账号密码、订单超时时间等）就直接写死，不套壳。

```yaml
server:
  port: 7001
  servlet:
    context-path: /fastpay-server

spring:
  datasource:
    driver-class-name: ${DB_DRIVER:com.mysql.cj.jdbc.Driver}
    # 默认连本地 MySQL 的 bigbear_fastpay 库（跟步骤二里创建的库名一致）
    url: ${DB_URL:jdbc:mysql://localhost:3306/bigbear_fastpay?useUnicode=true&characterEncoding=utf8&zeroDateTimeBehavior=convertToNull&useSSL=false&serverTimezone=Asia/Shanghai&allowPublicKeyRetrieval=true}
    # 默认为空，起服务前先把冒号后面的默认值改成你本机 MySQL 的账号密码
    username: ${DB_USERNAME:}
    password: ${DB_PASSWORD:}

fastpay:
  jwt:
    # 本地开发用的示意密钥。想换成自己的：环境变量 FASTPAY_JWT_SECRET=$(openssl rand -hex 48) 覆盖
    secret: ${FASTPAY_JWT_SECRET:dev-only-jwt-secret-do-not-use-in-prod-1a2b3c4d5e6f7890}
    # Token 有效期（小时）
    expire-hours: 12

  # 首次启动时数据库里如果还没有管理员，会用这里的账号密码建一个
  admin:
    username: admin
    password: "123456"

  pay:
    # 订单超时时间（分钟）
    order-timeout-minutes: 3
    # 支付页面域名（商户前端地址），本地默认指向商户前端 dev server 的 3002 端口
    page-domain: ${FASTPAY_PAGE_DOMAIN:http://localhost:3002/fastpay-merchant}
```

⚠️ 数据库跑不起来最常见的原因就是账号密码没改。示例里的 `${DB_USERNAME:}` 表示「环境变量 DB_USERNAME 没设就用冒号后面的默认值」，冒号后面现在是空的 —— 本地起服务前，要么把冒号后面填成你 MySQL 的账号密码，要么在启动命令里带上 `DB_USERNAME=xxx DB_PASSWORD=yyy` 环境变量。

不要把默认值改成真实生产账号密码往仓库提 —— 提上去公开代码库就等于把线上的钥匙挂在网上。

#### 步骤四：启动后端服务

```bash
cd fastpay-server
mvn spring-boot:run -Dspring-boot.run.profiles=dev
```

⚠️ 一定要带上 `-Dspring-boot.run.profiles=dev`，否则读不到上面的 `application-dev.yml`，数据库连接、JWT、支付配置一个都加载不进来。项目里没有默认的 `application.yml`，`bootstrap.yml` 也不会自动激活 profile（Spring Boot 3 不读它，只是留档说明）。

服务地址：http://localhost:7001/fastpay-server

#### 步骤五：启动管理后台

```bash
cd fastpay-admin
npm install
npm run dev
```

访问地址：http://localhost:3001

**默认账号**：`admin` / `123456`（`application-dev.yml` 里配好的初始管理员，首次启动服务时会自动写进数据库；生产环境是随机生成密码写到启动日志里，不用这个默认值）

#### 步骤六：启动商户平台

```bash
cd fastpay-merchant
npm install
npm run dev
```

访问地址：http://localhost:3002

---

## 二、管理后台使用指南

### 2.1 登录系统

1. 访问 http://localhost:3001
2. 输入管理员账号密码
3. 点击登录进入系统

### 2.2 控制台

控制台展示系统整体运营数据：

- **今日订单数**：当日创建的订单总数
- **今日成交额**：当日支付成功的总金额
- **今日成功率**：支付成功订单占比
- **商户总数**：系统注册的商户数量
- **订单趋势图**：近期订单数量变化
- **支付方式占比**：微信/支付宝使用比例
- **活跃商户**：交易量最大的商户列表

### 2.3 商户管理

#### 创建商户

1. 点击「新增商户」按钮
2. 填写商户信息：
   - **商户名称**：商户的显示名称
   - **登录用户名**：商户登录账号
   - **登录密码**：商户登录密码
   - **联系人**：商户联系人姓名
   - **联系电话**：商户联系电话
   - **回调地址**：默认支付回调通知地址
3. 点击确定完成创建

#### 管理商户

- **查看详情**：查看商户完整信息
- **编辑**：修改商户信息
- **启用/禁用**：控制商户状态
- **重置密钥**：重新生成 API 密钥

### 2.4 店铺管理

#### 创建店铺

1. 选择所属商户
2. 点击「新增店铺」
3. 填写店铺信息：
   - **店铺名称**：店铺显示名称
   - **店铺描述**：店铺简介
   - **联系人**：店铺联系人
   - **联系电话**：店铺联系电话

#### 管理二维码

1. 选择店铺
2. 点击「添加二维码」
3. 上传收款二维码图片
4. 系统自动解析二维码内容
5. 选择对应的支付通道
6. 确认添加

### 2.5 订单管理

#### 订单列表

展示所有订单信息：

| 字段 | 说明 |
|------|------|
| 平台订单号 | 系统生成的唯一订单号 |
| 商户订单号 | 商户系统的订单号 |
| 商户 | 所属商户名称 |
| 店铺 | 所属店铺名称 |
| 金额 | 订单金额 |
| 支付类型 | 微信/支付宝 |
| 状态 | 待支付/已支付/已过期/已关闭 |
| 回调状态 | 未通知/成功/失败 |
| 创建时间 | 订单创建时间 |

#### 订单操作

- **查看详情**：查看订单完整信息
- **确认支付**：手动确认订单已支付（待支付订单）
- **关闭订单**：关闭未支付的订单
- **重发通知**：重新发送回调通知（已支付且通知失败的订单）

---

## 三、商户平台使用指南

### 3.1 商户登录

1. 访问 http://localhost:3002
2. 输入商户账号密码（由管理员创建）
3. 点击登录进入系统

### 3.2 控制台

展示商户自己的运营数据：

- **今日订单**：当日订单数量
- **今日成交**：当日成交金额
- **本月成交**：本月累计成交
- **累计成交**：历史总成交额
- **快捷操作**：常用功能入口
- **最近订单**：最新的订单列表

### 3.3 店铺管理

#### 创建店铺

1. 点击「新增店铺」
2. 填写店铺信息
3. 确认创建

#### 管理二维码

1. 选择店铺
2. 切换到「二维码管理」标签
3. 添加收款二维码：
   - 上传二维码图片
   - 系统自动识别支付类型
   - 选择对应通道
   - 确认添加

#### 查看订单

1. 选择店铺
2. 切换到「订单记录」标签
3. 查看该店铺的所有订单

### 3.4 通道管理

#### 创建通道

1. 点击「新增通道」
2. 填写通道信息：
   - **通道名称**：通道显示名称
   - **支付类型**：微信/支付宝
   - **通道描述**：通道说明
3. 确认创建

#### 通道模板

点击「生成模板」可获取通道的监听回调配置，用于配置收款监听服务。

### 3.5 订单管理

查看和管理所有订单：

- **筛选订单**：按订单号、状态、支付类型筛选
- **查看详情**：查看订单完整信息
- **确认支付**：手动确认订单已支付
- **关闭订单**：关闭未支付订单
- **重发通知**：重新发送回调通知

### 3.6 开发配置

#### 查看 API 信息

- **商户编号**：用于 API 调用的商户标识
- **API 密钥**：用于签名的密钥（点击查看）
- **重置密钥**：重新生成 API 密钥

#### 配置回调地址

- **回调通知地址**：支付成功后的通知地址
- 点击「保存配置」生效

### 3.7 开发文档

商户平台内置完整的开发文档：

- **接口说明**：API 接口详细说明
- **签名算法**：签名生成方法和示例代码
- **代码示例**：PHP、Java、Python 等语言示例
- **WebSocket**：实时支付结果推送说明

---

## 四、API 对接指南

**接口文档已经统一收敛到一份：👉 [开发对接文档 docs/API.md](API.md)**

这份使用说明只讲「后台怎么用、怎么部署」，接口细节不再在这里重复维护。

**为什么要收敛？** 同一套接口在两个地方各写一份，改了一处忘了另一处，接入方照着过期的那份写代码就会踩坑 —— 之前这里的参数表就漏了必填的 `shopNo`、多了一个根本不存在的 `notifyUrl` 请求参数。**一份文档、一个出处，改代码时只用同步一个地方。**

`docs/API.md` 里有：

| 你想知道什么 | 去哪看 |
|---|---|
| 平台开了两套接口，我该用哪套 | [第一章](API.md#一先搞清楚这里有两套接口) |
| 一笔钱是怎么走完整个流程的（流程图） | [第二章](API.md#二一笔钱是怎么走完整个流程的) |
| `/api/pay/*` 原生接口的全部参数和返回 | [第三章](API.md#三平台原生接口-apipay) |
| 彩虹易支付协议接口（`submit.php` / `mapi.php` / `api.php`） | [第四章](API.md#四彩虹易支付协议接口-php) |
| 回调通知怎么处理（含完整 PHP 示例） | [第五章](API.md#五回调通知平台--你的系统) |
| 收款监听软件的上报接口 | [第六章](API.md#六收款监听软件的上报接口) |
| 商户改回调地址、重置密钥 | [第七章](API.md#七商户自己用的配置接口) |
| **两套签名算法**（各有能照着抄的完整例子） | [第八章](API.md#八两套签名算法千万别拿混) |
| 错误码分别代表什么 | [第九章](API.md#九错误码对照表) |
| 从零接入，一步步照着做 | [第十章](API.md#十从零接入照着做就行) |
| 签名验不过、回调收不到怎么排查 | [第十一章](API.md#十一出问题了怎么查) |
| WebSocket 实时推送怎么接 | [3.7](API.md#37-websocket-实时推送) |

在线接口文档 `/fastpay-server/doc.html` **只有本地开发环境能打开**（默认 `http://localhost:7001/fastpay-server/doc.html`，后端所有网址都挂在 `/fastpay-server` 前缀下，别漏了）。**线上默认是关掉的，而且是故意关的** —— 把 67 个接口的完整清单公开出去，等于把系统地图直接送给攻击者。所以线上环境有两道锁：

- 后端 `application-prod.yml` 里 `springdoc.api-docs.enabled` 和 `knife4j.enable` 默认都关着；
- 部署用的 Nginx（`deploy/nginx/pay.copliot.conf`）再挡一层，`/fastpay-server/doc.html` 这类路径直接返回 404。

真要临时排查接口，得两处一起放开：把环境变量 `SPRINGDOC_API_DOCS_ENABLED=true` 设上重启后端，再让运维在 Nginx 那条 `location ~ ^/fastpay-server/(...doc\.html...)` 规则上临时放行；用完记得关回去。

---

## 五、常见问题

> 接口对接类的问题（签名、回调、路径前缀）有更详细的排查手册：[docs/API.md 第十一章](API.md#十一出问题了怎么查)。

### 5.1 签名验证失败

**头号原因：把两套签名算法拿混了。** 平台有两套接口，签名规则不一样 —— 走 `/api/pay/*` 是「末尾拼 `&key=密钥`、结果转大写」，走 `submit.php` / `mapi.php` 是「末尾直接拼密钥、结果转小写」。
**一眼自查**：原生接口的签名一定是 32 位**大写**，易支付的一定是 32 位**小写**，大小写不对就是拿混了。

**其它常见原因**：
1. 漏了必填参数 `shopNo`（原生下单必填，且要参与签名）
2. 用错了密钥（要用 **API Secret**，不是 API Key，也不是登录密码）
3. 金额字符串对不上（签名用 `10.00`，请求里发 `10`）
4. 时间戳传成了 13 位毫秒（应该是 10 位秒）
5. 中文参数没用 UTF-8 编码算 MD5

逐条排查步骤见 [docs/API.md 11.1](API.md#111-签名老是验不过怎么查)。

### 5.2 回调通知收不到

**可能原因**：
1. 回调地址不可访问
2. 回调地址返回非 success
3. 服务器防火墙拦截

**解决方法**：
1. 确保回调地址外网可访问
2. 检查回调处理逻辑，确保返回 success
3. 检查服务器防火墙设置
4. 查看订单详情中的回调状态和次数

### 5.3 订单一直待支付

**可能原因**：
1. 二维码未正确配置
2. 支付监听服务未启动
3. 通道配置错误

**解决方法**：
1. 检查二维码是否正确上传和解析
2. 确认支付监听服务正常运行
3. 检查通道配置是否正确

### 5.4 WebSocket 连接失败

**可能原因**：
1. Nginx 没配 WebSocket 代理（默认配置转发不了 Upgrade 头）
2. 后端端口不通
3. 页面是 HTTPS 却用了 `ws://`，被浏览器拦掉

**解决方法**：
1. 按 [docs/API.md 11.5](API.md#115-websocket-连不上) 加一段 nginx 配置
2. 确认后端 **7001** 端口可访问（WebSocket 和 HTTP 走同一个端口，没有单独的端口）
3. HTTPS 页面必须用 `wss://`

---

## 六、生产部署

### 6.1 后端打包

```bash
cd fastpay-server
mvn clean package -DskipTests
```

生成的 jar 包位于 `target/fastpay-server-1.0.0.jar`

### 6.2 前端打包

```bash
# 管理后台
cd fastpay-admin
npm run build

# 商户平台
cd fastpay-merchant
npm run build
```

生成的静态文件位于 `dist` 目录

### 6.3 Nginx 配置

下面这份示例照着改一下域名和目录就能跑。三个关键点先说清楚，这几处任何一项跟真实服务对不上，都会直接 502 或 404：

- **后端跑在 `/fastpay-server` 这个前缀下、`7001` 端口上。** 前缀是 `application-*.yml` 里 `server.servlet.context-path` 的值，端口是同一份配置里 `server.port` 的值。所以后端 `location` 必须写 `/fastpay-server/`，`proxy_pass` 目标是 `http://127.0.0.1:7001`（不要在 URL 后再拼路径，让 Nginx 把整段请求路径原样透传给后端）—— 少一层、多一层，backend 就命不中 controller。
- **前端打包基址是 `/fastpay-admin/` 和 `/fastpay-merchant/`**（见两个 `vite.config.js` 里的 `base`）。两个前端不能挂在根路径 `/` 下，得挂到跟 `base` 同名的子路径下，`index.html` 里对静态资源的引用才对得上。把 `fastpay-admin/dist/*` 原样拷到 `/var/www/fastpay-admin/`，把 `fastpay-merchant/dist/*` 原样拷到 `/var/www/fastpay-merchant/` —— 目录名要跟前端的 `base` 一样，不要多套一层 `dist/`。
- **WebSocket 走同一个前缀同一个端口**（后端 `@ServerEndpoint("/ws/pay/...")` 挂在 `/fastpay-server` context-path 下）。所以下面一个 `location /fastpay-server/` 就够了，`Upgrade` / `Connection` 两行放里面既覆盖普通 HTTP 也覆盖 WS，不用单独再配一段。

```nginx
server {
    listen 80;
    server_name pay.your-domain.com;

    client_max_body_size 20m;   # 收款码是 base64 存的，请求体会比较大

    # 后端 API + WebSocket -> fastpay-server (127.0.0.1:7001)
    location /fastpay-server/ {
        proxy_pass http://127.0.0.1:7001;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;
        proxy_http_version 1.1;
        proxy_set_header Upgrade $http_upgrade;
        proxy_set_header Connection "upgrade";
        proxy_read_timeout 3600s;
    }

    # 前端静态文件根目录（下面两个 location 都从这里往下找）
    root /var/www;

    # 管理后台（单页应用，刷新不能 404）
    location /fastpay-admin/ {
        try_files $uri $uri/ /fastpay-admin/index.html;
    }
    location = /fastpay-admin { return 301 /fastpay-admin/; }

    # 商户平台（单页应用，刷新不能 404）
    location /fastpay-merchant/ {
        try_files $uri $uri/ /fastpay-merchant/index.html;
    }
    location = /fastpay-merchant { return 301 /fastpay-merchant/; }

    # 根路径默认进商户平台
    location = / { return 301 /fastpay-merchant/; }
}
```

改完先 `nginx -t` 检查语法，通过了再 `systemctl reload nginx`。

> 生产上真跑的完整版（HTTPS 证书、接口文档挡一层 404、静态资源长缓存等）就在仓库里：[`deploy/nginx/pay.copliot.conf`](../deploy/nginx/pay.copliot.conf) —— 上线时优先照抄这份。

### 6.4 启动服务

```bash
# 后台启动
nohup java -jar fastpay-server-1.0.0.jar --spring.profiles.active=prod > /dev/null 2>&1 &
```

---

## 七、技术支持

如有问题，请联系：

- **开发者**：xiaomo37564459
- **项目地址**：https://github.com/xiaomo37564459/bigbear-fastpay
