package com.betterself.growth.privacy;

import com.betterself.growth.town.companion.application.MemoryStore;
import com.betterself.growth.auth.SessionService;
import com.betterself.growth.shared.api.ApiException;
import com.betterself.growth.shared.id.PublicIdGenerator;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Timestamp;
import java.time.Clock;
import java.util.HexFormat;
import java.util.List;

@Service
public class DeletionService {

    private final JdbcTemplate jdbc;
    private final SessionService sessions;
    private final PublicIdGenerator ids;
    private final Clock clock;
    private final MemoryStore companionMemories;

    public DeletionService(JdbcTemplate jdbc, SessionService sessions, PublicIdGenerator ids, Clock clock, MemoryStore companionMemories) {
        this.jdbc = jdbc;
        this.sessions = sessions;
        this.ids = ids;
        this.clock = clock;
        this.companionMemories = companionMemories;
    }

    @Transactional
    public DeletionView create(long userId) {
        DeletionView existing = current(userId, false);
        if (existing != null) {
            return existing;
        }
        String publicId = ids.next();
        jdbc.update(
            "insert into deletion_request (public_id, user_id, requested_at, process_after) values (?, ?, ?, ?)",
            publicId, userId, Timestamp.from(clock.instant()),
            Timestamp.from(clock.instant().plus(RetentionPolicy.DELETION_COOLING_OFF))
        );
        jdbc.update("update sys_user set status = 'DELETION_PENDING', updated_at = ? where id = ?", Timestamp.from(clock.instant()), userId);
        jdbc.update("update notification_preference set enabled = 0, updated_at = ? where user_id = ?", Timestamp.from(clock.instant()), userId);
        sessions.revokeAll(userId);
        return current(userId, true);
    }

    public DeletionView current(long userId) {
        DeletionView view = current(userId, false);
        if (view == null) {
            throw new ApiException(HttpStatus.NOT_FOUND, "DELETION_REQUEST_NOT_FOUND", "Resource not found");
        }
        return view;
    }

    @Transactional
    public DeletionView cancel(long userId) {
        int changed = jdbc.update(
            "update deletion_request set status = 'CANCELLED', cancelled_at = ? where user_id = ? and status = 'COOLING_OFF'",
            Timestamp.from(clock.instant()), userId
        );
        if (changed == 0) {
            throw new ApiException(HttpStatus.CONFLICT, "DELETION_NOT_CANCELLABLE", "Deletion can no longer be cancelled");
        }
        jdbc.update("update sys_user set status = 'ACTIVE', updated_at = ? where id = ?", Timestamp.from(clock.instant()), userId);
        return jdbc.query(
            "select public_id, status, requested_at, process_after, completed_at, cancelled_at from deletion_request where user_id = ? order by id desc limit 1",
            rs -> rs.next() ? map(rs) : null,
            userId
        );
    }

    @Transactional
    public int processDue() {
        List<Long> users = jdbc.queryForList(
            "select user_id from deletion_request where status = 'COOLING_OFF' and process_after <= ? for update skip locked",
            Long.class, Timestamp.from(clock.instant())
        );
        for (long userId : users) {
            jdbc.update(
                "update deletion_request set status = 'PROCESSING', processing_started_at = ? where user_id = ? and status = 'COOLING_OFF'",
                Timestamp.from(clock.instant()), userId
            );
            jdbc.update("update ai_message set content = '[DELETED]', deleted_at = ? where user_id = ?", Timestamp.from(clock.instant()), userId);
            jdbc.update("update ai_memory set content = '[DELETED]', status = 'DELETED', deleted_at = ? where user_id = ?", Timestamp.from(clock.instant()), userId);
            jdbc.update("update attachment set scan_status = 'DELETED', deleted_at = ? where user_id = ?", Timestamp.from(clock.instant()), userId);
            jdbc.update("delete from town_companion_world where user_id=?", userId);
            // Residents' memories are files on disk now, not a column in the save above. Dropping the
            // save alone would leave the whole memory directory behind - exactly the kind of hole
            // docs/02-modules.md warns about when it says deletion has to take the memory directory
            // with it. This runs outside the SQL transaction and is idempotent by design.
            companionMemories.deleteUser(userId);
            String receipt = sha256("deletion:" + userId + ":" + clock.instant());
            jdbc.update(
                "update deletion_request set status = 'COMPLETED', completed_at = ?, completion_receipt_hash = ? where user_id = ? and status = 'PROCESSING'",
                Timestamp.from(clock.instant()), receipt, userId
            );
            jdbc.update(
                "update sys_user set email = concat('deleted+', id, '@example.invalid'), email_normalized = concat('deleted+', id, '@example.invalid'), display_name = 'Deleted User', password_hash = 'DELETED', status = 'DELETED', deleted_at = ?, updated_at = ? where id = ?",
                Timestamp.from(clock.instant()), Timestamp.from(clock.instant()), userId
            );
        }
        return users.size();
    }

    private DeletionView current(long userId, boolean required) {
        DeletionView view = jdbc.query(
            "select public_id, status, requested_at, process_after, completed_at, cancelled_at from deletion_request where user_id = ? and status in ('COOLING_OFF','PROCESSING') order by id desc limit 1",
            rs -> rs.next() ? map(rs) : null,
            userId
        );
        if (required && view == null) {
            throw new IllegalStateException("Deletion request was not created");
        }
        return view;
    }

    private DeletionView map(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new DeletionView(
            rs.getString("public_id"), rs.getString("status"), rs.getTimestamp("requested_at").toInstant(),
            rs.getTimestamp("process_after").toInstant(),
            rs.getTimestamp("completed_at") == null ? null : rs.getTimestamp("completed_at").toInstant(),
            rs.getTimestamp("cancelled_at") == null ? null : rs.getTimestamp("cancelled_at").toInstant()
        );
    }

    private String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    public record DeletionView(
        String publicId,
        String status,
        java.time.Instant requestedAt,
        java.time.Instant processAfter,
        java.time.Instant completedAt,
        java.time.Instant cancelledAt
    ) {
    }
}
