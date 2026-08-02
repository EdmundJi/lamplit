package com.betterself.growth.shared.outbox;

import com.betterself.growth.admin.TelemetryPolicy;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Clock;
import java.util.List;
import java.util.Map;

@Component
public class OutboxPublisher {

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public OutboxPublisher(JdbcTemplate jdbc, ObjectMapper objectMapper, Clock clock) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${app.outbox.delay-ms:5000}")
    @Transactional
    public int publish() {
        List<Row> rows = jdbc.query(
            "select id, payload from outbox_event where status = 'PENDING' and available_at <= ? order by id limit 100 for update skip locked",
            (rs, row) -> new Row(rs.getLong(1), rs.getString(2)), Timestamp.from(clock.instant())
        );
        for (Row row : rows) {
            try {
                Map<String, Object> payload = objectMapper.readValue(row.payload(), new TypeReference<>() {});
                TelemetryPolicy.validate(payload);
                jdbc.update("update outbox_event set status = 'PUBLISHED', published_at = ? where id = ?", Timestamp.from(clock.instant()), row.id());
            } catch (Exception exception) {
                jdbc.update("update outbox_event set attempts = attempts + 1, status = case when attempts >= 4 then 'FAILED' else 'PENDING' end, last_error_code = 'INVALID_PAYLOAD' where id = ?", row.id());
            }
        }
        return rows.size();
    }

    private record Row(long id, String payload) {
    }
}
