package com.betterself.growth.admin;

import com.betterself.growth.shared.id.PublicIdGenerator;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

@Service
public class AuditService {

    private final JdbcTemplate jdbc;
    private final PublicIdGenerator ids;
    private final ObjectMapper objectMapper;

    public AuditService(JdbcTemplate jdbc, PublicIdGenerator ids, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.ids = ids;
        this.objectMapper = objectMapper;
    }

    public void record(long actorId, String action, String resourceType, String resourceId, String requestId, Map<String, Object> metadata) {
        jdbc.update(
            "insert into audit_log (public_id, actor_user_id, action, resource_type, resource_public_id, outcome, request_id, metadata) values (?, ?, ?, ?, ?, 'SUCCESS', ?, cast(? as json))",
            ids.next(), actorId, action, resourceType, resourceId, requestId, json(metadata)
        );
    }

    public List<AuditView> list() {
        return jdbc.query(
            "select public_id, action, resource_type, resource_public_id, outcome, request_id, created_at from audit_log order by id desc limit 200",
            (rs, row) -> new AuditView(
                rs.getString("public_id"), rs.getString("action"), rs.getString("resource_type"),
                rs.getString("resource_public_id"), rs.getString("outcome"), rs.getString("request_id"),
                rs.getTimestamp("created_at").toInstant()
            )
        );
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Unable to serialize audit metadata", exception);
        }
    }

    public record AuditView(String publicId, String action, String resourceType, String resourcePublicId, String outcome, String requestId, java.time.Instant createdAt) {
    }
}
