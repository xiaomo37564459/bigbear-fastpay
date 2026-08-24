/**
 * 开发文档页 —— 「兼容彩虹易支付协议」章节的全部文案和示例
 *
 * 为什么单独拆一个文件：
 * 1. 文档页模板本来就长，把纯文案挪出来，模板那边只管排版
 * 2. 这里的接口地址、参数、签名示例必须和后端实现一模一样，
 *    拆成模块之后能直接写单元测试盯着，哪天改错了测试会红
 *
 * 对照的后端实现：
 * - fastpay-server/src/main/java/com/fastpay/controller/pay/EpayGatewayController.java
 * - fastpay-server/src/main/java/com/fastpay/util/EpaySignUtil.java
 * - fastpay-server/src/main/java/com/fastpay/service/impl/PayOrderServiceImpl.java（sendEpayNotify）
 */

/**
 * 对外的易支付接口根地址。
 * 第三方系统后台里的「接口地址」就填这个，后面的 submit.php / mapi.php
 * 由对方系统自己往后拼，商户不用管 —— 这一条填错的人最多。
 */
export const EPAY_BASE_URL = 'https://pay.copliot.cloud/fastpay-server'

/**
 * 收款页所在的商户前端地址 —— 和上面的接口地址不是一个前缀，别写混。
 * 后端拼法是 page-domain + "/pay/" + 订单号（PayOrderServiceImpl.createEpayOrder），
 * 线上 page-domain 配的就是这个值（application-prod.yml 的 page-domain）。
 * 接口服务器 /fastpay-server 底下没有 /pay/ 这个路径，写成那样商户是打不开的。
 */
export const EPAY_MERCHANT_URL = 'https://pay.copliot.cloud/fastpay-merchant'

/** 开头那段人话说明：这一套是给谁用的 */
export const epayIntro = {
  who: '你要接的系统（sub2api、发卡站、各类商城）本身支持「易支付」的话，就用这一套 —— 对方系统一行代码都不用改，在它后台填三个配置就能收款。',
  when: '如果是你自己写的程序，用本页上面那套原生接口更简单，这一节可以不看。',
  note: '两套接口同时开着，互不影响，而且共用同一组商户编号和密钥，不用另外申请。'
}

/** 三个接口入口 */
export const epayEndpoints = [
  {
    path: '/submit.php',
    method: 'GET / POST',
    desc: '页面跳转支付。用户在对方系统点「去支付」，浏览器先到这里，平台再自动跳到收款页'
  },
  {
    path: '/mapi.php',
    method: 'GET / POST',
    desc: '接口下单。对方系统的后端调用，返回 JSON，里面带收款链接和二维码内容'
  },
  {
    path: '/api.php?act=order',
    method: 'GET / POST',
    desc: '查询订单状态。对方系统用来兜底轮询，确认这笔单到底付了没有'
  }
]

/**
 * 在第三方系统后台该怎么填 —— 这一段是整节里最重要的
 * 以 sub2api 为例，其他易支付系统的字段名大同小异
 */
export const epayConfigFields = [
  {
    field: '商户 ID（PID / 商户号）',
    value: '你的商户编号',
    tip: '登录商户中心，在「开发配置」页面能看到，形如 M17390000123400'
  },
  {
    field: '商户密钥（PKey / KEY / 密钥）',
    value: '你的 API Secret',
    tip: '和商户编号在同一个页面。复制的时候注意别把前后的空格一起带上'
  },
  {
    field: 'API 地址 / 接口地址 / 支付网关',
    value: EPAY_BASE_URL,
    tip: '填到这里就够了。后面的 /submit.php 是对方系统自动拼上去的，不用你填 —— 填多了会拼成 /submit.php/submit.php，一定失败'
  }
]

