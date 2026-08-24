package com.fastpay.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fastpay.common.BusinessException;
import com.fastpay.config.LoginLimitProperties;
import com.fastpay.entity.LoginAttempt;
import com.fastpay.mapper.LoginAttemptMapper;
import com.fastpay.service.LoginLimitService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 登录限次服务实现（MTM-162）。
 * <p>
 * 存储走数据库 fp_login_attempt，服务重启后限制状态还在，不会因为进程重启把攻击者的失败次数清零。
 * <p>
 * 并发考虑：登录本身是低频动作，攻击者一秒最多试几十次；我们用 (scope, identity_key) 的
 * 唯一索引兜底，第一次并发插入时另一方会拿到 DuplicateKeyException，捕获后转成 UPDATE，
 * 没必要引入行锁或 Redis。
 *
 * @author xiaomo37564459
 */
@Slf4j
@Service
public class LoginLimitServiceImpl implements LoginLimitService {

    private static final String IP_KEY_PREFIX = "ip:";

    private final LoginAttemptMapper attemptMapper;
    private final LoginLimitProperties props;

    public LoginLimitServiceImpl(LoginAttemptMapper attemptMapper, LoginLimitProperties props) {
        this.attemptMapper = attemptMapper;
        this.props = props;
    }

    @Override
    public int assertNotLocked(String scope, String username, String ip) {
        LocalDateTime now = LocalDateTime.now();
        String userKey = userKey(username);
        String ipKey = ipKey(ip);

        // 账号维度：先看有没有锁；没有锁再算「本次登录还能尝试几次」返回给前端
        LoginAttempt userAttempt = userKey == null ? null : findAttempt(scope, userKey);
        rejectIfLocked(userAttempt, now, "登录尝试过多，请 %d 分钟后再试");

        if (!isIpExempt(ip)) {
            LoginAttempt ipAttempt = ipKey == null ? null : findAttempt(scope, ipKey);
            rejectIfLocked(ipAttempt, now, "该网络登录尝试过多，请 %d 分钟后再试");
        }

        return remainingUserAttempts(userAttempt, now);
    }

    @Override
    public int registerFailure(String scope, String username, String ip) {
        LocalDateTime now = LocalDateTime.now();
        int remainingUser = props.getUserMaxAttempts();

        String userKey = userKey(username);
        if (userKey != null) {
            LoginAttempt after = bumpFailure(scope, userKey, "user", now, props.getUserMaxAttempts());
            remainingUser = Math.max(0, props.getUserMaxAttempts() - after.getFailCount());
            if (isLocked(after, now)) {
                log.warn("登录限次触发：scope={} user={} 已连续失败 {} 次，锁定至 {}",
                        scope, safeLog(username), after.getFailCount(), after.getLockedUntil());
            }
        }

        String ipKey = ipKey(ip);
        if (ipKey != null && !isIpExempt(ip)) {
            LoginAttempt after = bumpFailure(scope, ipKey, "ip", now, props.getIpMaxAttempts());
            if (isLocked(after, now)) {
                log.warn("登录限次触发：scope={} ip={} 已连续失败 {} 次，锁定至 {}",
                        scope, ip, after.getFailCount(), after.getLockedUntil());
            }
        }

        return remainingUser;
    }

    @Override
    public void registerSuccess(String scope, String username, String ip) {
        String userKey = userKey(username);
        if (userKey != null) {
            clearAttempt(scope, userKey);
        }
        String ipKey = ipKey(ip);
        if (ipKey != null) {
            clearAttempt(scope, ipKey);
        }
    }

    // ================= 内部实现 =================

    private LoginAttempt findAttempt(String scope, String identityKey) {
        return attemptMapper.selectOne(new LambdaQueryWrapper<LoginAttempt>()
                .eq(LoginAttempt::getScope, scope)
                .eq(LoginAttempt::getIdentityKey, identityKey)
                .last("LIMIT 1"));
    }

    private void rejectIfLocked(LoginAttempt attempt, LocalDateTime now, String messageTemplate) {
        if (!isLocked(attempt, now)) {
            return;
        }
        long minutes = Math.max(1, Duration.between(now, attempt.getLockedUntil()).toMinutes() + 1);
        throw new BusinessException(429, String.format(messageTemplate, minutes));
    }

    private boolean isLocked(LoginAttempt attempt, LocalDateTime now) {
        return attempt != null && attempt.getLockedUntil() != null && attempt.getLockedUntil().isAfter(now);
    }

    private int remainingUserAttempts(LoginAttempt attempt, LocalDateTime now) {
        if (attempt == null || attempt.getFailCount() == null || attempt.getFailCount() == 0) {
            return props.getUserMaxAttempts();
        }
        // 窗口过期就等同重置（下一次失败会走 reset 分支归 1）
        if (isWindowExpired(attempt, now)) {
            return props.getUserMaxAttempts();
        }
        return Math.max(0, props.getUserMaxAttempts() - attempt.getFailCount());
    }

