package com.betterself.growth.safety;

import com.betterself.growth.shared.id.PublicIdGenerator;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.util.List;

@Service
public class CrisisResponseService {

    private final JdbcTemplate jdbc;
    private final PublicIdGenerator ids;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public CrisisResponseService(JdbcTemplate jdbc, PublicIdGenerator ids, ObjectMapper objectMapper, Clock clock) {
        this.jdbc = jdbc;
        this.ids = ids;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    public CrisisResponse respond(
        long userId,
        Long sessionId,
        String scene,
        List<String> ruleCodes
    ) {
        Policy policy = jdbc.query(
            "select id, response_text, actions from safety_policy_version where policy_code = 'L3_CRISIS_RESPONSE' and status = 'PUBLISHED' order by version desc limit 1",
            rs -> rs.next() ? new Policy(rs.getLong(1), rs.getString(2), rs.getString(3)) : null
        );
        if (policy == null) {
            throw new IllegalStateException("Published crisis response policy is missing");
        }
        jdbc.update(
            """
                insert into ai_safety_event (
                    public_id, user_id, session_id, scene, risk_level, direction, rule_codes,
                    redacted_excerpt, policy_version_id, expires_at
                ) values (?, ?, ?, ?, 'L3', 'INPUT', cast(? as json), '[REDACTED]', ?, ?)
                """,
            ids.next(), userId, sessionId, scene, json(ruleCodes), policy.id(),
            Timestamp.from(clock.instant().plus(Duration.ofDays(180)))
        );
        return new CrisisResponse(policy.responseText(), policy.actionsJson());
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Unable to serialize safety event", exception);
        }
    }

    public record CrisisResponse(String message, String actionsJson) {
    }

    private record Policy(long id, String responseText, String actionsJson) {
    }
}
