package com.betterself.growth.identity;

import com.betterself.growth.shared.api.ApiException;
import com.betterself.growth.shared.id.PublicIdGenerator;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Time;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
public class NotificationService {

    private static final Set<String> CHANNELS = Set.of("IN_APP", "EMAIL", "WEB_PUSH");

    private final JdbcTemplate jdbc;
    private final PublicIdGenerator ids;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public NotificationService(JdbcTemplate jdbc, PublicIdGenerator ids, ObjectMapper objectMapper, Clock clock) {
        this.jdbc = jdbc;
        this.ids = ids;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    public List<PreferenceView> preferences(long userId) {
        return jdbc.query(
            "select channel, enabled, max_per_day from notification_preference where user_id = ? order by channel",
            (rs, row) -> new PreferenceView(rs.getString("channel"), rs.getBoolean("enabled"), rs.getInt("max_per_day")),
            userId
        );
    }

    @Transactional
    public PreferenceView update(long userId, String channel, PreferenceCommand command) {
        String normalized = channel.toUpperCase();
        if (!CHANNELS.contains(normalized) || command.maxPerDay() < 0 || command.maxPerDay() > 10) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_NOTIFICATION_PREFERENCE", "Notification preference is invalid");
        }
        jdbc.update(
            "insert into notification_preference (user_id, channel, enabled, max_per_day) values (?, ?, ?, ?) on duplicate key update enabled = values(enabled), max_per_day = values(max_per_day), updated_at = UTC_TIMESTAMP(3)",
            userId, normalized, command.enabled(), command.maxPerDay()
        );
        return preferences(userId).stream().filter(item -> item.channel().equals(normalized)).findFirst().orElseThrow();
    }

    @Transactional
    public DeliveryView schedule(long userId, String channel, String type, String deduplicationKey, Map<String, Object> safePayload) {
        PreferenceRow preference = jdbc.query(
            """
                select np.enabled, np.max_per_day, up.timezone, up.quiet_hours_start, up.quiet_hours_end
                from notification_preference np join user_preference up on up.user_id = np.user_id
                where np.user_id = ? and np.channel = ?
                """,
            rs -> rs.next() ? new PreferenceRow(
                rs.getBoolean("enabled"), rs.getInt("max_per_day"), rs.getString("timezone"),
                rs.getTime("quiet_hours_start") == null ? null : rs.getTime("quiet_hours_start").toLocalTime(),
                rs.getTime("quiet_hours_end") == null ? null : rs.getTime("quiet_hours_end").toLocalTime()
            ) : null,
            userId, channel
        );
        if (preference == null || !preference.enabled()) {
            throw new ApiException(HttpStatus.CONFLICT, "NOTIFICATION_CHANNEL_DISABLED", "Notification channel is disabled");
        }
        Instant scheduled = nextAllowed(preference, clock.instant());
        Integer deliveredToday = jdbc.queryForObject(
            "select count(*) from notification_delivery where user_id = ? and channel = ? and status = 'DELIVERED' and date(convert_tz(delivered_at, '+00:00', ?)) = date(convert_tz(?, '+00:00', ?))",
            Integer.class, userId, channel, preference.timezone(), Timestamp.from(clock.instant()), preference.timezone()
        );
        if (deliveredToday != null && deliveredToday >= preference.maxPerDay()) {
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "NOTIFICATION_DAILY_LIMIT", "Daily notification limit reached");
        }
        String publicId = ids.next();
        jdbc.update(
            "insert ignore into notification_delivery (public_id, user_id, channel, notification_type, deduplication_key, payload, scheduled_at) values (?, ?, ?, ?, ?, cast(? as json), ?)",
            publicId, userId, channel, type, deduplicationKey, json(safePayload), Timestamp.from(scheduled)
        );
        return new DeliveryView(publicId, channel, "PENDING", scheduled);
    }

    private Instant nextAllowed(PreferenceRow preference, Instant instant) {
        ZoneId zone = ZoneId.of(preference.timezone());
        var local = instant.atZone(zone);
        if (!quiet(local.toLocalTime(), preference.quietStart(), preference.quietEnd())) {
            return instant;
        }
        var next = local.toLocalDate().atTime(preference.quietEnd()).atZone(zone);
        if (!next.isAfter(local)) {
            next = next.plusDays(1);
        }
        return next.toInstant();
    }

    private boolean quiet(LocalTime value, LocalTime start, LocalTime end) {
        if (start == null || end == null || start.equals(end)) return false;
        return start.isBefore(end) ? !value.isBefore(start) && value.isBefore(end) : !value.isBefore(start) || value.isBefore(end);
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Unable to serialize notification", exception);
        }
    }

    public record PreferenceCommand(boolean enabled, int maxPerDay) {
    }

    public record PreferenceView(String channel, boolean enabled, int maxPerDay) {
    }

    public record DeliveryView(String publicId, String channel, String status, Instant scheduledAt) {
    }

    private record PreferenceRow(boolean enabled, int maxPerDay, String timezone, LocalTime quietStart, LocalTime quietEnd) {
    }
}
