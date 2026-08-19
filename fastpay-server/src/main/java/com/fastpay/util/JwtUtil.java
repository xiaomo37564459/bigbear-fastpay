package com.fastpay.util;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;

/**
 * JWT 工具类
 * 用于生成和验证 JWT Token
 *
 * @author xiaomo37564459
 */
@Component
public class JwtUtil {

    @Value("${fastpay.jwt.secret}")
    private String secret;

    @Value("${fastpay.jwt.expire-hours}")
    private Integer expireHours;

    /**
     * 获取签名密钥
     */
    private SecretKey getSigningKey() {
        return Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * 生成 Token（不带令牌版本号，用于商户等暂时没有令牌失效需求的场景）
     *
     * @param userId   用户ID
     * @param username 用户名
     * @param userType 用户类型（admin/merchant）
     * @return JWT Token
     */
    public String generateToken(Long userId, String username, String userType) {
        return generateToken(userId, username, userType, null);
    }

    /**
     * 生成 Token（带令牌版本号）。
     * 管理员改密码/改账号后 tokenVersion 会 +1，AuthInterceptor 会拿它跟库里的 token_version 比对，
     * 不一致就直接判 token 失效。传 null 表示这个 token 不参与版本校验（比如商户端）。
     *
     * @param userId       用户ID
     * @param username     用户名
     * @param userType     用户类型（admin/merchant）
     * @param tokenVersion 令牌版本号，可为 null
     * @return JWT Token
     */
    public String generateToken(Long userId, String username, String userType, Integer tokenVersion) {
        Map<String, Object> claims = new HashMap<>();
        claims.put("userId", userId);
        claims.put("username", username);
        claims.put("userType", userType);
        if (tokenVersion != null) {
            claims.put("tokenVersion", tokenVersion);
        }

        Date now = new Date();
        Date expiration = new Date(now.getTime() + expireHours * 60 * 60 * 1000L);

        return Jwts.builder()
                .claims(claims)
                .subject(username)
                .issuedAt(now)
                .expiration(expiration)
                .signWith(getSigningKey())
                .compact();
    }

    /**
     * 解析 Token
     *
     * @param token JWT Token
     * @return Claims 声明信息
     */
    public Claims parseToken(String token) {
        try {
            return Jwts.parser()
                    .verifyWith(getSigningKey())
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 验证 Token 是否有效
     *
     * @param token JWT Token
     * @return 是否有效
     */
    public boolean validateToken(String token) {
        Claims claims = parseToken(token);
        if (claims == null) {
            return false;
        }
        return !claims.getExpiration().before(new Date());
    }

    /**
     * 从 Token 中获取用户ID
     */
    public Long getUserId(String token) {
        Claims claims = parseToken(token);
        if (claims == null) {
            return null;
        }
        return claims.get("userId", Long.class);
    }

    /**
     * 从 Token 中获取用户名
     */
    public String getUsername(String token) {
        Claims claims = parseToken(token);
        if (claims == null) {
            return null;
        }
        return claims.get("username", String.class);
    }

    /**
     * 从 Token 中获取用户类型
     */
    public String getUserType(String token) {
        Claims claims = parseToken(token);
        if (claims == null) {
            return null;
        }
        return claims.get("userType", String.class);
    }

    /**
     * 从 Token 中获取令牌版本号。
     * 老 token（升级前签发的）里没有这个字段，返回 null；
     * 调用方要把 null 当作 0 处理，保证升级过程不会把在线用户强制踢下线。
     */
    public Integer getTokenVersion(String token) {
        Claims claims = parseToken(token);
        if (claims == null) {
            return null;
        }
        return claims.get("tokenVersion", Integer.class);
    }
}