/** 下单参数（submit.php 和 mapi.php 通用） */
export const epayRequestParams = [
  { name: 'pid', required: true, type: 'String', desc: '商户号，就是你的商户编号，不用另外申请' },
  { name: 'type', required: true, type: 'String', desc: '支付方式：alipay = 支付宝，wxpay = 微信' },
  { name: 'out_trade_no', required: true, type: 'String', desc: '对方系统自己的订单号，同一个商户下不能重复' },
  { name: 'name', required: true, type: 'String', desc: '商品名称，会显示在收款页上' },
  { name: 'money', required: true, type: 'String', desc: '订单金额（元），必须大于 0，例如 10.00' },
  { name: 'notify_url', required: false, type: 'String', desc: '付款成功后平台通知你的地址。不传就用「开发配置」里填的默认地址' },
  { name: 'return_url', required: false, type: 'String', desc: '用户付完款，浏览器跳回的页面地址' },
  { name: 'sign', required: true, type: 'String', desc: '签名，32 位小写字符串，算法见下一节' },
  { name: 'sign_type', required: false, type: 'String', desc: '固定填 MD5。它本身不参与签名计算' }
]

/** 易支付的签名步骤（和原生那套完全不同，见 epaySignDiff） */
export const epaySignSteps = [
  {
    title: '去掉不参与签名的参数',
    desc: '把 sign、sign_type 这两个字段去掉，值为空的参数也一并去掉'
  },
  {
    title: '按参数名排序',
    desc: '剩下的参数按参数名 ASCII 码升序排（简单说就是 a 到 z）'
  },
  {
    title: '拼成一个字符串',
    desc: '拼成 a=1&b=2&c=3 这种形式，注意不做 URL 编码，值原样拼上去'
  },
  {
    title: '末尾直接拼密钥',
    desc: '在拼好的字符串末尾直接接上商户密钥。注意：是直接接上去，不是 &key=密钥'
  },
  {
    title: 'MD5 之后转小写',
    desc: '对整串做 MD5，结果转成小写的 32 位字符串，这就是 sign'
  }
]

/**
 * 一个可以照抄、也可以自己验的下单签名示例
 * 商户编号 M17390000123400、密钥 your-merchant-key
 * 自己验一遍：printf '%s' '……' | md5sum
 */
export const epaySignExample = {
  merchantNo: 'M17390000123400',
  apiSecret: 'your-merchant-key',
  signStr:
    'money=10.00&name=测试商品&notify_url=https://your-site.com/pay/notify&out_trade_no=ORDER20260819001&pid=M17390000123400&return_url=https://your-site.com/pay/result&type=wxpayyour-merchant-key',
  sign: '44175b98b2b0f726a31a31a1414e8116'
}

/** 回调通知的签名示例，密钥同上 */
export const epayNotifySignExample = {
  signStr:
    'money=10.00&name=测试商品&out_trade_no=ORDER20260819001&pid=M17390000123400&trade_no=FP20260819120000123456&trade_status=TRADE_SUCCESS&type=wxpayyour-merchant-key',
  sign: '98eb26dd531b12d5ccb20339145de5ba',
  url:
    'GET https://your-site.com/pay/notify?money=10.00&name=%E6%B5%8B%E8%AF%95%E5%95%86%E5%93%81' +
    '&out_trade_no=ORDER20260819001&pid=M17390000123400&sign=98eb26dd531b12d5ccb20339145de5ba' +
    '&sign_type=MD5&trade_no=FP20260819120000123456&trade_status=TRADE_SUCCESS&type=wxpay'
}

/** 两套签名到底差在哪 —— 混用是接入方最容易踩的坑 */
export const epaySignDiff = [
  {
    item: '密钥怎么拼在后面',
    native: '末尾拼 &key=密钥',
    epay: '末尾直接拼密钥，中间什么都不加'
  },
  {
    item: '算完转成什么',
    native: 'MD5 结果转大写',
    epay: 'MD5 结果转小写'
  },
  {
    item: '哪些参数不参与',
    native: '只去掉 sign',
    epay: '去掉 sign、sign_type，以及所有空值参数'
  },
  {
    item: '用在哪些接口上',
    native: '/api/pay/ 开头的原生接口',
    epay: 'submit.php、mapi.php、api.php'
  }
]

/**
 * mapi.php 的返回示例
 * 注意 payurl 走的是商户前端地址（EPAY_MERCHANT_URL），不是接口地址
 */
