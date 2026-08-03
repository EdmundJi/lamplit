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
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
public class InsightService {

    private static final String OVERVIEW_CACHE_PREFIX = "insights:overview:v2:";
    private static final Map<String, String> ATTRIBUTE_NAMES = Map.of(
        "KNOWLEDGE", "智力",
        "HEALTH", "体力",
        "CAREER", "执行力",
        "RELATIONSHIP", "社交力",
        "WELLBEING", "心境力"
    );
    private static final List<String> ATTRIBUTE_ORDER = List.of(
        "KNOWLEDGE", "HEALTH", "CAREER", "RELATIONSHIP", "WELLBEING"
    );

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

    public AttributesOverview attributes(long userId) {
        List<AttributeView> attributes = jdbc.query(
            """
                select d.code, d.name, d.description, ud.experience
                from user_dimension ud
                join growth_dimension d on d.id = ud.dimension_id
                where ud.user_id = ? and ud.active = 1 and d.is_system = 1 and d.archived_at is null
                """,
            (rs, row) -> attribute(
                rs.getString("code"),
                rs.getString("name"),
                rs.getString("description"),
                rs.getInt("experience")
            ),
            userId
        ).stream().sorted(Comparator.comparingInt(value -> ATTRIBUTE_ORDER.indexOf(value.code()))).toList();
        int totalExperience = attributes.stream().mapToInt(AttributeView::experience).sum();
        return new AttributesOverview(totalExperience, attributeLevel(totalExperience), attributes);
    }

    private AttributeView attribute(String code, String dimensionName, String description, int experience) {
        int level = attributeLevel(experience);
        int currentThreshold = level <= 1 ? 0 : (level - 1) * (level - 1) * 100;
        Integer nextThreshold = level >= 20 ? null : level * level * 100;
        int toNext = nextThreshold == null ? 0 : Math.max(0, nextThreshold - experience);
        return new AttributeView(
            code,
            ATTRIBUTE_NAMES.getOrDefault(code, dimensionName),
            dimensionName,
            description,
            experience,
            level,
            attributeScore(experience),
            currentThreshold,
            nextThreshold,
            toNext
        );
    }

    static int attributeLevel(int experience) {
        return Math.max(1, Math.min(20, (int) Math.floor(Math.sqrt(Math.max(0, experience)) / 10) + 1));
    }

    static int attributeScore(int experience) {
        if (experience <= 0) return 0;
        double score = 100 * Math.log1p(experience) / Math.log1p(5_000);
        return Math.max(0, Math.min(100, (int) Math.round(score)));
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
        WeekRange range = week;
        Map<String, Integer> statusAdvices = new HashMap<>();
        Map<String, Integer> statusEnergy = new HashMap<>();
        for (String key : List.of("SHRINK", "KEEP", "LIGHT")) {
            statusAdvices.put(key, 0);
        }
        for (String key : List.of("LOW", "STEADY", "OPEN")) {
            statusEnergy.put(key, 0);
        }
        jdbc.query(
            """
                select energy, advice from daily_status_check
                where user_id = ? and local_date between ? and ?
                """,
            rs -> {
                while (rs.next()) {
                    statusAdvices.merge(rs.getString("advice"), 1, Integer::sum);
                    statusEnergy.merge(rs.getString("energy"), 1, Integer::sum);
                }
                return null;
            },
            userId, Date.valueOf(range.start()), Date.valueOf(range.end())
        );
        int statusCheckCount = statusAdvices.values().stream().mapToInt(Integer::intValue).sum();
        return new Overview(
            plannedValue, effectiveValue, fulfillment, experience == null ? 0 : experience,
            recoveries, personalBest == null ? 0 : personalBest,
            statusCheckCount, statusAdvices, statusEnergy
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
        int personalBestDailyActions,
        int statusCheckCount,
        Map<String, Integer> statusAdvices,
        Map<String, Integer> statusEnergy
    ) {
    }

    public record AttributesOverview(int totalExperience, int overallLevel, List<AttributeView> attributes) {
    }

    public record AttributeView(
        String code,
        String name,
        String dimensionName,
        String description,
        int experience,
        int level,
        int radarScore,
        int currentLevelExperience,
        Integer nextLevelExperience,
        int experienceToNextLevel
    ) {
    }

    public record TrendPoint(LocalDate date, int effectiveActions, int experience) {
    }

    public record CalendarDay(LocalDate date, int plannedActions, int effectiveActions) {
    }

    record WeekRange(LocalDate start, LocalDate end) {
    }
}
