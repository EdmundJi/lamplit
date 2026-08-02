package com.betterself.growth.auth;

import com.betterself.growth.shared.api.ApiException;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;

@Service
public class JwtService {

    private final SecretKey key;
    private final Duration ttl;
    private final Clock clock;

    public JwtService(
        @Value("${app.security.jwt-secret}") String secret,
        @Value("${app.security.access-token-ttl:PT30M}") Duration ttl,
        Clock clock
    ) {
        if (secret == null || secret.getBytes(StandardCharsets.UTF_8).length < 32) {
            throw new IllegalStateException("JWT secret must be at least 32 bytes");
        }
        this.key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        this.ttl = ttl;
        this.clock = clock;
    }

    public String issue(long userId, String role) {
        Instant now = clock.instant();
        return Jwts.builder()
            .subject(Long.toString(userId))
            .claim("role", role)
            .issuedAt(Date.from(now))
            .expiration(Date.from(now.plus(ttl)))
            .signWith(key)
            .compact();
    }

    public CurrentUser parse(String token) {
        try {
            Claims claims = Jwts.parser().verifyWith(key).build().parseSignedClaims(token).getPayload();
            return new CurrentUser(Long.parseLong(claims.getSubject()), claims.get("role", String.class));
        } catch (RuntimeException exception) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "INVALID_ACCESS_TOKEN", "Authentication is required");
        }
    }
}
