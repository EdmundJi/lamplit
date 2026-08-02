package com.betterself.growth.privacy;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Clock;

@Component
public class RetentionJob {

    private final JdbcTemplate jdbc;
    private final ExportService exports;
    private final DeletionService deletions;
    private final Clock clock;

    public RetentionJob(JdbcTemplate jdbc, ExportService exports, DeletionService deletions, Clock clock) {
        this.jdbc = jdbc;
        this.exports = exports;
        this.deletions = deletions;
        this.clock = clock;
    }

    @Scheduled(cron = "${app.privacy.retention-cron:0 10 * * * *}")
    @Transactional
    public void enforce() {
        jdbc.update("update ai_message set content = '[EXPIRED]', status = 'FAILED', deleted_at = ? where expires_at <= ? and deleted_at is null",
            Timestamp.from(clock.instant()), Timestamp.from(clock.instant()));
        jdbc.update("update ai_memory set content = '[EXPIRED]', status = 'EXPIRED', deleted_at = ? where expires_at <= ? and deleted_at is null",
            Timestamp.from(clock.instant()), Timestamp.from(clock.instant()));
        exports.expireDue();
        deletions.processDue();
    }
}