export const epayMapiResponse = `{
  "code": 1,
  "msg": "success",
  "trade_no": "FP20260819120000123456",
  "payurl": "${EPAY_MERCHANT_URL}/pay/FP20260819120000123456",
  "qrcode": "weixin://wxpay/bizpayurl?pr=xxxxxxxx"
}`

/**
 * 回调重发的规则。对照后端 PayOrderServiceImpl.processFailedNotify：
 * notifyCount 从 0 起、每发一次 +1，循环条件 notifyCount < 5，
 * 等待时间是 2^notifyCount 分钟。首次发完 count=1 → 等 2 分钟发第 2 次，
 * 之后 4、8、16 分钟各一次，发满 5 次就停。
 * 所以是「一共最多 5 次」而不是「重试 5 次」，重发间隔里也没有 1 分钟这一档。
 */
export const epayNotifyRetry = {
  maxTotalSends: 5,
  retryIntervals: [2, 4, 8, 16],
  text: '平台一共最多发 5 次（第一次 + 4 次重发），重发间隔依次是 2、4、8、16 分钟。5 次都没收到 success 就不再自动发了，需要商户在后台点「重发通知」。'
}

/** 回调通知的参数 */
export const epayNotifyParams = [
  { name: 'pid', type: 'String', desc: '商户编号' },
  { name: 'trade_no', type: 'String', desc: '平台订单号' },
  { name: 'out_trade_no', type: 'String', desc: '你自己系统的订单号，拿它去找对应的那一单' },
  { name: 'type', type: 'String', desc: '支付方式：alipay / wxpay' },
  { name: 'name', type: 'String', desc: '商品名称' },
  {
    name: 'money',
    type: 'String',
    desc: '订单金额（元）。就是你下单时传的 money 原样回传 —— 平台只有在收款金额和订单金额分毫不差的时候才确认这一单，所以这里不会出现「实付比订单多几分少几分」的情况，别拿它当「用户实际付了多少」来用'
  },
  { name: 'trade_status', type: 'String', desc: '交易状态，固定是 TRADE_SUCCESS' },
  { name: 'sign', type: 'String', desc: '签名，32 位小写。务必验签通过之后再发货' },
  { name: 'sign_type', type: 'String', desc: '固定 MD5' }
]

/** 常见问题 */
export const epayFaq = [
  {
    q: '一直提示「签名验证失败」怎么办？',
    a: '十有八九是把本页上面原生接口那套签名算法用到易支付上了。易支付这一套是「末尾直接拼密钥、结果转小写」，原生那一套是「末尾拼 &key=密钥、结果转大写」，两套长得像但完全不通用。先确认你用的是易支付这一套，再检查 API Secret 有没有复制错（前后多带了空格也会算错）。'
  },
  {
    q: '接口地址到底填到哪一层？',
    a: `只填到 ${EPAY_BASE_URL} 为止，后面不要带 /submit.php。对方系统会自己往后拼文件名，你要是填了，它就会拼成 /submit.php/submit.php，怎么试都不通。`
  },
  {
    q: '用户付了钱，订单却还是「未支付」？',
    a: '平台是按金额把收款和订单一笔笔对上的：用户实际付的钱必须和订单金额完全一致，多付一分、少付一分都不会自动确认。另外订单有有效期，超时之后再付也不会自动到账。遇到这两种情况，把订单号发给管理员，在后台手动确认即可。'
  },
  {
    q: '订单付成功了，但我的系统没收到通知？',
    a: '先确认下单时传的 notify_url 是外网能访问的地址（localhost、内网 IP 平台访问不到）。平台发的是 GET 请求不是 POST，别用只收 POST 的路由去接。收到之后必须原样返回字符串 success，返回别的内容平台会当成失败再发一次 —— 一共最多发 5 次（第一次 + 4 次重发），重发间隔依次是 2、4、8、16 分钟，之后就要商户在后台手动点「重发通知」。同一笔可能收到多次，你那边要做到重复收到也不会重复发货。'
  },
  {
    q: '支持退款吗？',
    a: '本平台的易支付接口暂时不支持退款。请在对接方后台（比如 sub2api）把退款开关关掉，用户端就不会出现退款入口。如果对方版本忽略了这个开关照样发退款请求，平台会返回一句明确的「未实现退款」，不会让它当成网络故障反复重试。需要退款请线下和商户协商处理。'
  },
  {
    q: '商户编号和 API Secret 去哪里看？',
    a: '登录商户中心之后，左侧「开发配置」页面就能看到。商户编号形如 M17390000123400，API Secret 是一串随机字符。两套接口共用同一组，不用另外申请。'
  }
]

