package com.ljx.server.admin;

import com.ljx.server.auth.JwtProperties;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.security.Keys;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.UUID;

/**
 * 管理端令牌：**独立于玩家令牌**。
 * <p>
 * 用 {@code typ=admin} 标记，玩家端签发的是 {@code typ=access}/{@code typ=refresh}——
 * 因此**玩家的令牌无法访问 /api/admin/**（解析时校验 typ），这是两套认证之间的关键隔离。
 * 密钥优先取 {@code ljx.admin.jwt-secret}，未配置时回退玩家端 {@code ljx.jwt.secret}。
 */
@Service
public class AdminJwtService {

    private static final String CLAIM_TYPE = "typ";
    public static final String TYPE_ADMIN = "admin";

    private final SecretKey key;
    private final Duration ttl;

    public AdminJwtService(AdminProperties adminProps, JwtProperties jwtProps) {
        String secret = adminProps.getJwtSecret() == null || adminProps.getJwtSecret().isBlank()
                ? jwtProps.secret()
                : adminProps.getJwtSecret();
        this.key = Keys.hmacShaKeyFor(Decoders.BASE64.decode(secret));
        this.ttl = adminProps.getTokenTtl();
    }

    public String issue(AdminUser admin) {
        Instant now = Instant.now();
        return Jwts.builder()
                .id(UUID.randomUUID().toString())
                .subject(String.valueOf(admin.getId()))
                .claim("name", admin.getUsername())
                .claim(CLAIM_TYPE, TYPE_ADMIN)
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(ttl)))
                .signWith(key)
                .compact();
    }

    /** 解析并强制校验 typ=admin；不是管理端令牌一律抛异常 */
    public Claims parseAdmin(String token) {
        Claims claims = Jwts.parser().verifyWith(key).build().parseSignedClaims(token).getPayload();
        if (!TYPE_ADMIN.equals(claims.get(CLAIM_TYPE, String.class))) {
            throw new JwtException("not an admin token");
        }
        return claims;
    }

    public Duration ttl() {
        return ttl;
    }
}
