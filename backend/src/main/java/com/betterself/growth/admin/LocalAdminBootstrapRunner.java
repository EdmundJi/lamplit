package com.betterself.growth.admin;

import com.betterself.growth.career.RoleProgressionService;
import com.betterself.growth.shared.id.PublicIdGenerator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Date;
import java.time.LocalDate;

@Component
@Profile("local")
@Order(Ordered.HIGHEST_PRECEDENCE)
public class LocalAdminBootstrapRunner implements ApplicationRunner {

    private static final Logger LOGGER = LoggerFactory.getLogger(LocalAdminBootstrapRunner.class);

    private final JdbcTemplate jdbc;
    private final PasswordEncoder passwordEncoder;
    private final PublicIdGenerator ids;
    private final RoleProgressionService roleProgression;
    private final LocalAdminLoginProperties properties;

    public LocalAdminBootstrapRunner(
        JdbcTemplate jdbc,
        PasswordEncoder passwordEncoder,
        PublicIdGenerator ids,
        RoleProgressionService roleProgression,
        LocalAdminLoginProperties properties
    ) {
        this.jdbc = jdbc;
        this.passwordEncoder = passwordEncoder;
        this.ids = ids;
        this.roleProgression = roleProgression;
        this.properties = properties;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        properties.validate();
        if (!properties.enabled()) return;

        String username = properties.username().trim();
        String normalizedUsername = properties.normalizedUsername();
        Long userId = jdbc.query(
            "select id from sys_user where email_normalized = ?",
            resultSet -> resultSet.next() ? resultSet.getLong(1) : null,
            normalizedUsername
        );
        String passwordHash = passwordEncoder.encode(properties.password());
        if (userId == null) {
            jdbc.update(
                """
                    insert into sys_user (
                        public_id, email, email_normalized, password_hash, display_name,
                        birth_date, timezone, status, role
                    ) values (?, ?, ?, ?, ?, ?, 'Asia/Shanghai', 'ACTIVE', 'ADMIN')
                    """,
                ids.next(), username, normalizedUsername, passwordHash, "本地管理员",
                Date.valueOf(LocalDate.of(1990, 1, 1))
            );
            userId = jdbc.queryForObject(
                "select id from sys_user where email_normalized = ?", Long.class, normalizedUsername
            );
        } else {
            jdbc.update(
                """
                    update sys_user
                    set password_hash = ?, display_name = '本地管理员', status = 'ACTIVE',
                        role = 'ADMIN', deleted_at = null, updated_at = UTC_TIMESTAMP(3)
                    where id = ?
                    """,
                passwordHash, userId
            );
        }

        initializeRelatedRows(userId);
        LOGGER.warn("Local development administrator login is enabled for {}. Never enable it in production.", username);
    }

    private void initializeRelatedRows(long userId) {
        jdbc.update(
            "insert ignore into user_preference (user_id, timezone) values (?, 'Asia/Shanghai')", userId
        );
        jdbc.update(
            """
                insert ignore into notification_preference (user_id, channel, enabled, max_per_day)
                values (?, 'IN_APP', 1, 2), (?, 'EMAIL', 0, 2), (?, 'WEB_PUSH', 0, 2)
                """,
            userId, userId, userId
        );
        jdbc.update(
            """
                insert ignore into user_dimension (user_id, dimension_id)
                select ?, id from growth_dimension where is_system = 1
                """,
            userId
        );
        roleProgression.initialize(userId);
    }
}
