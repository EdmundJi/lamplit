package com.betterself.growth.town;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Date;
import java.time.Clock;
import java.util.Map;

/** Repairs legacy JSON-null profiles inside the provisioner's existing transaction. */
final class TownNpcRhythmRepair {
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final TownDailyProduction daily;

    TownNpcRhythmRepair(JdbcTemplate jdbc, ObjectMapper mapper, Clock clock) {
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.daily = new TownDailyProduction(jdbc, clock);
    }

    void repair(long userId) {
        var rows = jdbc.queryForList("""
            select npc_code, interests, share_drive, curiosity
            from town_npc where town_user_id = ? and
              (rhythm is null or JSON_TYPE(rhythm) <> 'OBJECT'
               or coalesce(JSON_TYPE(JSON_EXTRACT(rhythm, '$.errands')), 'NULL') <> 'ARRAY')
            for update
            """, userId);
        for (var row : rows) {
            String code = (String) row.get("npc_code");
            try {
                Map<String, Double> interests = mapper.readValue((String) row.get("interests"), new TypeReference<>() {});
                var rhythm = TownNpcRhythm.defaultFor(code, interests,
                    ((Number) row.get("share_drive")).doubleValue(), ((Number) row.get("curiosity")).doubleValue());
                jdbc.update("update town_npc set rhythm=cast(? as json) where town_user_id=? and npc_code=?",
                    mapper.writeValueAsString(rhythm), userId, code);
                // Only invalid-profile plans from today onward are regenerated; past events remain intact.
                jdbc.update("update town_npc_mood set day_plan=null where town_user_id=? and npc_code=? and local_date>=?",
                    userId, code, Date.valueOf(daily.today(userId)));
            } catch (java.io.IOException ex) {
                throw new IllegalStateException("cannot repair NPC rhythm from its profile", ex);
            }
        }
    }
}
