package com.betterself.growth.execution;

import com.betterself.growth.shared.api.ApiException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.util.HexFormat;

@Service
public class IdempotencyService {

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public IdempotencyService(JdbcTemplate jdbc, ObjectMapper objectMapper, Clock clock) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    public BeginResult begin(long userId, String operation, String key, Object request) {
        if (key == null || key.isBlank() || key.length() > 120) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "IDEMPOTENCY_KEY_REQUIRED", "A valid Idempotency-Key is required");
        }
        byte[] requestHash = hash(request);
        int inserted = jdbc.update(
            "insert ignore into idempotency_record (user_id, operation, idempotency_key, request_hash, expires_at) values (?, ?, ?, ?, ?)",
            userId, operation, key, requestHash, Timestamp.from(clock.instant().plus(Duration.ofHours(24)))
        );
        if (inserted == 1) {
            return new BeginResult(false, null);
        }
        Stored stored = jdbc.query(
            "select request_hash, response_body from idempotency_record where user_id = ? and operation = ? and idempotency_key = ? for update",
            rs -> rs.next() ? new Stored(rs.getBytes(1), rs.getString(2)) : null,
            userId, operation, key
        );
        if (stored == null || !MessageDigest.isEqual(requestHash, stored.requestHash())) {
            throw new ApiException(HttpStatus.CONFLICT, "IDEMPOTENCY_KEY_REUSED", "Idempotency key was used with different content");
        }
        if (stored.responseBody() == null) {
            throw new ApiException(HttpStatus.CONFLICT, "IDEMPOTENCY_REQUEST_IN_PROGRESS", "The original request is still in progress");
        }
        return new BeginResult(true, stored.responseBody());
    }

    public void complete(long userId, String operation, String key, Object response, String resourcePublicId) {
        try {
            jdbc.update(
                "update idempotency_record set response_status = 200, response_body = cast(? as json), resource_public_id = ? where user_id = ? and operation = ? and idempotency_key = ?",
                objectMapper.writeValueAsString(response), resourcePublicId, userId, operation, key
            );
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Unable to serialize idempotent response", exception);
        }
    }

    public <T> T replay(BeginResult begin, Class<T> type) {
        try {
            return objectMapper.readValue(begin.responseBody(), type);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Stored idempotent response is invalid", exception);
        }
    }

    private byte[] hash(Object request) {
        try {
            byte[] canonical = objectMapper.writer()
                .with(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
                .writeValueAsBytes(request);
            return MessageDigest.getInstance("SHA-256").digest(canonical);
        } catch (JsonProcessingException | NoSuchAlgorithmException exception) {
            throw new IllegalStateException("Unable to hash idempotent request", exception);
        }
    }

    public String requestFingerprint(Object request) {
        return HexFormat.of().formatHex(hash(request));
    }

    public record BeginResult(boolean replay, String responseBody) {
    }

    private record Stored(byte[] requestHash, String responseBody) {
    }
}
