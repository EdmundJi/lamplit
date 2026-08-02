package com.betterself.growth.auth;

import com.betterself.growth.shared.api.ApiException;
import com.betterself.growth.shared.id.PublicIdGenerator;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;

@Service
public class DatabaseSessionService implements SessionService {

    private final JdbcTemplate jdbc;
    private final JwtService jwtService;
    private final PublicIdGenerator idGenerator;
    private final Clock clock;
    private final Duration refreshTtl;
    private final SecureRandom random = new SecureRandom();

    public DatabaseSessionService(
        JdbcTemplate jdbc,
        JwtService jwtService,
        PublicIdGenerator idGenerator,
        Clock clock,
        @Value("${app.security.refresh-token-ttl:P7D}") Duration refreshTtl
    ) {
        this.jdbc = jdbc;
        this.jwtService = jwtService;
        this.idGenerator = idGenerator;
        this.clock = clock;
        this.refreshTtl = refreshTtl;
    }

    @Override
    @Transactional
    public IssuedSession issue(long userId, String deviceLabel, HttpServletRequest request) {
        String familyId = idGenerator.next();
        return insertSession(userId, familyId, deviceLabel, request);
    }

    @Override
    @Transactional(noRollbackFor = ApiException.class)
    public IssuedSession rotate(String rawRefreshToken, HttpServletRequest request) {
        if (rawRefreshToken == null || rawRefreshToken.isBlank()) {
            throw unauthorized("REFRESH_TOKEN_REQUIRED");
        }
        SessionRow row = jdbc.query(
            """
                select id, user_id, token_family_id, device_label, expires_at, rotated_at, revoked_at
                from auth_session where refresh_token_hash = ? for update
                """,
            resultSet -> resultSet.next() ? new SessionRow(
                resultSet.getLong("id"),
                resultSet.getLong("user_id"),
                resultSet.getString("token_family_id"),
                resultSet.getString("device_label"),
                resultSet.getTimestamp("expires_at").toInstant(),
                resultSet.getTimestamp("rotated_at"),
                resultSet.getTimestamp("revoked_at")
            ) : null,
            hash(rawRefreshToken)
        );
        if (row == null) {
            throw unauthorized("INVALID_REFRESH_TOKEN");
        }
        if (row.rotatedAt() != null || row.revokedAt() != null) {
            revokeFamily(row.familyId(), "REPLAY_DETECTED");
            throw unauthorized("REFRESH_REPLAY_DETECTED");
        }
        if (!row.expiresAt().isAfter(clock.instant())) {
            revokeFamily(row.familyId(), "EXPIRED");
            throw unauthorized("REFRESH_TOKEN_EXPIRED");
        }
        jdbc.update("update auth_session set rotated_at = ? where id = ?", timestamp(clock.instant()), row.id());
        return insertSession(row.userId(), row.familyId(), row.deviceLabel(), request);
    }

    @Override
    @Transactional
    public void revokeCurrent(long userId, String rawRefreshToken) {
        if (rawRefreshToken != null) {
            jdbc.update(
                "update auth_session set revoked_at = ?, revoke_reason = 'LOGOUT' where user_id = ? and refresh_token_hash = ? and revoked_at is null",
                timestamp(clock.instant()),
                userId,
                hash(rawRefreshToken)
            );
        }
    }

    @Override
    @Transactional
    public void revokeAll(long userId) {
        jdbc.update(
            "update auth_session set revoked_at = ?, revoke_reason = 'LOGOUT_ALL' where user_id = ? and revoked_at is null",
            timestamp(clock.instant()),
            userId
        );
    }

    private IssuedSession insertSession(
        long userId,
        String familyId,
        String deviceLabel,
        HttpServletRequest request
    ) {
        String rawRefresh = randomToken(32);
        Instant now = clock.instant();
        String role = jdbc.queryForObject("select role from sys_user where id = ?", String.class, userId);
        jdbc.update(
            """
                insert into auth_session (
                    public_id, user_id, token_family_id, refresh_token_hash, device_label,
                    ip_hash, user_agent_hash, issued_at, expires_at
                ) values (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
            idGenerator.next(),
            userId,
            familyId,
            hash(rawRefresh),
            limit(deviceLabel, 120),
            hashNullable(request.getRemoteAddr()),
            hashNullable(request.getHeader("User-Agent")),
            timestamp(now),
            timestamp(now.plus(refreshTtl))
        );
        return new IssuedSession(userId, jwtService.issue(userId, role), rawRefresh, randomToken(24));
    }

    private void revokeFamily(String familyId, String reason) {
        jdbc.update(
            "update auth_session set revoked_at = coalesce(revoked_at, ?), revoke_reason = ? where token_family_id = ?",
            timestamp(clock.instant()),
            reason,
            familyId
        );
    }

    private ApiException unauthorized(String code) {
        return new ApiException(HttpStatus.UNAUTHORIZED, code, "Authentication is required");
    }

    private byte[] hash(String value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private byte[] hashNullable(String value) {
        return value == null ? null : hash(value);
    }

    private String randomToken(int bytes) {
        byte[] value = new byte[bytes];
        random.nextBytes(value);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value);
    }

    private String limit(String value, int length) {
        if (value == null || value.length() <= length) {
            return value;
        }
        return value.substring(0, length);
    }

    private Timestamp timestamp(Instant value) {
        return Timestamp.from(value);
    }

    private record SessionRow(
        long id,
        long userId,
        String familyId,
        String deviceLabel,
        Instant expiresAt,
        Timestamp rotatedAt,
        Timestamp revokedAt
    ) {
    }
}
