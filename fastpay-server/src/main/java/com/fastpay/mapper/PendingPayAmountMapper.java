package com.fastpay.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.fastpay.entity.PendingPayAmount;
import org.apache.ibatis.annotations.Mapper;

/**
 * 待支付金额占位表 Mapper
 */
@Mapper
public interface PendingPayAmountMapper extends BaseMapper<PendingPayAmount> {
}
