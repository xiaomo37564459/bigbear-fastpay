package com.fastpay.config;

import com.fastpay.service.AdminService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;

/**
 * 系统初始化配置
 * 在应用启动时执行初始化操作
 *
 * @author xiaomo37564459
 */
@Slf4j
@Component
public class InitConfig implements CommandLineRunner {

    private final AdminService adminService;
    private final DataSource dataSource;

    public InitConfig(AdminService adminService, DataSource dataSource) {
        this.adminService = adminService;
        this.dataSource = dataSource;
    }

    @Override
    public void run(String... args) {
        // 先检查数据库表结构是不是最新的 —— 缺列就直接让 Spring Boot 起不来。
        // 这条不能吞异常：宁可服务起不来（运维当场就知道），也不能起来了但一登录就 500（要等用户投诉）。
        ensureAdminSchemaUpgraded();

        // 初始化默认管理员账号（首次启动才建；已存在就跳过）
        try {
            adminService.initDefaultAdmin();
        } catch (Exception e) {
            log.warn("初始化管理员账号失败（可能数据库未就绪）: {}", e.getMessage());
        }
    }

    /**
     * 检查 fp_admin 表是不是升过 V1.1（含 token_version 列）。
     * 缺就抛异常让 Spring Boot 直接退出，日志里说清楚要先跑哪个迁移脚本。
     *
     * 这个方法解决的具体线上事故：老库直接部署新版本 jar，后端能启动、日志无异常，
     * 但只要有人登录，AdminMapper 那句 SELECT ... token_version FROM fp_admin
     * 就会因为字段不存在返回 500，谁都进不去（包括进去修的人）。
     */
    private void ensureAdminSchemaUpgraded() {
        try (Connection conn = dataSource.getConnection()) {
            if (adminTableHasTokenVersion(conn)) {
                return;
            }
            throw new IllegalStateException(
                    "fp_admin 表缺少 token_version 列。这个版本的后端要求先跑数据库升级脚本再启动：\n" +
                    "  · PostgreSQL：db/migration/V1_1__admin_account_management_pg.sql\n" +
                    "  · MySQL：    db/migration/V1_1__admin_account_management_mysql.sql\n" +
                    "跑完再重启后端。详见 docs/DEPLOY.md 第三节 \"部署 / 更新版本\"。"
            );
        } catch (SQLException e) {
            // 数据库连不上是另一类问题（DB_URL 配错、库还没起来等），交给上层日志暴露
            throw new IllegalStateException("启动前检查数据库结构失败：" + e.getMessage(), e);
        }
    }

    private boolean adminTableHasTokenVersion(Connection conn) throws SQLException {
        DatabaseMetaData md = conn.getMetaData();
        // 不同数据库对表/列名大小写敏感度不同：PostgreSQL 默认小写、MySQL 看操作系统。
        // 两种都试一下，任何一个匹配到就算通过。
        for (String table : new String[]{"fp_admin", "FP_ADMIN"}) {
            for (String column : new String[]{"token_version", "TOKEN_VERSION"}) {
                try (ResultSet rs = md.getColumns(conn.getCatalog(), null, table, column)) {
                    if (rs.next()) {
                        return true;
                    }
                }
            }
        }
        return false;
    }
}
