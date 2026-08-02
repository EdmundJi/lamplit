package com.betterself.growth.admin;

import com.betterself.growth.auth.CurrentUser;
import com.betterself.growth.shared.api.ApiException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers
@SpringBootTest
class AdminGovernanceIT {

    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
        registry.add("spring.jpa.properties.hibernate.boot.allow_jdbc_metadata_access", () -> true);
        registry.add("app.security.jwt-secret", () -> "test-only-secret-at-least-thirty-two-bytes");
        registry.add("app.security.mfa-encryption-key", () -> Base64.getEncoder().encodeToString(new byte[32]));
    }

    @Autowired JdbcTemplate jdbc;
    @Autowired ContentGovernanceService content;
    @Autowired SafetyReviewService safety;
    @Autowired AdminUserService users;

    private CurrentUser administrator;
    private CurrentUser contentOperator;
    private CurrentUser safetyOperator;
    private String reviewerPublicId;

    @BeforeEach
    void setUp() {
        jdbc.update("delete from audit_log");
        jdbc.update("delete from ai_safety_event");
        jdbc.update("delete from ai_prompt_version where version > 1");
        jdbc.update("update ai_prompt_version set status = 'PUBLISHED' where version = 1");
        jdbc.update("delete from sys_user");

        administrator = user("ADMIN", "admin", Timestamp.from(Instant.now()));
        reviewerPublicId = insertUser("ADMIN", "reviewer", null).publicId();
        contentOperator = user("CONTENT_OPERATOR", "content", null);
        safetyOperator = user("SAFETY_OPERATOR", "safety", null);
        jdbc.update(
            "insert into ai_safety_event (public_id, scene, risk_level, direction, rule_codes, redacted_excerpt, expires_at) values (?, 'STUDY', 'L2', 'INPUT', json_array('SELF_HARM'), ?, date_add(utc_timestamp(3), interval 30 day))",
            "90000000000000000000000001", "redacted excerpt"
        );
    }

    @Test
    void enforcesLeastPrivilegeAndRedactsSafetyReview() {
        assertThatThrownBy(() -> safety.events(contentOperator))
            .isInstanceOf(ApiException.class)
            .extracting(error -> ((ApiException) error).code())
            .isEqualTo("ADMIN_PERMISSION_REQUIRED");

        assertThat(safety.events(safetyOperator)).singleElement()
            .extracting(SafetyReviewService.SafetyEventView::excerpt)
            .isEqualTo("[REDACTED]");
        assertThat(safety.events(administrator)).singleElement()
            .extracting(SafetyReviewService.SafetyEventView::excerpt)
            .isEqualTo("redacted excerpt");
    }

    @Test
    void publicationRequiresRecentMfaAndIndependentReviewer() {
        CurrentUser staleAdmin = user("ADMIN", "stale", Timestamp.from(Instant.now().minusSeconds(16 * 60)));
        var command = command(reviewerPublicId, "new prompt");

        assertThatThrownBy(() -> content.publishPrompt(staleAdmin, command, requestId(1)))
            .isInstanceOf(ApiException.class)
            .extracting(error -> ((ApiException) error).code())
            .isEqualTo("RECENT_MFA_REQUIRED");

        String actorPublicId = jdbc.queryForObject("select public_id from sys_user where id = ?", String.class, administrator.id());
        assertThatThrownBy(() -> content.publishPrompt(administrator, command(actorPublicId, "new prompt"), requestId(2)))
            .isInstanceOf(ApiException.class)
            .extracting(error -> ((ApiException) error).code())
            .isEqualTo("INDEPENDENT_REVIEWER_REQUIRED");
    }

    @Test
    void publishesImmutableVersionRollsBackAndAuditsBothActions() {
        String originalPrompt = jdbc.queryForObject(
            "select system_prompt from ai_prompt_version where scene = 'STUDY' and version = 1",
            String.class
        );
        ContentGovernanceService.PromptView published = content.publishPrompt(
            administrator, command(reviewerPublicId, "version two prompt"), requestId(3)
        );

        assertThat(published.version()).isEqualTo(2);
        assertThat(jdbc.queryForObject("select system_prompt from ai_prompt_version where scene = 'STUDY' and version = 1", String.class))
            .isEqualTo(originalPrompt);
        assertThat(jdbc.queryForObject("select status from ai_prompt_version where scene = 'STUDY' and version = 1", String.class))
            .isEqualTo("RETIRED");
        assertThat(jdbc.queryForObject("select status from ai_prompt_version where public_id = ?", String.class, published.publicId()))
            .isEqualTo("PUBLISHED");

        ContentGovernanceService.PromptView rolledBack = content.rollback(
            administrator, "00000000000000000000001001", requestId(4)
        );
        assertThat(rolledBack.status()).isEqualTo("PUBLISHED");
        assertThat(jdbc.queryForObject("select status from ai_prompt_version where public_id = ?", String.class, published.publicId()))
            .isEqualTo("RETIRED");
        assertThat(jdbc.queryForList("select action from audit_log order by id", String.class))
            .containsExactly("PROMPT_PUBLISH", "PROMPT_ROLLBACK");
    }

    @Test
    void createsPrivilegedUsersWithOneTimeMfaSecretAndPreventsSelfDisable() {
        AdminUserService.CreatedAdminView created = users.createPrivilegedUser(
            administrator,
            new AdminUserService.CreateAdminCommand(
                "new-admin@example.test",
                "Correct-Horse-Battery-2026!",
                "New Admin",
                "ADMIN",
                "Asia/Shanghai",
                null
            ),
            requestId(5)
        );

        assertThat(created.user().role()).isEqualTo("ADMIN");
        assertThat(created.mfaSecret()).isNotBlank();
        assertThat(jdbc.queryForObject(
            "select mfa_secret_encrypted is not null from sys_user where email_normalized = 'new-admin@example.test'",
            Boolean.class
        )).isTrue();
        assertThat(jdbc.queryForList("select action from audit_log order by id", String.class))
            .containsExactly("ADMIN_ACCOUNT_CREATE");

        assertThatThrownBy(() -> users.updateStatus(administrator, jdbc.queryForObject("select public_id from sys_user where id = ?", String.class, administrator.id()), new AdminUserService.StatusCommand("LOCKED"), requestId(6)))
            .isInstanceOf(ApiException.class)
            .extracting(error -> ((ApiException) error).code())
            .isEqualTo("CANNOT_DISABLE_SELF");
    }

    private ContentGovernanceService.PublishPromptCommand command(String reviewer, String prompt) {
        return new ContentGovernanceService.PublishPromptCommand(
            "STUDY", prompt, "{\"type\":\"object\"}", reviewer
        );
    }

    private CurrentUser user(String role, String label, Timestamp mfaVerifiedAt) {
        return insertUser(role, label, mfaVerifiedAt).user();
    }

    private InsertedUser insertUser(String role, String label, Timestamp mfaVerifiedAt) {
        String publicId = String.format("800000000000000000000%05d", Math.abs(label.hashCode()) % 100000);
        String email = label + "@example.test";
        jdbc.update(
            "insert into sys_user (public_id, email, email_normalized, password_hash, display_name, birth_date, timezone, status, role, mfa_verified_at) values (?, ?, ?, 'hash', ?, '1990-01-01', 'Asia/Shanghai', 'ACTIVE', ?, ?)",
            publicId, email, email, label, role, mfaVerifiedAt
        );
        long id = jdbc.queryForObject("select id from sys_user where public_id = ?", Long.class, publicId);
        return new InsertedUser(new CurrentUser(id, role), publicId);
    }

    private String requestId(int suffix) {
        return String.format("700000000000000000000%05d", suffix);
    }

    private record InsertedUser(CurrentUser user, String publicId) {
    }
}
