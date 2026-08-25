# FAST 易支付 - 对接演示项目

## 简介

这是 FAST 易支付的对接演示项目，展示了如何将 FAST 易支付集成到您的系统中。

## 功能演示

- ✅ 页面跳转支付
- ✅ API 接口支付
- ✅ 订单状态查询
- ✅ 回调通知处理
- ✅ 签名生成与验证

## 快速开始

### 1. 配置修改

编辑 `src/main/resources/application-dev.yml`（本地开发用的配置文件）。这份文件里的值都写成了 `${环境变量名:默认值}` 的形式，本地跑改默认值就行，上生产再走环境变量。

```yaml
fastpay:
  # 商户编号（在商户平台获取）
  merchant-no: ${FASTPAY_DEMO_MERCHANT_NO:your-merchant-no-here}
  # API 密钥（在商户平台获取）
  api-secret: ${FASTPAY_DEMO_API_SECRET:your-api-secret-here}
  # 支付网关地址
  gateway-url: ${FASTPAY_DEMO_GATEWAY_URL:http://localhost:7001/fastpay-server}
  # 异步通知地址（需要外网可访问）
  notify-url: ${FASTPAY_DEMO_NOTIFY_URL:http://localhost:7002/pay/notify}
  # 同步跳转地址
  return-url: ${FASTPAY_DEMO_RETURN_URL:http://localhost:7002/pay/return}
```

### 2. 启动项目

```bash
mvn spring-boot:run -Dspring-boot.run.profiles=dev
```

⚠️ 一定要带上 `-Dspring-boot.run.profiles=dev`，否则读不到上面的 `application-dev.yml`，商户号、密钥、网关地址一个都加载不进来，服务能起但接口全空。

### 3. 访问演示

打开浏览器访问：http://localhost:7002

## 项目结构

```
src/main/java/com/fastpay/demo/
├── config/
│   └── FastPayConfig.java      # 支付配置
├── controller/
│   └── PayController.java      # 支付控制器
├── service/
│   └── FastPayService.java     # 支付服务
└── util/
    └── SignUtil.java           # 签名工具

src/main/resources/
├── templates/
│   ├── index.html              # 首页
│   ├── page_pay.html           # 页面跳转支付
│   ├── api_pay.html            # API接口支付
│   └── result.html             # 支付结果
├── application-dev.yml         # 开发环境配置（本地跑用这份）
└── application-prod.yml        # 生产环境配置（部署时由环境变量注入真实值）
```

## 详细文档

请查看 [Demo 说明文档](../docs/DEMO.md)

## 技术支持

- **开发者**：xiaomo37564459
