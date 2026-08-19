package com.fastpay.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 未匹配收款通知记录
 * 收到付款通知但按 (商户 + 支付方式 + pay_amount) 找不到对应的待支付订单时落这一张表，
 * 管理后台可以看到「有一笔钱进来但没认到单」，配合手动确认支付接口人工救回来。
 * 处理状态：0-待处理，1-已人工处理，2-已忽略。
 */
@Data
@TableName("fp_unmatched_notify")
public class UnmatchedNotify implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 收到的收款金额（监听软件解析出来的） */
    private BigDecimal amount;

    /** 支付类型：wxpay / alipay */
    private String payType;

    /** 商户ID（通过 channelId 反查得到） */
    private Long merchantId;

    /** 商户通道ID（原始通知里带的 channelId） */
    private Long channelId;

    /** 原始通知内容（用来事后核对是不是本平台该收的钱） */
    private String rawMessage;

    /** 监听软件收到通知的时间 */
    private LocalDateTime notifyTime;

    /** 处理状态：0-待处理，1-已人工处理，2-已忽略 */
    private Integer handleStatus;

    /** 处理备注（管理员人工处理后留言） */
    private String handleRemark;

    /** 处理时对应到的平台订单号（人工把这笔钱认到哪张单上） */
    private String handledOrderNo;

    /** 处理时间 */
    private LocalDateTime handleTime;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;
}
