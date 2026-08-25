package com.fastpay.service;

/**
 * 登录限次服务（MTM-162）。
 * <p>
 * 两把钥匙同时算：账号维度（防止盯住一个账号刷）和 IP 维度（防止一台机器换账号刷）。
 * 任一维度被锁就拒绝登录；成功登录就把两把钥匙同时清零。
 * <p>
 * 状态持久化到数据库表 fp_login_attempt，服务重启后限制状态还在。
 *
 * @author xiaomo37564459
 */
public interface LoginLimitService {

    /** 作用域常量：管理后台。 */
    String SCOPE_ADMIN = "admin";

    /** 作用域常量：商户后台。 */
    String SCOPE_MERCHANT = "merchant";

    /**
     * 登录前调用：如果账号或 IP 任一维度被锁，直接抛 429 BusinessException。
     * 返回值为账号维度当前还剩几次可以尝试；调用方用不上时可以直接忽略。
     *
     * @param scope    作用域，SCOPE_ADMIN 或 SCOPE_MERCHANT
     * @param username 登录账号（未归一化，服务内部自己处理）
     * @param ip       客户端 IP
     * @return 账号维度当前还剩几次可以尝试（含本次），未记录过失败时返回 userMaxAttempts
     */
    int assertNotLocked(String scope, String username, String ip);

    /**
     * 登录失败时调用：把账号和 IP 两条计数都 +1，达到阈值就上锁。
     *
     * @return 账号维度这次失败后还剩几次可以尝试；已锁则返回 0
     */
    int registerFailure(String scope, String username, String ip);

    /**
     * 登录成功时调用：把账号和 IP 两条计数同时清零、解锁。
     */
    void registerSuccess(String scope, String username, String ip);
}
