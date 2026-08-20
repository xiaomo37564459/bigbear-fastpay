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
 * 待支付金额占位表（同一商户+同一支付方式下，未过期未支付订单的 pay_amount 必须唯一）
 * 唯一键 (merchant_id, pay_type, pay_amount) 由数据库保证，确保并发下单不会分到同一个 pay_amount。
 * 订单确认支付 / 关闭 / 过期时会把对应行删掉，把该金额释放出来给后来的订单用。
 */
@Data
@TableName("fp_pending_pay_amount")
public class PendingPayAmount implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 商户ID */
    private Long merchantId;

    /** 支付类型：wxpay / alipay */
    private String payType;

    /** 实际应付金额（在订单原始 amount 基础上做微调后落定的值） */
    private BigDecimal payAmount;

    /** 关联的平台订单号，便于排查 */
    private String orderNo;

    /** 关联订单的过期时间，方便定时兜底清理残留 */
    private LocalDateTime expireTime;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;
}
