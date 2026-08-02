package com.betterself.growth.admin;

import com.betterself.growth.auth.MfaSecretCipher;
import com.betterself.growth.auth.MfaService;
import com.betterself.growth.career.RoleProgressionService;
import com.betterself.growth.shared.id.PublicIdGenerator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Date;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Locale;

@Component
@EnableConfigurationProperties(AdminBootstrapProperties.class)
public class AdminBootstrapRunner implements ApplicationRunner {

    private static final Logger LOGGER = LoggerFactory.getLogger(AdminBootstrapRunner.class);

    private final JdbcTemplate jdbc;
    private final PasswordEncoder passwordEncoder;
    private final PublicIdGenerator ids;
    private final MfaService mfaService;
    private final MfaSecretCipher mfaCipher;
    private final RoleProgressionService roleProgression;
    private final AdminBootstrapProperties properties;

    public AdminBootstrapRunner(
        JdbcTemplate jdbc,
        PasswordEncoder passwordEncoder,
        PublicIdGenerator ids,
        MfaService mfaService,
        MfaSecretCipher mfaCipher,
        RoleProgressionService roleProgression,
        AdminBootstrapProperties properties
    ) {
        this.jdbc = jdbc;
        this.passwordEncoder = passwordEncoder;
        this.ids = ids;
        this.mfaService = mfaService;
        this.mfaCipher = mfaCipher;
        this.roleProgression = roleProgression;
        this.properties = properties;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        Integer administrators = jdbc.queryForObject(
            "select count(*) from sys_user where role = 'ADMIN' and status = 'ACTIVE'",
            Integer.class
        );
        if (administrators != null && administrators > 0) {
            return;
        }
        if (!properties.hasAnyCredential()) {
            LOGGER.warn("No active administrator exists. Set ADMIN_BOOTSTRAP_EMAIL and ADMIN_BOOTSTRAP_PASSWORD once to create the first administrator.");
            return;
        }
        if (!properties.hasRequiredCredential()) {
            throw new IllegalStateException("ADMIN_BOOTSTRAP_EMAIL and ADMIN_BOOTSTRAP_PASSWORD must be configured together");
        }
        if (properties.password().length() < 12) {
            throw new IllegalStateException("ADMIN_BOOTSTRAP_PASSWORD must contain at least 12 characters");
        }

        String email = properties.email().trim();
        String normalizedEmail = email.toLowerCase(Locale.ROOT);
        String displayName = blank(properties.displayName()) ? "系统管理员" : properties.displayName().trim();
        String timezone = blank(properties.timezone()) ? "Asia/Shanghai" : ZoneId.of(properties.timezone().trim()).getId();
        String mfaSecret = blank(properties.mfaSecret()) ? mfaService.generateSecret() : properties.mfaSecret().trim().replace(" ", "");

        jdbc.update(
            """
                insert into sys_user (
                    public_id, email, email_normalized, password_hash, display_name,
                    birth_date, timezone, status, role, mfa_secret_encrypted
                ) values (?, ?, ?, ?, ?, ?, ?, 'ACTIVE', 'ADMIN', ?)
                """,
            ids.next(),
            email,
            normalizedEmail,
            passwordEncoder.encode(properties.password()),
            displayName,
            Date.valueOf(LocalDate.of(1990, 1, 1)),
            timezone,
            mfaCipher.encrypt(mfaSecret)
        );
        long userId = jdbc.queryForObject("select id from sys_user where email_normalized = ?", Long.class, normalizedEmail);
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

        LOGGER.warn("Created first administrator account for {}. Add this TOTP secret to an authenticator app: {}", email, mfaSecret);
        LOGGER.warn("Administrator MFA URI: otpauth://totp/BetterSelf:{}?secret={}&issuer=BetterSelf", email, mfaSecret);
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
