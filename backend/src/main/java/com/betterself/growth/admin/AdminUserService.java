package com.betterself.growth.admin;

import com.betterself.growth.auth.CurrentUser;
import com.betterself.growth.auth.MfaSecretCipher;
import com.betterself.growth.auth.MfaService;
import com.betterself.growth.career.RoleProgressionService;
import com.betterself.growth.shared.api.ApiException;
import com.betterself.growth.shared.id.PublicIdGenerator;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Date;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@Service
public class AdminUserService {

    private static final Set<String> ROLES = Set.of("USER", "ADMIN", "CONTENT_OPERATOR", "SAFETY_OPERATOR");
    private static final Set<String> STATUSES = Set.of("ACTIVE", "LOCKED", "DELETION_PENDING", "DELETED");

    private final JdbcTemplate jdbc;
    private final AdminAuthorizationService authorization;
    private final AuditService audit;
    private final PasswordEncoder passwordEncoder;
    private final PublicIdGenerator ids;
    private final MfaService mfaService;
    private final MfaSecretCipher mfaCipher;
    private final RoleProgressionService roleProgression;

    public AdminUserService(
        JdbcTemplate jdbc,
        AdminAuthorizationService authorization,
        AuditService audit,
        PasswordEncoder passwordEncoder,
        PublicIdGenerator ids,
        MfaService mfaService,
        MfaSecretCipher mfaCipher,
        RoleProgressionService roleProgression
    ) {
        this.jdbc = jdbc;
        this.authorization = authorization;
        this.audit = audit;
        this.passwordEncoder = passwordEncoder;
        this.ids = ids;
        this.mfaService = mfaService;
        this.mfaCipher = mfaCipher;
        this.roleProgression = roleProgression;
    }

    public List<UserAdminView> users(CurrentUser actor) {
        authorization.require(actor, "USER_ADMIN");
        return jdbc.query(
            """
                select public_id, email, display_name, timezone, status, role, created_at, updated_at
                from sys_user
                where deleted_at is null
                order by
                    case when role = 'ADMIN' then 0 when role <> 'USER' then 1 else 2 end,
                    id desc
                limit 200
                """,
            (rs, row) -> new UserAdminView(
                rs.getString("public_id"),
                rs.getString("email"),
                rs.getString("display_name"),
                rs.getString("timezone"),
                rs.getString("status"),
                rs.getString("role"),
                instant(rs.getTimestamp("created_at")),
                instant(rs.getTimestamp("updated_at"))
            )
        );
    }

