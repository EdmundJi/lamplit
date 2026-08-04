package com.betterself.growth.auth;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.betterself.growth.career.RoleProgressionService;
import com.betterself.growth.shared.api.ApiException;
import com.betterself.growth.shared.id.PublicIdGenerator;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.sql.Date;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Base64;
import java.util.Locale;

@Service
public class AuthService {

    public static final String TERMS_VERSION = "2026-07";
    public static final String PRIVACY_VERSION = "2026-07";
    public static final String AI_VERSION = "2026-07";

    private final JdbcTemplate jdbc;
    private final PasswordEncoder passwordEncoder;
    private final PublicIdGenerator idGenerator;
    private final Clock clock;
    private final SessionService sessionService;
    private final MfaService mfaService;
    private final MfaSecretCipher mfaSecretCipher;
    private final RoleProgressionService roleProgression;
    private final Duration passwordResetTtl;
    private final SecureRandom random = new SecureRandom();

    public AuthService(
        JdbcTemplate jdbc,
        PasswordEncoder passwordEncoder,
        PublicIdGenerator idGenerator,
        Clock clock,
        SessionService sessionService,
        MfaService mfaService,
        MfaSecretCipher mfaSecretCipher,
        RoleProgressionService roleProgression,
        @Value("${app.security.password-reset-token-ttl:PT30M}") Duration passwordResetTtl
    ) {
        this.jdbc = jdbc;
        this.passwordEncoder = passwordEncoder;
        this.idGenerator = idGenerator;
        this.clock = clock;
        this.sessionService = sessionService;
        this.mfaService = mfaService;
        this.mfaSecretCipher = mfaSecretCipher;
        this.roleProgression = roleProgression;
        this.passwordResetTtl = passwordResetTtl;
    }

