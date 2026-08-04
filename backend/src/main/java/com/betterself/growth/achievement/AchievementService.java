package com.betterself.growth.achievement;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

@Service
public class AchievementService {

    private final JdbcTemplate jdbc;
    private final Clock clock;
    private final AchievementConditionEvaluator evaluator;
    private final TitleService titleService;

    public AchievementService(
        JdbcTemplate jdbc,
        Clock clock,
        AchievementConditionEvaluator evaluator,
        TitleService titleService
    ) {
        this.jdbc = jdbc;
        this.clock = clock;
        this.evaluator = evaluator;
        this.titleService = titleService;
    }

    /**
     * 列出全部成就并评估解锁。惰性评估：读取时计算当前指标，
     * 对新满足条件的成就写入解锁记录并发放称号奖励（一次性）。
     */
    @Transactional
    public List<AchievementView> list(long userId) {
        GrowthMetrics metrics = metrics(userId);
        List<AchievementRow> all = jdbc.query(
            """
                select code, name, body, trigger_text, category, condition_json, reward_title_code, icon_key, tone
                from achievement where is_active = 1 order by sort_order, id
                """,
            (rs, row) -> new AchievementRow(
                rs.getString("code"),
                rs.getString("name"),
                rs.getString("body"),
                rs.getString("trigger_text"),
                rs.getString("category"),
                rs.getString("condition_json"),
                rs.getString("reward_title_code"),
                rs.getString("icon_key"),
                rs.getString("tone")
            )
        );
        Map<String, Instant> earned = new HashMap<>();
        jdbc.query(
            "select achievement_code, earned_at from user_achievement where user_id = ?",
            rs -> {
                earned.put(rs.getString("achievement_code"), rs.getTimestamp("earned_at").toInstant());
            },
            userId
        );
        List<AchievementView> views = new ArrayList<>(all.size());
        for (AchievementRow achievement : all) {
            Instant earnedAt = earned.get(achievement.code());
            if (earnedAt == null && evaluator.isMet(achievement.conditionJson(), metrics)) {
                earnedAt = clock.instant();
                jdbc.update(
                    """
                        insert into user_achievement (user_id, achievement_code, earned_at)
                        values (?, ?, ?)
                        on duplicate key update achievement_code = achievement_code
                        """,
                    userId,
                    achievement.code(),
                    Timestamp.from(earnedAt)
                );
                if (achievement.rewardTitleCode() != null) {
                    titleService.acquire(userId, achievement.rewardTitleCode());
                }
            }
            views.add(new AchievementView(
                achievement.code(),
                achievement.name(),
                achievement.body(),
                achievement.triggerText(),
                achievement.category(),
                achievement.iconKey(),
                achievement.tone(),
                earnedAt != null,
                earnedAt
            ));
        }
        return views;
    }

    private GrowthMetrics metrics(long userId) {
        LocalDate today = clock.instant().atZone(userZone(userId)).toLocalDate();
        LocalDate monday = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        Date start = Date.valueOf(monday);
        Date end = Date.valueOf(today);

        Integer planned = jdbc.queryForObject(
            "select count(*) from task_schedule where user_id = ? and local_date between ? and ?",
            Integer.class,
            userId, start, end
        );
        Integer effective = jdbc.queryForObject(
            """
                select count(*) from task_event e join task_schedule s on s.id = e.schedule_id
                where e.user_id = ? and s.local_date between ? and ?
                  and e.event_type in ('COMPLETED','PARTIAL')
                  and not exists (select 1 from task_event r where r.reverses_event_id = e.id)
                """,
            Integer.class,
            userId, start, end
        );
        int plannedValue = planned == null ? 0 : planned;
        int effectiveValue = effective == null ? 0 : effective;
        BigDecimal fulfillment = plannedValue == 0
            ? BigDecimal.ZERO.setScale(3)
            : BigDecimal.valueOf(effectiveValue)
                .divide(BigDecimal.valueOf(plannedValue), 3, RoundingMode.HALF_UP);

        Integer experience = jdbc.queryForObject(
            "select coalesce(sum(experience), 0) from user_dimension where user_id = ?",
            Integer.class,
            userId
        );

        List<LocalDate> activeDates = jdbc.queryForList(
            """
                select distinct s.local_date from task_event e join task_schedule s on s.id = e.schedule_id
                where e.user_id = ? and e.event_type in ('COMPLETED','PARTIAL')
                  and not exists (select 1 from task_event r where r.reverses_event_id = e.id)
                order by s.local_date
                """,
            LocalDate.class,
            userId
        );
        ActionHistory actionHistory = actionHistory(activeDates);

        Map<String, Integer> roleLevels = new HashMap<>();
        jdbc.query(
            "select role_code, level from user_role_progress where user_id = ?",
            rs -> {
                roleLevels.put(rs.getString("role_code"), rs.getInt("level"));
            },
            userId
        );

        return new GrowthMetrics(
            effectiveValue,
            fulfillment,
            actionHistory.recoveryCount(),
            experience == null ? 0 : experience,
            actionHistory.longestStreak(),
            roleLevels
        );
    }

    private ActionHistory actionHistory(List<LocalDate> activeDates) {
        int recoveries = 0;
        int longest = 0;
        int current = 0;
        LocalDate previous = null;
        for (LocalDate date : activeDates) {
            long gap = previous == null ? 0 : ChronoUnit.DAYS.between(previous, date);
            if (gap >= 8) {
                recoveries++;
            }
            if (previous != null && gap == 1) {
                current += 1;
            } else {
                current = 1;
            }
            longest = Math.max(longest, current);
            previous = date;
        }
        return new ActionHistory(recoveries, longest);
    }

    private ZoneId userZone(long userId) {
        return ZoneId.of(jdbc.queryForObject(
            "select timezone from sys_user where id = ?",
            String.class,
            userId
        ));
    }

    public record GrowthMetrics(
        int effectiveActions,
        BigDecimal fulfillmentRate,
        int recoveryCount,
        int totalExperience,
        int longestStreak,
        Map<String, Integer> roleLevels
    ) {
        public GrowthMetrics {
            Objects.requireNonNull(fulfillmentRate, "fulfillmentRate");
            roleLevels = Map.copyOf(Objects.requireNonNull(roleLevels, "roleLevels"));
        }
    }

    public record AchievementView(
        String code,
        String name,
        String body,
        String triggerText,
        String category,
        String iconKey,
        String tone,
        boolean earned,
        Instant earnedAt
    ) {
    }

    private record AchievementRow(
        String code,
        String name,
        String body,
        String triggerText,
        String category,
        String conditionJson,
        String rewardTitleCode,
        String iconKey,
        String tone
    ) {
    }

    private record ActionHistory(int recoveryCount, int longestStreak) {
    }
}
