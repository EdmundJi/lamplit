package com.betterself.growth.daily;

import com.betterself.growth.shared.api.ApiException;
import com.betterself.growth.shared.id.PublicIdGenerator;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Date;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
public class DailyStatusService {

    private static final Set<String> ENERGY_LEVELS = Set.of("LOW", "STEADY", "OPEN");

    private final JdbcTemplate jdbc;
    private final PublicIdGenerator ids;
    private final Clock clock;
    private final org.springframework.data.redis.core.StringRedisTemplate redis;

    public DailyStatusService(JdbcTemplate jdbc, PublicIdGenerator ids, Clock clock, org.springframework.data.redis.core.StringRedisTemplate redis) {
        this.jdbc = jdbc;
        this.ids = ids;
        this.clock = clock;
        this.redis = redis;
    }

    /**
     * 精力偏低或少于 20 分钟 -> 缩小任务；精力充足且至少 45 分钟 -> 保持原计划；其余 -> 轻量推进。
     */
    public static String adviceFor(String energy, int availableMinutes) {
        if ("LOW".equals(energy) || availableMinutes < 20) {
            return "SHRINK";
        }
        if ("OPEN".equals(energy) && availableMinutes >= 45) {
            return "KEEP";
        }
        return "LIGHT";
    }

    @Transactional
    public StatusView save(long userId, SaveStatusCommand command, ZoneId zone) {
        if (command.energy() == null || !ENERGY_LEVELS.contains(command.energy())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_ENERGY", "精力等级无效");
        }
        int minutes = command.availableMinutes();
        if (minutes < 10 || minutes > 180) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_AVAILABLE_MINUTES", "可用时间需在 10 到 180 分钟之间");
        }
        LocalDate date = command.localDate() == null
            ? clock.instant().atZone(zone).toLocalDate()
            : command.localDate();
        String advice = adviceFor(command.energy(), minutes);
        int changed = jdbc.update(
            """
                update daily_status_check set energy = ?, available_minutes = ?, advice = ?, updated_at = ?
                where user_id = ? and local_date = ?
                """,
            command.energy(), minutes, advice, Timestamp.from(clock.instant()), userId, Date.valueOf(date)
        );
        if (changed == 0) {
            jdbc.update(
                """
                    insert into daily_status_check (public_id, user_id, local_date, energy, available_minutes, advice)
                    values (?, ?, ?, ?, ?, ?)
                    """,
                ids.next(), userId, Date.valueOf(date), command.energy(), minutes, advice
            );
        }
        try {
            redis.delete("insights:overview:v2:" + userId);
        } catch (RuntimeException ignored) {
            // Redis 不可用时缓存失效失败不阻塞保存
        }
        return status(userId, date);
    }

    public StatusView status(long userId, LocalDate date) {
        return jdbc.query(
            """
                select public_id, local_date, energy, available_minutes, advice, updated_at
                from daily_status_check where user_id = ? and local_date = ?
                """,
            rs -> rs.next() ? new StatusView(
                rs.getString("public_id"), rs.getDate("local_date") == null ? date : rs.getDate("local_date").toLocalDate(),
                rs.getString("energy"), rs.getInt("available_minutes"), rs.getString("advice"),
                rs.getTimestamp("updated_at").toInstant()
            ) : null,
            userId, Date.valueOf(date)
        );
    }

    public StatusView latest(long userId) {
        return jdbc.query(
            """
                select public_id, local_date, energy, available_minutes, advice, updated_at
                from daily_status_check where user_id = ? order by local_date desc limit 1
                """,
            rs -> rs.next() ? new StatusView(
                rs.getString("public_id"), rs.getDate("local_date").toLocalDate(),
                rs.getString("energy"), rs.getInt("available_minutes"), rs.getString("advice"),
                rs.getTimestamp("updated_at").toInstant()
            ) : null,
            userId
        );
    }

    public WeeklyStatusStats weeklyStats(long userId, LocalDate from, LocalDate to) {
        List<StatusRow> rows = jdbc.query(
            """
                select energy, advice from daily_status_check
                where user_id = ? and local_date between ? and ?
                order by local_date
                """,
            (rs, row) -> new StatusRow(rs.getString("energy"), rs.getString("advice")),
            userId, Date.valueOf(from), Date.valueOf(to)
        );
        Map<String, Integer> advices = new HashMap<>();
        advices.put("SHRINK", 0);
        advices.put("KEEP", 0);
        advices.put("LIGHT", 0);
        Map<String, Integer> energy = new HashMap<>();
        energy.put("LOW", 0);
        energy.put("STEADY", 0);
        energy.put("OPEN", 0);
        for (StatusRow row : rows) {
            advices.merge(row.advice(), 1, Integer::sum);
            energy.merge(row.energy(), 1, Integer::sum);
        }
        return new WeeklyStatusStats(rows.size(), advices, energy);
    }

    private record StatusRow(String energy, String advice) {
    }

    public record StatusView(
        String publicId,
        LocalDate localDate,
        String energy,
        int availableMinutes,
        String advice,
        Instant updatedAt
    ) {
    }

    public record SaveStatusCommand(LocalDate localDate, String energy, Integer availableMinutes) {
    }

    public record WeeklyStatusStats(int checkCount, Map<String, Integer> advices, Map<String, Integer> energy) {
    }
}
