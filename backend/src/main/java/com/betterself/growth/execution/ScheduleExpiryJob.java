package com.betterself.growth.execution;

import com.betterself.growth.shared.id.PublicIdGenerator;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.List;

@Component
public class ScheduleExpiryJob {

    private final JdbcTemplate jdbc;
    private final PublicIdGenerator ids;
    private final Clock clock;

    public ScheduleExpiryJob(JdbcTemplate jdbc, PublicIdGenerator ids, Clock clock) {
        this.jdbc = jdbc;
        this.ids = ids;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${app.execution.expiry-delay-ms:60000}")
    @Transactional
    public int expireDue() {
        Instant now = clock.instant();
        List<DueSchedule> due = jdbc.query(
            """
                select s.id, s.user_id, t.title, t.estimated_minutes, t.difficulty,
                       t.dimension_weights, t.role_code
                from task_schedule s join user_task t on t.id = s.task_id
                where s.status = 'PLANNED' and s.planned_end_at < ?
                for update skip locked
                """,
            (rs, row) -> new DueSchedule(
                rs.getLong("id"), rs.getLong("user_id"), rs.getString("title"),
                rs.getInt("estimated_minutes"), rs.getInt("difficulty"), rs.getString("dimension_weights"),
                rs.getString("role_code")
            ),
            Timestamp.from(now)
        );
        for (DueSchedule schedule : due) {
            int changed = jdbc.update(
                "update task_schedule set status = 'EXPIRED', version = version + 1, updated_at = ? where id = ? and status = 'PLANNED'",
                Timestamp.from(now), schedule.id()
            );
            if (changed == 1) {
                jdbc.update(
                    """
                        insert into task_event (
                            public_id, user_id, schedule_id, event_type, occurred_at, experience_delta,
                            task_title_snapshot, estimated_minutes_snapshot, difficulty_snapshot,
                            role_code_snapshot, dimension_weights_snapshot
                        ) values (?, ?, ?, 'EXPIRED', ?, 0, ?, ?, ?, ?, cast(? as json))
                        """,
                    ids.next(), schedule.userId(), schedule.id(), Timestamp.from(now), schedule.title(),
                    schedule.estimatedMinutes(), schedule.difficulty(), schedule.roleCode(), schedule.dimensionWeights()
                );
            }
        }
        return due.size();
    }

    private record DueSchedule(
        long id,
        long userId,
        String title,
        int estimatedMinutes,
        int difficulty,
        String dimensionWeights,
        String roleCode
    ) {
    }
}
