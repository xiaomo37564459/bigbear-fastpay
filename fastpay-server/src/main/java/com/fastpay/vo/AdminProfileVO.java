package com.fastpay.vo;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 当前管理员信息 VO（专门给"账号设置"页面用，不包含密码字段）
 *
 * @author xiaomo37564459
 */
@Data
public class AdminProfileVO {

    /**
     * 管理员ID
     */
    private Long id;

    /**
     * 登录账号
     */
    private String username;

    /**
     * 昵称
     */
    private String nickname;

    /**
     * 头像URL
     */
    private String avatar;

    /**
     * 最后登录时间
     */
    private LocalDateTime lastLoginTime;

    /**
     * 最后登录IP
     */
    private String lastLoginIp;
}