    @Transactional
    public CreatedAdminView createPrivilegedUser(CurrentUser actor, CreateAdminCommand command, String requestId) {
        authorization.require(actor, "USER_ADMIN");
        if (command == null || command.email() == null || command.email().isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_ADMIN_EMAIL", "A valid email is required");
        }
        if (command.password() == null || command.password().length() < 12) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "WEAK_PASSWORD", "Password must contain at least 12 characters");
        }
        String role = blank(command.role()) ? "ADMIN" : command.role();
        if (!ROLES.contains(role) || "USER".equals(role)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_ADMIN_ROLE", "Administrator role is invalid");
        }
        String timezone = blank(command.timezone()) ? "Asia/Shanghai" : ZoneId.of(command.timezone().trim()).getId();
        String displayName = blank(command.displayName()) ? "后台账号" : command.displayName().trim();
        String email = command.email().trim();
        String normalizedEmail = email.toLowerCase(Locale.ROOT);
        String mfaSecret = blank(command.mfaSecret()) ? mfaService.generateSecret() : command.mfaSecret().trim().replace(" ", "");
        String publicId = ids.next();
        try {
            jdbc.update(connection -> {
                PreparedStatement statement = connection.prepareStatement(
                    """
                        insert into sys_user (
                            public_id, email, email_normalized, password_hash, display_name,
                            birth_date, timezone, status, role, mfa_secret_encrypted
                        ) values (?, ?, ?, ?, ?, ?, ?, 'ACTIVE', ?, ?)
                        """,
                    Statement.NO_GENERATED_KEYS
                );
                statement.setString(1, publicId);
                statement.setString(2, email);
                statement.setString(3, normalizedEmail);
                statement.setString(4, passwordEncoder.encode(command.password()));
                statement.setString(5, displayName);
                statement.setDate(6, Date.valueOf(LocalDate.of(1990, 1, 1)));
                statement.setString(7, timezone);
                statement.setString(8, role);
                statement.setBytes(9, mfaCipher.encrypt(mfaSecret));
                return statement;
            });
        } catch (DuplicateKeyException exception) {
            throw new ApiException(HttpStatus.CONFLICT, "EMAIL_ALREADY_REGISTERED", "Email is already registered");
        }
        long userId = jdbc.queryForObject("select id from sys_user where public_id = ?", Long.class, publicId);
        initializeUserDefaults(userId, timezone);
        audit.record(actor.id(), "ADMIN_ACCOUNT_CREATE", "USER", publicId, requestId, Map.of("role", role));
        return new CreatedAdminView(user(publicId), mfaSecret);
    }

    @Transactional
    public UserAdminView updateRole(CurrentUser actor, String userPublicId, RoleCommand command, String requestId) {
        authorization.require(actor, "USER_ADMIN");
        if (command == null || !ROLES.contains(command.role())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_ADMIN_ROLE", "Administrator role is invalid");
        }
        UserTarget target = target(userPublicId);
        if (target.id() == actor.id() && !"ADMIN".equals(command.role())) {
            throw new ApiException(HttpStatus.CONFLICT, "CANNOT_DEMOTE_SELF", "Administrators cannot demote their own account");
        }
        if ("USER".equals(target.role()) && !"USER".equals(command.role())) {
            throw new ApiException(HttpStatus.CONFLICT, "ADMIN_MFA_REQUIRED", "Create a privileged account with MFA instead");
        }
        jdbc.update("update sys_user set role = ?, updated_at = current_timestamp(3) where id = ?", command.role(), target.id());
        audit.record(actor.id(), "USER_ROLE_UPDATE", "USER", userPublicId, requestId, Map.of("role", command.role()));
        return user(userPublicId);
    }

    @Transactional
    public UserAdminView updateStatus(CurrentUser actor, String userPublicId, StatusCommand command, String requestId) {
        authorization.require(actor, "USER_ADMIN");
        if (command == null || !STATUSES.contains(command.status())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_USER_STATUS", "User status is invalid");
        }
        UserTarget target = target(userPublicId);
        if (target.id() == actor.id() && !"ACTIVE".equals(command.status())) {
            throw new ApiException(HttpStatus.CONFLICT, "CANNOT_DISABLE_SELF", "Administrators cannot disable their own account");
        }
        jdbc.update("update sys_user set status = ?, updated_at = current_timestamp(3) where id = ?", command.status(), target.id());
        audit.record(actor.id(), "USER_STATUS_UPDATE", "USER", userPublicId, requestId, Map.of("status", command.status()));
        return user(userPublicId);
    }

    private UserAdminView user(String publicId) {
        return jdbc.query(
            """
                select public_id, email, display_name, timezone, status, role, created_at, updated_at
                from sys_user where public_id = ?
                """,
            rs -> rs.next() ? new UserAdminView(
                rs.getString("public_id"),
                rs.getString("email"),
                rs.getString("display_name"),
                rs.getString("timezone"),
                rs.getString("status"),
                rs.getString("role"),
                instant(rs.getTimestamp("created_at")),
                instant(rs.getTimestamp("updated_at"))
            ) : null,
            publicId
        );
    }

    private UserTarget target(String publicId) {
        UserTarget target = jdbc.query(
            "select id, role, status from sys_user where public_id = ?",
            rs -> rs.next() ? new UserTarget(rs.getLong("id"), rs.getString("role"), rs.getString("status")) : null,
            publicId
        );
        if (target == null) {
            throw new ApiException(HttpStatus.NOT_FOUND, "USER_NOT_FOUND", "Resource not found");
        }
        return target;
    }

    private void initializeUserDefaults(long userId, String timezone) {
        jdbc.update("insert into user_preference (user_id, timezone) values (?, ?)", userId, timezone);
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
    }

    private static Instant instant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    public record CreateAdminCommand(String email, String password, String displayName, String role, String timezone, String mfaSecret) {
    }

    public record RoleCommand(String role) {
    }

    public record StatusCommand(String status) {
    }

    public record UserAdminView(
        String publicId,
        String email,
        String displayName,
        String timezone,
        String status,
        String role,
        Instant createdAt,
        Instant updatedAt
    ) {
    }

    public record CreatedAdminView(UserAdminView user, String mfaSecret) {
    }

    private record UserTarget(long id, String role, String status) {
    }
}