    @Transactional
    public UserView register(RegisterCommand command) {
        ZoneId timezone = parseTimezone(command.timezone());
        requireConsent(command.consents());
        if (command.password() == null || command.password().length() < 12) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "WEAK_PASSWORD", "Password must contain at least 12 characters");
        }
        String normalizedEmail = normalizeEmail(command.email());
        KeyHolder keys = new GeneratedKeyHolder();
        try {
            jdbc.update(connection -> {
                PreparedStatement statement = connection.prepareStatement(
                    """
                        insert into sys_user (
                            public_id, email, email_normalized, password_hash, display_name,
                            birth_date, timezone, status, role
                        ) values (?, ?, ?, ?, ?, ?, ?, 'ACTIVE', 'USER')
                        """,
                    Statement.RETURN_GENERATED_KEYS
                );
                statement.setString(1, idGenerator.next());
                statement.setString(2, command.email().trim());
                statement.setString(3, normalizedEmail);
                statement.setString(4, passwordEncoder.encode(command.password()));
                statement.setString(5, command.displayName().trim());
                statement.setDate(6, Date.valueOf(command.birthDate()));
                statement.setString(7, timezone.getId());
                return statement;
            }, keys);
        } catch (DuplicateKeyException exception) {
            throw new ApiException(HttpStatus.CONFLICT, "EMAIL_ALREADY_REGISTERED", "Email is already registered");
        }
        long userId = keys.getKey().longValue();
        insertConsent(userId, "TERMS", command.consents().terms());
        insertConsent(userId, "PRIVACY", command.consents().privacy());
        insertConsent(userId, "AI", command.consents().ai());
        jdbc.update(
            "insert into user_preference (user_id, timezone) values (?, ?)",
            userId,
            timezone.getId()
        );
        jdbc.update(
            """
                insert into notification_preference (user_id, channel, enabled, max_per_day)
                values (?, 'IN_APP', 1, 2), (?, 'EMAIL', 0, 2), (?, 'WEB_PUSH', 0, 2)
                """,
            userId, userId, userId
        );
        jdbc.update(
            """
                insert into user_dimension (user_id, dimension_id)
                select ?, id from growth_dimension where is_system = 1
                """,
            userId
        );
        roleProgression.initialize(userId);
        return findUser(userId);
    }

    public UserView authenticate(String email, String password) {
        UserCredentials user = jdbc.query(
            "select id, public_id, email, display_name, timezone, role, status, password_hash from sys_user where email_normalized = ?",
            resultSet -> resultSet.next() ? new UserCredentials(
                resultSet.getLong("id"),
                resultSet.getString("public_id"),
                resultSet.getString("email"),
                resultSet.getString("display_name"),
                resultSet.getString("timezone"),
                resultSet.getString("role"),
                resultSet.getString("status"),
                resultSet.getString("password_hash")
            ) : null,
            normalizeEmail(email)
        );
        if (user == null || !passwordEncoder.matches(password, user.passwordHash()) || !"ACTIVE".equals(user.status())) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "INVALID_CREDENTIALS", "Email or password is incorrect");
        }
        return new UserView(user.id(), user.publicId(), user.email(), user.displayName(), user.timezone(), user.role());
    }

    public UserView verifyAdminMfa(String email, String password, String code) {
        UserView user = authenticate(email, password);
        if ("USER".equals(user.role())) {
            throw new ApiException(HttpStatus.FORBIDDEN, "ADMIN_MFA_REQUIRED", "Administrator MFA is required");
        }
        byte[] encryptedSecret = jdbc.queryForObject(
            "select mfa_secret_encrypted from sys_user where id = ?",
            byte[].class,
            user.id()
        );
        if (encryptedSecret == null || !mfaService.verify(mfaSecretCipher.decrypt(encryptedSecret), code)) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "INVALID_MFA_CODE", "The MFA code is invalid");
        }
        jdbc.update("update sys_user set mfa_verified_at = ? where id = ?", Timestamp.from(clock.instant()), user.id());
        return user;
    }

    @Transactional
    public void requestPasswordReset(String email) {
        Long userId = jdbc.query(
            "select id from sys_user where email_normalized = ? and status = 'ACTIVE'",
            resultSet -> resultSet.next() ? resultSet.getLong(1) : null,
            normalizeEmail(email)
        );
        if (userId == null) {
            return;
        }
        String rawToken = randomToken();
        jdbc.update(
            "insert into password_reset_token (user_id, token_hash, requested_at, expires_at) values (?, ?, ?, ?)",
            userId,
            hash(rawToken),
            Timestamp.from(clock.instant()),
            Timestamp.from(clock.instant().plus(passwordResetTtl))
        );
        // A configured mail adapter receives the raw token in memory; durable storage keeps only the hash.
    }

    @Transactional
    public void resetPassword(String rawToken, String newPassword) {
        requireStrongPassword(newPassword);
        ResetRow reset = jdbc.query(
            """
                select id, user_id, expires_at, consumed_at, revoked_at
                from password_reset_token where token_hash = ? for update
                """,
            resultSet -> resultSet.next() ? new ResetRow(
                resultSet.getLong("id"),
                resultSet.getLong("user_id"),
                resultSet.getTimestamp("expires_at"),
                resultSet.getTimestamp("consumed_at"),
                resultSet.getTimestamp("revoked_at")
            ) : null,
            hash(rawToken)
        );
        if (reset == null || reset.consumedAt() != null || reset.revokedAt() != null
            || !reset.expiresAt().toInstant().isAfter(clock.instant())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_RESET_TOKEN", "The reset token is invalid or expired");
        }
        jdbc.update(
            "update sys_user set password_hash = ?, updated_at = ? where id = ?",
            passwordEncoder.encode(newPassword),
            Timestamp.from(clock.instant()),
            reset.userId()
        );
        jdbc.update("update password_reset_token set consumed_at = ? where id = ?",
            Timestamp.from(clock.instant()), reset.id());
        jdbc.update("update password_reset_token set revoked_at = ? where user_id = ? and id <> ? and revoked_at is null",
            Timestamp.from(clock.instant()), reset.userId(), reset.id());
        sessionService.revokeAll(reset.userId());
    }

    @Transactional
    public void updatePassword(long userId, String currentPassword, String newPassword) {
        requireStrongPassword(newPassword);
        String currentHash = jdbc.queryForObject("select password_hash from sys_user where id = ?", String.class, userId);
        if (!passwordEncoder.matches(currentPassword, currentHash)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "CURRENT_PASSWORD_INCORRECT", "Current password is incorrect");
        }
        jdbc.update(
            "update sys_user set password_hash = ?, updated_at = ? where id = ?",
            passwordEncoder.encode(newPassword),
            Timestamp.from(clock.instant()),
            userId
        );
        sessionService.revokeAll(userId);
    }

    public UserView findUser(long userId) {
        return jdbc.queryForObject(
            "select id, public_id, email, display_name, timezone, role from sys_user where id = ?",
            (resultSet, rowNumber) -> new UserView(
                resultSet.getLong("id"),
                resultSet.getString("public_id"),
                resultSet.getString("email"),
                resultSet.getString("display_name"),
                resultSet.getString("timezone"),
                resultSet.getString("role")
            ),
            userId
        );
    }

    private void insertConsent(long userId, String type, String version) {
        jdbc.update(
            "insert into consent_record (user_id, consent_type, version, granted, recorded_at) values (?, ?, ?, 1, ?)",
            userId,
            type,
            version,
            Timestamp.from(clock.instant())
        );
    }

    private void requireConsent(ConsentVersions consents) {
        if (consents == null
            || !TERMS_VERSION.equals(consents.terms())
            || !PRIVACY_VERSION.equals(consents.privacy())
            || !AI_VERSION.equals(consents.ai())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "CURRENT_CONSENTS_REQUIRED", "Current consent versions are required");
        }
    }

    private void requireStrongPassword(String password) {
        if (password == null || password.length() < 12) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "WEAK_PASSWORD", "Password must contain at least 12 characters");
        }
    }

    private String randomToken() {
        byte[] value = new byte[32];
        random.nextBytes(value);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value);
    }

    private byte[] hash(String value) {
        if (value == null || value.isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "RESET_TOKEN_REQUIRED", "A reset token is required");
        }
        try {
            return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private ZoneId parseTimezone(String timezone) {
        try {
            return ZoneId.of(timezone);
        } catch (RuntimeException exception) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_TIMEZONE", "A valid IANA timezone is required");
        }
    }

    private String normalizeEmail(String email) {
        if (email == null || email.isBlank() || !email.contains("@")) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_EMAIL", "A valid email is required");
        }
        return email.trim().toLowerCase(Locale.ROOT);
    }

    public record RegisterCommand(
        String email,
        String password,
        String displayName,
        LocalDate birthDate,
        String timezone,
        ConsentVersions consents
    ) {
    }

    public record ConsentVersions(String terms, String privacy, String ai) {
    }

    public record UserView(
        @JsonIgnore long id,
        String publicId,
        String email,
        String displayName,
        String timezone,
        String role
    ) {
    }

    private record UserCredentials(
        long id,
        String publicId,
        String email,
        String displayName,
        String timezone,
        String role,
        String status,
        String passwordHash
    ) {
    }

    private record ResetRow(long id, long userId, Timestamp expiresAt, Timestamp consumedAt, Timestamp revokedAt) {
    }
}
