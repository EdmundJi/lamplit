package com.betterself.growth.town;

import com.betterself.growth.shared.api.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Set;

/**
 * Server-side memory of where each resident last stood, so a page refresh or another user's
 * poll restores "where they actually were" instead of a fresh spot recomputed from today's
 * schedule. One row per user; the client walks toward whatever this returns, it never jumps.
 */
@Service
public class TownPresenceService {

    static final Duration MIN_REPORT_INTERVAL = Duration.ofSeconds(1);
    static final Duration STALE_AFTER = Duration.ofMinutes(10);
    private static final Set<String> FACINGS = Set.of("up", "down", "left", "right");

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final Clock clock;

    public TownPresenceService(JdbcTemplate jdbc, TransactionTemplate transactions, Clock clock) {
        this.jdbc = jdbc;
        this.transactions = transactions;
        this.clock = clock;
    }

    /** The last position on record, with staleness already computed — or null if there is none yet. */
    public PresenceView latest(long userId) {
        Row row = fetch(userId);
        if (row == null) {
            return null;
        }
        boolean stale = Duration.between(row.updatedAt(), clock.instant()).compareTo(STALE_AFTER) > 0;
        return new PresenceView(row.x(), row.y(), row.facing(), row.scene(), row.updatedAt(), stale);
    }

    /**
     * Accepts a client's reported position, clamped to a plausible walking distance since the
     * last one on record (same scene only — a scene change is a discrete event, not movement,
     * so it's never clamped). A report that arrives implausibly soon after the last *accepted*
     * one in the same scene is ignored outright rather than persisted, so a runaway or buggy
     * client hammering the endpoint can't turn this into a write-amplification vector; the
     * client's own 3s throttle means this never fires for normal use.
     */
    public PresenceWriteView report(long userId, PresenceCommand command) {
        validate(command);
        Instant now = clock.instant();
        Row previous = fetch(userId);
        boolean sameScene = previous != null && previous.scene().equals(command.scene());
        if (sameScene && Duration.between(previous.updatedAt(), now).compareTo(MIN_REPORT_INTERVAL) < 0) {
            return toWriteView(previous);
        }
        double x = command.x();
        double y = command.y();
        if (sameScene) {
            double elapsedSeconds = Duration.between(previous.updatedAt(), now).toNanos() / 1_000_000_000.0;
            TownPresenceClamp.Point clamped = TownPresenceClamp.clamp(previous.x(), previous.y(), x, y, elapsedSeconds);
            x = clamped.x();
            y = clamped.y();
        }
        Row accepted = new Row(x, y, command.facing(), command.scene(), now);
        persist(userId, accepted);
        return toWriteView(accepted);
    }

    private void validate(PresenceCommand command) {
        if (command == null || command.x() == null || command.y() == null
            || command.facing() == null || !FACINGS.contains(command.facing())
            || command.scene() == null || command.scene().isBlank() || command.scene().length() > 32) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "TOWN_PRESENCE_INVALID", "位置信息不完整");
        }
    }

    private Row fetch(long userId) {
        return jdbc.query(
            "select x, y, facing, scene, updated_at from town_presence where user_id = ?",
            rs -> rs.next()
                ? new Row(rs.getDouble("x"), rs.getDouble("y"), rs.getString("facing"), rs.getString("scene"), rs.getTimestamp("updated_at").toInstant())
                : null,
            userId
        );
    }

    private void persist(long userId, Row row) {
        transactions.executeWithoutResult(status -> jdbc.update(
            """
                insert into town_presence (user_id, x, y, facing, scene, updated_at)
                values (?, ?, ?, ?, ?, ?)
                on duplicate key update x = values(x), y = values(y), facing = values(facing),
                    scene = values(scene), updated_at = values(updated_at)
                """,
            userId, row.x(), row.y(), row.facing(), row.scene(), Timestamp.from(row.updatedAt())
        ));
    }

    private static PresenceWriteView toWriteView(Row row) {
        return new PresenceWriteView(row.x(), row.y(), row.facing(), row.scene(), row.updatedAt());
    }

    private record Row(double x, double y, String facing, String scene, Instant updatedAt) {
    }

    public record PresenceCommand(Double x, Double y, String facing, String scene) {
    }

    public record PresenceView(double x, double y, String facing, String scene, Instant updatedAt, boolean stale) {
    }

    public record PresenceWriteView(double x, double y, String facing, String scene, Instant updatedAt) {
    }
}
