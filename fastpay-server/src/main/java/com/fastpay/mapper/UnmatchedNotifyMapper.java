package com.fastpay.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.fastpay.entity.UnmatchedNotify;
import org.apache.ibatis.annotations.Mapper;

/**
 * 未匹配收款通知 Mapper
 */
@Mapper
public interface UnmatchedNotifyMapper extends BaseMapper<UnmatchedNotify> {
}
