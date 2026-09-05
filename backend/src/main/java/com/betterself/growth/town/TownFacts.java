package com.betterself.growth.town;

import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Date;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

/**
 * A compact snapshot of one resident's numbers, gathered fresh from the same tables
 * {@code InsightService}/{@code FriendService} read. Kept as raw SQL rather than injecting
 * those services so an NPC turn can build this with nothing but the shared {@link JdbcTemplate}.
 */
record TownFacts(int level, String dominantDimension, int longestStreak, int completedLast7Days, int deferredLast7Days) {

    private static final Set<String> DIMENSIONS = Set.of("KNOWLEDGE", "HEALTH", "CAREER", "RELATIONSHIP", "WELLBEING");

    static TownFacts collect(JdbcTemplate jdbc, long userId, ZoneId zone, Clock clock) {
        List<DimensionRow> rows = jdbc.query(
            """
                select d.code, ud.experience
                from user_dimension ud join growth_dimension d on d.id = ud.dimension_id
                where ud.user_id = ? and ud.active = 1 and d.is_system = 1 and d.archived_at is null
                """,
            (rs, row) -> new DimensionRow(rs.getString("code"), rs.getInt("experience")),
            userId
        );
        int totalExperience = rows.stream().mapToInt(DimensionRow::experience).sum();
        int level = attributeLevel(totalExperience);
        String dominant = rows.stream()
            .filter(row -> DIMENSIONS.contains(row.code()) && row.experience() > 0)
            .max(Comparator.comparingInt(DimensionRow::experience))
            .map(DimensionRow::code)
            .orElse(null);

        LocalDate today = clock.instant().atZone(zone).toLocalDate();
        LocalDate weekAgo = today.minusDays(6);
        return new TownFacts(
            level, dominant, longestStreak(jdbc, userId),
            completedCount(jdbc, userId, weekAgo, today), deferredCount(jdbc, userId, weekAgo, today)
        );
    }

    private static int attributeLevel(int experience) {
        return Math.max(1, Math.min(20, (int) Math.floor(Math.sqrt(Math.max(0, experience)) / 10) + 1));
    }

    /** Mirrors {@code FriendService.longestActionStreak}. */
    private static int longestStreak(JdbcTemplate jdbc, long userId) {
        List<LocalDate> activeDates = jdbc.queryForList(
            """
                select distinct s.local_date from task_event e join task_schedule s on s.id = e.schedule_id
                where e.user_id = ? and e.event_type in ('COMPLETED','PARTIAL')
                  and not exists (select 1 from task_event r where r.reverses_event_id = e.id)
                order by s.local_date
                """,
            LocalDate.class, userId
        );
        int longest = 0;
        int current = 0;
        LocalDate previous = null;
        for (LocalDate date : activeDates) {
            current = previous != null && ChronoUnit.DAYS.between(previous, date) == 1 ? current + 1 : 1;
            longest = Math.max(longest, current);
            previous = date;
        }
        return longest;
    }

    private static int completedCount(JdbcTemplate jdbc, long userId, LocalDate from, LocalDate to) {
        Integer value = jdbc.queryForObject(
            """
                select count(*) from task_event e join task_schedule s on s.id = e.schedule_id
                where e.user_id = ? and s.local_date between ? and ?
                  and e.event_type in ('COMPLETED','PARTIAL')
                  and not exists (select 1 from task_event r where r.reverses_event_id = e.id)
                """,
            Integer.class, userId, Date.valueOf(from), Date.valueOf(to)
        );
        return value == null ? 0 : value;
    }

    private static int deferredCount(JdbcTemplate jdbc, long userId, LocalDate from, LocalDate to) {
        Integer value = jdbc.queryForObject(
            """
                select count(*) from task_event e join task_schedule s on s.id = e.schedule_id
                where e.user_id = ? and s.local_date between ? and ? and e.event_type = 'DEFERRED'
                """,
            Integer.class, userId, Date.valueOf(from), Date.valueOf(to)
        );
        return value == null ? 0 : value;
    }

    private record DimensionRow(String code, int experience) {
    }
}