/** PHP 示例：按易支付规则签名并下单 */
export const epayPhpCode = `<?php
// PHP 示例 —— 易支付协议下单（mapi.php）
$apiUrl    = '${EPAY_BASE_URL}/mapi.php';
$apiSecret = 'YOUR_API_SECRET';   // 商户中心「开发配置」里的 API Secret

$params = [
    'pid'          => 'YOUR_MERCHANT_NO',
    'type'         => 'wxpay',                                // wxpay 或 alipay
    'out_trade_no' => 'ORDER' . time(),
    'name'         => '测试商品',
    'money'        => '10.00',
    'notify_url'   => 'https://your-site.com/pay/notify',
    'return_url'   => 'https://your-site.com/pay/result',
];

// 1) 按参数名 ASCII 升序排序
ksort($params);

// 2) 去掉 sign / sign_type 和空值，拼成 a=1&b=2（不做 URL 编码）
$parts = [];
foreach ($params as $k => $v) {
    if ($k === 'sign' || $k === 'sign_type') continue;
    if ($v === '' || $v === null) continue;
    $parts[] = $k . '=' . $v;
}
$signStr = implode('&', $parts);

// 3) 末尾直接拼密钥（不是原生那套的 key= 后缀）
$signStr .= $apiSecret;

// 4) MD5 之后保持小写，PHP 的 md5() 本来就是小写，别再转大写
$params['sign']      = md5($signStr);
$params['sign_type'] = 'MD5';

// 5) POST 提交
$ch = curl_init();
curl_setopt($ch, CURLOPT_URL, $apiUrl);
curl_setopt($ch, CURLOPT_POST, true);
curl_setopt($ch, CURLOPT_POSTFIELDS, http_build_query($params));
curl_setopt($ch, CURLOPT_RETURNTRANSFER, true);
$result = json_decode(curl_exec($ch), true);
curl_close($ch);

// 注意：易支付协议里 code = 1 才是成功，不是 200
if (isset($result['code']) && $result['code'] == 1) {
    echo '收款链接：' . $result['payurl'];
} else {
    echo '下单失败：' . $result['msg'];
}`

/** Java 示例：按易支付规则签名 */
export const epayJavaCode = `// Java 示例 —— 易支付协议签名
import java.security.MessageDigest;
import java.util.Map;
import java.util.TreeMap;

public class EpayDemo {

    private static final String API_URL    = "${EPAY_BASE_URL}/mapi.php";
    private static final String API_SECRET = "YOUR_API_SECRET";

    /**
     * 易支付签名：排序 -> 去掉 sign/sign_type/空值 -> 末尾直接拼密钥 -> MD5 转小写
     */
    public static String sign(Map<String, String> params) {
        // TreeMap 自动按参数名 ASCII 升序
        TreeMap<String, String> sorted = new TreeMap<>(params);
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> e : sorted.entrySet()) {
            String k = e.getKey();
            String v = e.getValue();
            if ("sign".equals(k) || "sign_type".equals(k)) continue;
            if (v == null || v.isEmpty()) continue;
            if (sb.length() > 0) sb.append('&');
            sb.append(k).append('=').append(v);
        }
        // 直接拼密钥，不是原生那套的 key= 后缀
        sb.append(API_SECRET);
        return md5(sb.toString());
    }

    private static String md5(String str) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] bytes = md.digest(str.getBytes("UTF-8"));
            StringBuilder hex = new StringBuilder();
            for (byte b : bytes) {
                // %02x 出来就是小写，正好符合易支付的要求
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (Exception e) {
            throw new RuntimeException("签名失败", e);
        }
    }

    public static void main(String[] args) {
        TreeMap<String, String> params = new TreeMap<>();
        params.put("pid", "YOUR_MERCHANT_NO");
        params.put("type", "wxpay");
        params.put("out_trade_no", "ORDER" + System.currentTimeMillis());
        params.put("name", "测试商品");
        params.put("money", "10.00");
        params.put("notify_url", "https://your-site.com/pay/notify");

        params.put("sign", sign(params));
        params.put("sign_type", "MD5");

        // 把 params 以表单（application/x-www-form-urlencoded）POST 到 API_URL
        // 返回 JSON 里 code = 1 才是成功
        System.out.println(API_URL + " <- " + params);
    }
}`

