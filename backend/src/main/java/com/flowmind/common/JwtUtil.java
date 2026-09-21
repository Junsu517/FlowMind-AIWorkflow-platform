package com.flowmind.common;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;

/**
 * JWT HS256，payload 含 userId、role，默认有效期 7 天。
 */
@Component
public class JwtUtil {

    @Value("${flowmind.jwt.secret}")
    private String secret;

    @Value("${flowmind.jwt.expire-days:7}")
    private int expireDays;

    private SecretKey key;

    @PostConstruct
    public void init() {
        this.key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
    }

    public String generate(Long userId, String role) {
        Date now = new Date();
        Date expire = new Date(now.getTime() + expireDays * 24L * 3600_000L);
        return Jwts.builder()
                .subject(String.valueOf(userId))
                .claim("userId", userId)
                .claim("role", role)
                .issuedAt(now)
                .expiration(expire)
                .signWith(key, Jwts.SIG.HS256)
                .compact();
    }

    /**
     * 解析并验签，token 无效或已过期时抛 io.jsonwebtoken.JwtException。
     */
    public Claims parse(String token) {
        return Jwts.parser().verifyWith(key).build()
                .parseSignedClaims(token).getPayload();
    }
}
