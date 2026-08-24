package com.fastpay.config;

import com.baomidou.mybatisplus.core.handlers.MetaObjectHandler;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import org.apache.ibatis.reflection.MetaObject;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.LocalDateTime;

/**
 * MyBatis Plus 配置类
 *
 * @author xiaomo37564459
 */
@Configuration
public class MybatisPlusConfig implements MetaObjectHandler {

    /**
     * 分页插件配置
     * PaginationInnerInterceptor 不传 DbType 时，会在运行时按当前连接的 JDBC 连接自动识别数据库类型
     * （MySQL / PostgreSQL 都认得），不用写死成某一种数据库
     */
    @Bean
    public MybatisPlusInterceptor mybatisPlusInterceptor() {
        MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();
        interceptor.addInnerInterceptor(new PaginationInnerInterceptor());
        return interceptor;
    }

    /**
     * 插入时自动填充
     */
    @Override
    public void insertFill(MetaObject metaObject) {
        this.strictInsertFill(metaObject, "createTime", LocalDateTime.class, LocalDateTime.now());
        this.strictInsertFill(metaObject, "updateTime", LocalDateTime.class, LocalDateTime.now());
    }

    /**
     * 更新时自动填充。
     * 这里刻意不用 strictUpdateFill：strict 模式只在字段值为 null 时才填充，而 fetch-then-updateById
     * 这个最常见的写法（先 getById / getOne 拿到实体、改字段、再 updateById）实体上已经带着库里
     * 那条记录的老 update_time，strict 会看成"已经有值"从而不覆盖，结果 SQL 里 update_time 又被
     * 写回成老值，"最后修改时间"就永远停在最初那次的时刻上（MTM-195）。
     * update_time 语义上就该反映"这条记录最后一次被写"的时刻，任何 UPDATE 都应该刷新它，
     * 所以这里直接用 metaObject.setValue 强制覆盖成 now()。
     */
    @Override
    public void updateFill(MetaObject metaObject) {
        if (metaObject.hasSetter("updateTime")) {
            metaObject.setValue("updateTime", LocalDateTime.now());
        }
    }
}
