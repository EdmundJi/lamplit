package com.betterself.growth.town;

import com.betterself.growth.shared.api.ApiException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

/**
 * Generates each active resident's nightly reflection. Runs once at a fixed server time
 * for every user who logged a task event in the last 3 days; per-user-timezone scheduling
 * (so the job fires at each resident's own local midnight-ish hour) is a follow-up — today
 * every user's "today" is computed from their own timezone but the trigger itself is shared.
 */
@Component
public class TownReflectionJob {

    private static final Logger log = LoggerFactory.getLogger(TownReflectionJob.class);

    private final JdbcTemplate jdbc;
    private final TownReflectionService reflections;
    private final Clock clock;

    public TownReflectionJob(JdbcTemplate jdbc, TownReflectionService reflections, Clock clock) {
        this.jdbc = jdbc;
        this.reflections = reflections;
        this.clock = clock;
    }

    @Scheduled(cron = "${app.town.reflection-cron:0 15 3 * * *}")
    public void run() {
        List<Long> candidates = activeUsers();
        int generated = 0;
        for (long userId : candidates) {
            try {
                String timezone = jdbc.queryForObject("select timezone from sys_user where id = ?", String.class, userId);
                LocalDate localDate = clock.instant().atZone(ZoneId.of(timezone)).toLocalDate();
                reflections.generate(userId, localDate);
                generated++;
            } catch (ApiException quotaOrMissing) {
                // Already generated (or blocked) for this user today; skip quietly.
            } catch (RuntimeException exception) {
                log.warn("town reflection generation failed for user {}: {}", userId, exception.toString());
            }
        }
        log.info("town reflection job generated {} of {} candidate reflections", generated, candidates.size());
    }

    private List<Long> activeUsers() {
        return jdbc.queryForList(
            "select distinct user_id from task_event where occurred_at >= ?",
            Long.class, Timestamp.from(clock.instant().minusSeconds(3 * 86_400L))
        );
    }
}
