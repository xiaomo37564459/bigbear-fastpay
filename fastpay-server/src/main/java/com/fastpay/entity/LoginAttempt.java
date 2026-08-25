package com.fastpay.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 登录失败次数记录（MTM-162 登录限次的持久层实体）。
 * <p>
 * 一条记录就是一把「钥匙」目前的失败累计和锁定状态：
 * <ul>
 *   <li>scope=admin/merchant，区分管理后台和商户后台两条独立的计数</li>
 *   <li>keyType=user 时 identityKey 为归一化后的账号（trim + toLowerCase）</li>
 *   <li>keyType=ip 时 identityKey 为 "ip:" + 客户端 IP</li>
 * </ul>
 * 命中 (scope, identityKey) 的唯一约束保证一把钥匙只有一行。
 *
 * @author xiaomo37564459
 */
@Data
@TableName("fp_login_attempt")
public class LoginAttempt implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 作用域：admin / merchant */
    private String scope;

    /** 钥匙标识：账号名（归一化）或 "ip:" + IP */
    private String identityKey;

    /** 钥匙类型：user / ip */
    private String keyType;

    /** 当前失败累计次数（成功登录后清零） */
    private Integer failCount;

    /** 本次统计窗口的第一次失败时间（用于滑动窗口重置判断） */
    private LocalDateTime firstFailedAt;

    /** 最近一次失败时间 */
    private LocalDateTime lastFailedAt;

    /** 锁定截止时间；为 null 或 <= now 时视为未锁 */
    private LocalDateTime lockedUntil;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;
}
