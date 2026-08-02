package com.betterself.growth.insight;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Date;
import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.List;

@Service
public class InsightService {

    private static final String OVERVIEW_CACHE_PREFIX = "insights:overview:v2:";

    private final JdbcTemplate jdbc;
    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public InsightService(JdbcTemplate jdbc, StringRedisTemplate redis, ObjectMapper objectMapper, Clock clock) {
        this.jdbc = jdbc;
        this.redis = redis;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    public Overview overview(long userId) {
        String key = OVERVIEW_CACHE_PREFIX + userId;
        try {
            String cached = redis.opsForValue().get(key);
            if (cached != null) {
                return objectMapper.readValue(cached, Overview.class);
            }
        } catch (RuntimeException | JsonProcessingException ignored) {
            // MySQL is the source of truth when Redis is unavailable or stale.
        }
        Overview overview = buildOverview(userId);
        try {
            redis.opsForValue().set(key, objectMapper.writeValueAsString(overview), Duration.ofMinutes(5));
        } catch (RuntimeException | JsonProcessingException ignored) {
            // Cache failures never block insights.
        }
        return overview;
    }

    public List<TrendPoint> trends(long userId, LocalDate from, LocalDate to) {
        LocalDate today = currentLocalDate(userId);
        Date start = Date.valueOf(from == null ? today.minusDays(27) : from);
        Date end = Date.valueOf(to == null ? today : to);
        return jdbc.query(
            """
                select s.local_date, coalesce(sum(e.experience_delta), 0) experience,
                       sum(case when e.event_type in ('COMPLETED','PARTIAL') then 1 else 0 end) effective
                from task_event e join task_schedule s on s.id = e.schedule_id
                where e.user_id = ? and s.local_date between ? and ?
                  and e.event_type <> 'REVERSED'
                  and not exists (select 1 from task_event r where r.reverses_event_id = e.id)
                group by s.local_date order by s.local_date
                """,
            (rs, row) -> new TrendPoint(
                rs.getDate("local_date").toLocalDate(), rs.getInt("effective"), rs.getInt("experience")
            ),
            userId, start, end
        );
    }

    public List<CalendarDay> calendar(long userId, LocalDate from, LocalDate to) {
        LocalDate today = currentLocalDate(userId);
        Date start = Date.valueOf(from == null ? today.withDayOfMonth(1) : from);
        Date end = Date.valueOf(to == null ? today : to);
        return jdbc.query(
            """
                select s.local_date, count(*) planned,
                       sum(case when s.status in ('DONE','PARTIAL') then 1 else 0 end) effective
                from task_schedule s where s.user_id = ? and s.local_date between ? and ?
                group by s.local_date order by s.local_date
                """,
            (rs, row) -> new CalendarDay(
                rs.getDate("local_date").toLocalDate(), rs.getInt("planned"), rs.getInt("effective")
            ),
            userId, start, end
        );
    }

    private Overview buildOverview(long userId) {
        WeekRange week = currentWeek(clock, userZone(userId));
        Integer planned = jdbc.queryForObject(
            "select count(*) from task_schedule where user_id = ? and local_date between ? and ?",
            Integer.class, userId, Date.valueOf(week.start()), Date.valueOf(week.end())
        );
        Integer effective = jdbc.queryForObject(
            """
                select count(*) from task_event e join task_schedule s on s.id = e.schedule_id
                where e.user_id = ? and s.local_date between ? and ?
                  and e.event_type in ('COMPLETED','PARTIAL')
                  and not exists (select 1 from task_event r where r.reverses_event_id = e.id)
                """,
            Integer.class, userId, Date.valueOf(week.start()), Date.valueOf(week.end())
        );
        Integer experience = jdbc.queryForObject(
            "select coalesce(sum(experience), 0) from user_dimension where user_id = ?",
            Integer.class, userId
        );
        List<LocalDate> activeDates = jdbc.queryForList(
            """
                select distinct s.local_date from task_event e join task_schedule s on s.id = e.schedule_id
                where e.user_id = ? and e.event_type in ('COMPLETED','PARTIAL')
                  and not exists (select 1 from task_event r where r.reverses_event_id = e.id)
                order by s.local_date
                """,
            LocalDate.class, userId
        );
        int recoveries = 0;
        for (int index = 1; index < activeDates.size(); index++) {
            if (ChronoUnit.DAYS.between(activeDates.get(index - 1), activeDates.get(index)) >= 8) {
                recoveries++;
            }
        }
        Integer personalBest = jdbc.queryForObject(
            """
                select coalesce(max(daily.effective), 0) from (
                  select count(*) effective from task_event e join task_schedule s on s.id = e.schedule_id
                  where e.user_id = ? and e.event_type in ('COMPLETED','PARTIAL')
                    and not exists (select 1 from task_event r where r.reverses_event_id = e.id)
                  group by s.local_date
                ) daily
                """,
            Integer.class, userId
        );
        int plannedValue = planned == null ? 0 : planned;
        int effectiveValue = effective == null ? 0 : effective;
        BigDecimal fulfillment = plannedValue == 0
            ? BigDecimal.ZERO.setScale(3)
            : BigDecimal.valueOf(effectiveValue).divide(BigDecimal.valueOf(plannedValue), 3, RoundingMode.HALF_UP);
        return new Overview(
            plannedValue, effectiveValue, fulfillment, experience == null ? 0 : experience,
            recoveries, personalBest == null ? 0 : personalBest
        );
    }

    private LocalDate currentLocalDate(long userId) {
        return clock.instant().atZone(userZone(userId)).toLocalDate();
    }

    private ZoneId userZone(long userId) {
        String timezone = jdbc.queryForObject(
            "select timezone from sys_user where id = ?",
            String.class,
            userId
        );
        return ZoneId.of(timezone);
    }

    static WeekRange currentWeek(Clock clock, ZoneId zone) {
        LocalDate today = clock.instant().atZone(zone).toLocalDate();
        LocalDate monday = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        return new WeekRange(monday, today);
    }

    public record Overview(
        int plannedActions,
        int effectiveActions,
        BigDecimal fulfillmentRate,
        int totalExperience,
        int recoveryCount,
        int personalBestDailyActions
    ) {
    }

    public record TrendPoint(LocalDate date, int effectiveActions, int experience) {
    }

    public record CalendarDay(LocalDate date, int plannedActions, int effectiveActions) {
    }

    record WeekRange(LocalDate start, LocalDate end) {
    }
}
