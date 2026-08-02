package com.betterself.growth.insight;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class MetricRebuildJob {

    private final JdbcTemplate jdbc;

    public MetricRebuildJob(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Scheduled(cron = "${app.insights.rebuild-cron:0 25 3 * * *}")
    @Transactional
    public void rebuildDimensionExperience() {
        jdbc.update(
            """
                update user_dimension ud
                left join (
                  select e.user_id, d.id dimension_id,
                         sum(round(e.experience_delta * cast(json_unquote(json_extract(e.dimension_weights_snapshot, concat('$.', d.code))) as decimal(10,4)) /
                           json_weight.total_weight)) experience
                  from task_event e
                  join growth_dimension d on json_contains_path(e.dimension_weights_snapshot, 'one', concat('$.', d.code))
                  join (
                    select id, (select sum(cast(value as unsigned)) from json_table(dimension_weights_snapshot, '$.*' columns(value int path '$')) weights) total_weight
                    from task_event
                  ) json_weight on json_weight.id = e.id
                  group by e.user_id, d.id
                ) totals on totals.user_id = ud.user_id and totals.dimension_id = ud.dimension_id
                set ud.experience = greatest(0, coalesce(totals.experience, 0)),
                    ud.level = floor(sqrt(greatest(0, coalesce(totals.experience, 0))) / 10) + 1,
                    ud.updated_at = UTC_TIMESTAMP(3)
                """
        );
        jdbc.update(
            """
                update user_role_progress rp
                left join (
                    select user_id, role_code_snapshot role_code, sum(role_experience_delta) experience
                    from task_event
                    group by user_id, role_code_snapshot
                ) totals on totals.user_id = rp.user_id and totals.role_code = rp.role_code
                set rp.experience = least(2149, greatest(0,
                        rp.experience_baseline + coalesce(totals.experience, 0))),
                    rp.updated_at = UTC_TIMESTAMP(3)
                """
        );
        jdbc.update(
            """
                update user_role_progress
                set level = case
                        when experience >= 1150 then 10
                        when experience >= 705 then 9
                        when experience >= 430 then 8
                        when experience >= 260 then 7
                        when experience >= 155 then 6
                        when experience >= 90 then 5
                        when experience >= 50 then 4
                        when experience >= 25 then 3
                        when experience >= 10 then 2
                        else 1
                    end,
                    updated_at = UTC_TIMESTAMP(3)
                """
        );
    }
}
