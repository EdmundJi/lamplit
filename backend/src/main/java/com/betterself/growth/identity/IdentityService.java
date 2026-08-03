package com.betterself.growth.identity;

import com.betterself.growth.auth.AuthService;
import com.betterself.growth.insight.InsightService;
import com.betterself.growth.partner.PartnerService;
import com.betterself.growth.shared.api.ApiException;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.Period;
import java.time.ZoneId;
import java.util.List;
import java.util.Set;

@Service
public class IdentityService {

    private final JdbcTemplate jdbc;
    private final AuthService authService;
    private final InsightService insights;
    private final PartnerService partners;
    private final Clock clock;

    public IdentityService(
        JdbcTemplate jdbc,
        AuthService authService,
        InsightService insights,
        PartnerService partners,
        Clock clock
    ) {
        this.jdbc = jdbc;
        this.authService = authService;
        this.insights = insights;
        this.partners = partners;
        this.clock = clock;
    }

    public AuthService.UserView me(long userId) {
        return authService.findUser(userId);
    }

    @Transactional
    public ProfileView profile(long userId) {
        UserProfileRow user = jdbc.queryForObject(
            """
                select public_id, email, display_name, birth_date, timezone, created_at
                from sys_user where id = ?
                """,
            (rs, row) -> new UserProfileRow(
                rs.getString("public_id"),
                rs.getString("email"),
                rs.getString("display_name"),
                rs.getDate("birth_date").toLocalDate(),
                rs.getString("timezone"),
                rs.getTimestamp("created_at").toInstant()
            ),
            userId
        );
        InsightService.AttributesOverview attributes = insights.attributes(userId);
        PartnerService.PartnerProfile partner = partners.profile(userId);
        Integer effectiveActions = jdbc.queryForObject(
            """
                select count(*) from task_event e
                where e.user_id = ? and e.event_type in ('COMPLETED','PARTIAL')
                  and not exists (select 1 from task_event r where r.reverses_event_id = e.id)
                """,
            Integer.class,
            userId
        );
        LocalDate today = clock.instant().atZone(ZoneId.of(user.timezone())).toLocalDate();
        int age = Period.between(user.birthDate(), today).getYears();
        return new ProfileView(
            user.publicId(), user.email(), user.displayName(), user.birthDate(), age, user.timezone(), user.createdAt(),
            attributes.overallLevel(), attributes.totalExperience(), effectiveActions == null ? 0 : effectiveActions,
            partner.wallet(), partner.selectedPet(), partner.pets().size()
        );
    }

    @Transactional
    public PreferenceView updatePreferences(long userId, PreferenceCommand command) {
        if (command.dailyMinutes() < 5 || command.dailyMinutes() > 720) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_DAILY_MINUTES", "Daily minutes must be between 5 and 720");
        }
        if (command.weeklyFrequency() < 1 || command.weeklyFrequency() > 7) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_WEEKLY_FREQUENCY", "Weekly frequency must be between 1 and 7");
        }
        if (command.preferredDifficulty() < 1 || command.preferredDifficulty() > 3) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_DIFFICULTY", "Difficulty must be between 1 and 3");
        }
        jdbc.update(
            """
                update user_preference
                set daily_minutes = ?, weekly_frequency = ?, preferred_difficulty = ?, updated_at = UTC_TIMESTAMP(3)
                where user_id = ?
                """,
            command.dailyMinutes(),
            command.weeklyFrequency(),
            command.preferredDifficulty(),
            userId
        );
        return preference(userId);
    }

    public PreferenceView preference(long userId) {
        return jdbc.queryForObject(
            """
                select daily_minutes, weekly_frequency, preferred_difficulty, timezone,
                       ai_retention_days, ai_memory_enabled
                from user_preference where user_id = ?
                """,
            (resultSet, rowNumber) -> new PreferenceView(
                resultSet.getInt("daily_minutes"),
                resultSet.getInt("weekly_frequency"),
                resultSet.getInt("preferred_difficulty"),
                resultSet.getString("timezone"),
                resultSet.getInt("ai_retention_days"),
                resultSet.getBoolean("ai_memory_enabled")
            ),
            userId
        );
    }

    public List<ConsentView> consents(long userId) {
        return jdbc.query(
            "select consent_type, version, granted, recorded_at, withdrawn_at from consent_record where user_id = ? order by id",
            (resultSet, rowNumber) -> new ConsentView(
                resultSet.getString("consent_type"),
                resultSet.getString("version"),
                resultSet.getBoolean("granted"),
                resultSet.getTimestamp("recorded_at").toInstant(),
                resultSet.getTimestamp("withdrawn_at") == null
                    ? null
                    : resultSet.getTimestamp("withdrawn_at").toInstant()
            ),
            userId
        );
    }

    @Transactional
    public List<ConsentView> recordConsent(long userId, ConsentCommand command) {
        Set<String> supportedTypes = Set.of("TERMS", "PRIVACY", "AI");
        if (command == null || !supportedTypes.contains(command.type())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_CONSENT_TYPE", "Consent type is invalid");
        }
        String expectedVersion = switch (command.type()) {
            case "TERMS" -> AuthService.TERMS_VERSION;
            case "PRIVACY" -> AuthService.PRIVACY_VERSION;
            case "AI" -> AuthService.AI_VERSION;
            default -> throw new IllegalStateException("Unsupported consent type");
        };
        if (!expectedVersion.equals(command.version())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_CONSENT_VERSION", "Current consent version is required");
        }
        Timestamp now = Timestamp.from(clock.instant());
        int updated = jdbc.update(
            """
                update consent_record
                set granted = ?, withdrawn_at = ?, recorded_at = case when ? then ? else recorded_at end
                where user_id = ? and consent_type = ? and version = ?
                """,
            command.granted(),
            command.granted() ? null : now,
            command.granted(),
            now,
            userId,
            command.type(),
            command.version()
        );
        if (updated == 0) {
            jdbc.update(
                """
                    insert into consent_record (user_id, consent_type, version, granted, recorded_at, withdrawn_at)
                    values (?, ?, ?, ?, ?, ?)
                    """,
                userId,
                command.type(),
                command.version(),
                command.granted(),
                now,
                command.granted() ? null : now
            );
        }
        return consents(userId);
    }

    @Transactional
    public PreferenceView updateAiMemory(long userId, boolean enabled) {
        jdbc.update(
            "update user_preference set ai_memory_enabled = ?, updated_at = UTC_TIMESTAMP(3) where user_id = ?",
            enabled,
            userId
        );
        return preference(userId);
    }

    public record PreferenceCommand(int dailyMinutes, int weeklyFrequency, int preferredDifficulty) {
    }

    public record ConsentCommand(@NotBlank String type, @NotBlank String version, boolean granted) {
    }

    public record ConsentView(String type, String version, boolean granted, Instant recordedAt, Instant withdrawnAt) {
    }

    public record PreferenceView(
        int dailyMinutes,
        int weeklyFrequency,
        int preferredDifficulty,
        String timezone,
        int aiRetentionDays,
        boolean aiMemoryEnabled
    ) {
    }

    public record ProfileView(
        String publicId,
        String email,
        String displayName,
        LocalDate birthDate,
        int age,
        String timezone,
        Instant createdAt,
        int overallLevel,
        int totalExperience,
        int effectiveActions,
        PartnerService.WalletView wallet,
        PartnerService.PetView selectedPet,
        int petCount
    ) {
    }

    private record UserProfileRow(
        String publicId,
        String email,
        String displayName,
        LocalDate birthDate,
        String timezone,
        Instant createdAt
    ) {
    }
}
