package com.betterself.growth.town.companion.adapters;

import com.betterself.growth.town.companion.application.ModelUsageQuery;
import com.betterself.growth.town.companion.application.ModelUsageRecorder;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;

/**
 * A small additive counters table, one row per (user, day, call type). Chosen over a per-call log
 * because the only questions this project needs answered are "how much did today cost" and "which
 * call type is priciest" - an upsert that adds to three counters answers both without ever growing
 * unbounded, and needs no cleanup job.
 */
@Repository
public class JdbcModelUsage implements ModelUsageRecorder, ModelUsageQuery {
    private final JdbcTemplate jdbc;
    public JdbcModelUsage(JdbcTemplate jdbc){this.jdbc=jdbc;}

    @Override
    public void record(long userId, String day, String callType, int inputTokens, int outputTokens) {
        if (inputTokens <= 0 && outputTokens <= 0) return; // nothing measured, nothing to write
        jdbc.update("""
            insert into town_companion_model_usage(user_id, usage_date, call_type, call_count, input_tokens, output_tokens)
            values (?,?,?,1,?,?)
            on duplicate key update
                call_count = call_count + 1,
                input_tokens = input_tokens + values(input_tokens),
                output_tokens = output_tokens + values(output_tokens)
            """, userId, LocalDate.parse(day), callType, inputTokens, outputTokens);
    }

    @Override
    public List<DailyUsage> forDay(long userId, String day) {
        return jdbc.query(
            "select call_type, call_count, input_tokens, output_tokens from town_companion_model_usage where user_id=? and usage_date=? order by call_type",
            (rs, i) -> new DailyUsage(rs.getString(1), rs.getInt(2), rs.getLong(3), rs.getLong(4)),
            userId, LocalDate.parse(day));
    }
}
