package com.fastpay.vo;

import lombok.Data;

/**
 * 改账号 / 改密码成功后的返回结果。
 * 之所以要返回新 token：改动会让当前 JWT 令牌版本作废，
 * 前端本地保存的老 token 后续请求都会 401。为了不打断"改完立刻还能继续用"的操作，
 * 后端顺手签一个和新令牌版本对齐的 token 交给前端替换掉本地缓存。
 * 前端若判断这是"改密码"场景，也可以主动清掉 token 并跳登录页（本次交付的做法）。
 *
 * @author xiaomo37564459
 */
@Data
public class UpdateCredentialVO {

    /**
     * 更新后的登录账号（用于前端刷新展示）
     */
    private String username;

    /**
     * 全新的 JWT Token（老 token 已失效）
     */
    private String token;
}