    private boolean isWindowExpired(LoginAttempt attempt, LocalDateTime now) {
        if (attempt.getFirstFailedAt() == null) {
            return true;
        }
        return Duration.between(attempt.getFirstFailedAt(), now).toMinutes() >= props.getWindowMinutes();
    }

    /**
     * 记一次失败：查表 → 写回。表里没记录先 INSERT，遇到唯一约束冲突（并发）时改走 UPDATE。
     * 返回写回后的最新实体，调用方据此判剩余次数、是否新触发锁定。
     */
    private LoginAttempt bumpFailure(String scope, String identityKey, String keyType,
                                      LocalDateTime now, int maxAttempts) {
        LoginAttempt existing = findAttempt(scope, identityKey);
        if (existing == null) {
            LoginAttempt fresh = newAttemptOnFirstFailure(scope, identityKey, keyType, now, maxAttempts);
            try {
                attemptMapper.insert(fresh);
                return fresh;
            } catch (DuplicateKeyException conflict) {
                // 极小概率：另一路并发抢先插了；退回 UPDATE 路径
                existing = findAttempt(scope, identityKey);
                if (existing == null) {
                    throw conflict;
                }
            }
        }
        applyFailureOnExisting(existing, now, maxAttempts);
        attemptMapper.updateById(existing);
        return existing;
    }

    private LoginAttempt newAttemptOnFirstFailure(String scope, String identityKey, String keyType,
                                                    LocalDateTime now, int maxAttempts) {
        LoginAttempt fresh = new LoginAttempt();
        fresh.setScope(scope);
        fresh.setIdentityKey(identityKey);
        fresh.setKeyType(keyType);
        fresh.setFailCount(1);
        fresh.setFirstFailedAt(now);
        fresh.setLastFailedAt(now);
        if (maxAttempts <= 1) {
            fresh.setLockedUntil(now.plusMinutes(props.getLockMinutes()));
        }
        return fresh;
    }

    private void applyFailureOnExisting(LoginAttempt existing, LocalDateTime now, int maxAttempts) {
        // 已在锁定中的再失败一次：只刷新 last_failed_at，count / locked_until 不动，避免锁定被无限往后叠加
        if (isLocked(existing, now)) {
            existing.setLastFailedAt(now);
            return;
        }
        int nextCount;
        if (isWindowExpired(existing, now)) {
            nextCount = 1;
            existing.setFirstFailedAt(now);
        } else {
            nextCount = (existing.getFailCount() == null ? 0 : existing.getFailCount()) + 1;
        }
        existing.setFailCount(nextCount);
        existing.setLastFailedAt(now);
        if (nextCount >= maxAttempts) {
            existing.setLockedUntil(now.plusMinutes(props.getLockMinutes()));
        } else {
            existing.setLockedUntil(null);
        }
    }

    private void clearAttempt(String scope, String identityKey) {
        LoginAttempt existing = findAttempt(scope, identityKey);
        if (existing == null || (existing.getFailCount() != null && existing.getFailCount() == 0
                && existing.getLockedUntil() == null)) {
            return;
        }
        existing.setFailCount(0);
        existing.setFirstFailedAt(null);
        existing.setLastFailedAt(null);
        existing.setLockedUntil(null);
        attemptMapper.updateById(existing);
    }

    // ---------- 小工具 ----------

    /** 账号归一化：null / 空返回 null（登录服务会先拒绝空账号），否则 trim + toLowerCase 保证大小写不敏感。 */
    private String userKey(String username) {
        if (username == null) {
            return null;
        }
        String trimmed = username.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        return trimmed.toLowerCase();
    }

    private String ipKey(String ip) {
        if (ip == null) {
            return null;
        }
        String trimmed = ip.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        return IP_KEY_PREFIX + trimmed;
    }

    private boolean isIpExempt(String ip) {
        if (ip == null || ip.isEmpty()) {
            return false;
        }
        List<String> list = props.getIpWhitelist();
        if (list == null || list.isEmpty()) {
            return false;
        }
        Set<String> set = new HashSet<>();
        for (String item : list) {
            if (item != null) {
                String t = item.trim();
                if (!t.isEmpty()) {
                    set.add(t);
                }
            }
        }
        return set.contains(ip.trim());
    }

    /** 日志里不完整打印账号，只保留前 2 位 + *，避免刷试探日志把候选账号名整份印出来。 */
    private String safeLog(String username) {
        if (username == null || username.isEmpty()) {
            return "<empty>";
        }
        String u = username.trim();
        if (u.length() <= 2) {
            return u.charAt(0) + "*";
        }
        return u.substring(0, 2) + "***";
    }
}
