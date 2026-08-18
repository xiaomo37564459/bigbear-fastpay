package com.fastpay.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.fastpay.entity.PayOrder;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * 支付订单 Mapper 接口
 *
 * 统计 SQL 全部改写成 MySQL / PostgreSQL 都认的写法（CAST(... AS DATE)、CURRENT_DATE、COALESCE），
 * 不再用 MySQL 专用的 CURDATE()/IFNULL()/DATE_SUB()，两个数据库共用同一套 SQL。
 *
 * @author FastPay
 */
@Mapper
public interface PayOrderMapper extends BaseMapper<PayOrder> {

    /**
     * 统计今日订单数量
     */
    @Select("SELECT COUNT(*) FROM fp_pay_order WHERE CAST(create_time AS DATE) = CURRENT_DATE")
    Integer countTodayOrders();

    /**
     * 统计今日成功订单金额
     */
    @Select("SELECT COALESCE(SUM(pay_amount), 0) FROM fp_pay_order WHERE CAST(create_time AS DATE) = CURRENT_DATE AND status = 1")
    BigDecimal sumTodayAmount();

    /**
     * 统计今日成功订单数量
     */
    @Select("SELECT COUNT(*) FROM fp_pay_order WHERE CAST(create_time AS DATE) = CURRENT_DATE AND status = 1")
    Integer countTodaySuccessOrders();

    /**
     * 按商户统计订单
     */
    @Select("SELECT merchant_id, COUNT(*) as order_count, COALESCE(SUM(pay_amount), 0) as total_amount " +
            "FROM fp_pay_order WHERE status = 1 GROUP BY merchant_id")
    List<Map<String, Object>> statsByMerchant();

    /**
     * 按日期统计最近N天的订单。
     * DATE_SUB(...INTERVAL...DAY) 是 MySQL 专用语法，PG 没有；改成在 Java 里把起始日期算好再传进 SQL，
     * 避免两个数据库各写一套日期函数。
     */
    default List<Map<String, Object>> statsByDate(Integer days) {
        return statsByDateFrom(LocalDate.now().minusDays(days));
    }

    /**
     * 别名用 stat_date 而不是 date —— date 在 PostgreSQL 里是类型关键字，当列别名容易踩坑。
     * 对外接口返回字段名仍叫 date，由 AdminServiceImpl 在拿到结果后做一次字段名映射，前端不用改。
     */
    @Select("SELECT CAST(create_time AS DATE) as stat_date, COUNT(*) as order_count, " +
            "SUM(CASE WHEN status = 1 THEN 1 ELSE 0 END) as success_count, " +
            "COALESCE(SUM(CASE WHEN status = 1 THEN pay_amount ELSE 0 END), 0) as total_amount " +
            "FROM fp_pay_order WHERE create_time >= #{fromDate} " +
            "GROUP BY CAST(create_time AS DATE) ORDER BY stat_date DESC")
    List<Map<String, Object>> statsByDateFrom(@Param("fromDate") LocalDate fromDate);
}
