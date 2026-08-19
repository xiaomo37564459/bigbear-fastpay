package com.fastpay.config;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fastpay.common.BusinessException;
import com.fastpay.common.Constants;
import com.fastpay.entity.Admin;
import com.fastpay.mapper.AdminMapper;
import com.fastpay.util.JwtUtil;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * 认证拦截器
 * 验证请求的 JWT Token
 *
 * @author FastPay
 */
@Slf4j
@Component
public class AuthInterceptor implements HandlerInterceptor {

    private final JwtUtil jwtUtil;
    private final AdminMapper adminMapper;

    public AuthInterceptor(JwtUtil jwtUtil, AdminMapper adminMapper) {
        this.jwtUtil = jwtUtil;
        this.adminMapper = adminMapper;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        // OPTIONS 请求直接放行
        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) {
            return true;
        }

        // 获取 Token
        String authHeader = request.getHeader(Constants.Header.AUTHORIZATION);
        if (authHeader == null || !authHeader.startsWith(Constants.Header.BEARER)) {
            throw BusinessException.unauthorized("请先登录");
        }

        String token = authHeader.substring(Constants.Header.BEARER.length());

        // 验证 Token
        if (!jwtUtil.validateToken(token)) {
            throw BusinessException.unauthorized("登录已过期，请重新登录");
        }

        // 获取用户信息并存入请求属性
        Long userId = jwtUtil.getUserId(token);
        String username = jwtUtil.getUsername(token);
        String userType = jwtUtil.getUserType(token);

        request.setAttribute("userId", userId);
        request.setAttribute("username", username);
        request.setAttribute("userType", userType);

        // 验证访问权限
        String uri = request.getRequestURI();
        if (uri.startsWith("/api/admin/") && !"admin".equals(userType)) {
            throw BusinessException.forbidden("无权访问管理后台");
        }
        if (uri.startsWith("/api/merchant/") && !"merchant".equals(userType)) {
            throw BusinessException.forbidden("无权访问商户平台");
        }

        // 管理员令牌还要额外校验 tokenVersion，改密码/改账号后老 token 会在这里被拒
        if ("admin".equals(userType)) {
            Integer claimVersion = jwtUtil.getTokenVersion(token);
            // 升级前签发的老 token 里没有 tokenVersion 字段，按 0 处理；数据库默认值也是 0，不会误踢
            int currentClaim = claimVersion == null ? 0 : claimVersion;
            Admin admin = adminMapper.selectOne(
                    Wrappers.<Admin>lambdaQuery().select(Admin::getId, Admin::getStatus, Admin::getTokenVersion)
                            .eq(Admin::getId, userId));
            if (admin == null) {
                throw BusinessException.unauthorized("账号不存在或已注销，请重新登录");
            }
            if (!Constants.Status.ENABLED.equals(admin.getStatus())) {
                throw BusinessException.forbidden("账号已被禁用");
            }
            int dbVersion = admin.getTokenVersion() == null ? 0 : admin.getTokenVersion();
            if (dbVersion != currentClaim) {
                throw BusinessException.unauthorized("登录状态已失效，请使用新密码重新登录");
            }
        }

        return true;
    }
}