/** Python 示例：按易支付规则签名并下单 */
export const epayPythonCode = `# Python 示例 —— 易支付协议下单
import hashlib
import time
import requests

API_URL = '${EPAY_BASE_URL}/mapi.php'
API_SECRET = 'YOUR_API_SECRET'


def epay_sign(data, secret):
    """易支付签名：去掉 sign/sign_type/空值 -> 按参数名升序 -> 末尾直接拼密钥 -> MD5 小写"""
    items = sorted(
        (k, v) for k, v in data.items()
        if k not in ('sign', 'sign_type') and v not in ('', None)
    )
    # 拼成 a=1&b=2，不做 URL 编码
    sign_str = '&'.join(f'{k}={v}' for k, v in items)
    # 直接拼密钥，不是原生那套的 key= 后缀
    sign_str += secret
    # hexdigest() 本来就是小写，别再转大写
    return hashlib.md5(sign_str.encode('utf-8')).hexdigest()


params = {
    'pid': 'YOUR_MERCHANT_NO',
    'type': 'wxpay',                                # wxpay 或 alipay
    'out_trade_no': f'ORDER{int(time.time())}',
    'name': '测试商品',
    'money': '10.00',
    'notify_url': 'https://your-site.com/pay/notify',
    'return_url': 'https://your-site.com/pay/result',
}

params['sign'] = epay_sign(params, API_SECRET)
params['sign_type'] = 'MD5'

resp = requests.post(API_URL, data=params, timeout=10).json()

# 注意：易支付协议里 code = 1 才是成功，不是 200
if resp.get('code') == 1:
    print('收款链接：', resp['payurl'])
else:
    print('下单失败：', resp.get('msg'))`

/** 回调接收示例（PHP），带验签和防重复 */
export const epayNotifyPhpCode = `<?php
// PHP 示例 —— 接收易支付回调（平台发的是 GET）
$apiSecret = 'YOUR_API_SECRET';

$params = $_GET;                      // 平台用 GET 发过来
$sign   = $params['sign'] ?? '';

// 1) 用和下单一样的规则重新算一遍签名
ksort($params);
$parts = [];
foreach ($params as $k => $v) {
    if ($k === 'sign' || $k === 'sign_type') continue;
    if ($v === '' || $v === null) continue;
    $parts[] = $k . '=' . $v;
}
$expect = md5(implode('&', $parts) . $apiSecret);

// 2) 对不上就直接拒绝，绝不能发货
if (!hash_equals($expect, $sign)) {
    exit('fail');
}

// 3) 只认 TRADE_SUCCESS
if (($params['trade_status'] ?? '') !== 'TRADE_SUCCESS') {
    exit('fail');
}

// 4) 同一笔可能收到多次，先查自己库里的状态，已处理过就直接回 success
$order = findOrder($params['out_trade_no']);
if (!$order) {
    exit('fail');
}
if ($order['paid']) {
    exit('success');
}

// 5) 核对金额，再发货
if (bccomp($order['amount'], $params['money'], 2) !== 0) {
    exit('fail');
}
markPaidAndDeliver($order);

// 6) 必须原样输出 success，少一个字母平台都会重试
echo 'success';`
