package com.betterself.growth.town;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import java.sql.Date;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;

/** Claim and effects must share the caller's short database transaction. */
@Service
public class TownDailyProduction {
    private final JdbcTemplate jdbc;
    private final Clock clock;
    public TownDailyProduction(JdbcTemplate jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }
    boolean claim(long userId, LocalDate date, String stage) {
        return jdbc.update("insert ignore into town_daily_production (town_user_id, local_date, stage) values (?, ?, ?)",
            userId, Date.valueOf(date), stage) == 1;
    }
    ZoneId zone(long userId) {
        String value = jdbc.query("select timezone from sys_user where id = ?",
            rs -> rs.next() ? rs.getString(1) : null, userId);
        try { return value == null ? clock.getZone() : ZoneId.of(value); }
        catch (RuntimeException ex) { return clock.getZone(); }
    }
    LocalDate today(long userId) { return LocalDate.now(clock.withZone(zone(userId))); }
    LocalDate dateForPeer(long sourceUserId, long destinationUserId, LocalDate sourceDate) {
        var instant = clock.instant();
        long offset = java.time.temporal.ChronoUnit.DAYS.between(
            instant.atZone(zone(sourceUserId)).toLocalDate(), instant.atZone(zone(destinationUserId)).toLocalDate());
        return sourceDate.plusDays(offset);
    }
}
