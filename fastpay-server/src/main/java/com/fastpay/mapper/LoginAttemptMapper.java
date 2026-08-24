package com.fastpay.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.fastpay.entity.LoginAttempt;
import org.apache.ibatis.annotations.Mapper;

/**
 * 登录失败次数记录 Mapper（MTM-162）。
 *
 * @author xiaomo37564459
 */
@Mapper
public interface LoginAttemptMapper extends BaseMapper<LoginAttempt> {
}
