package com.betterself.growth.admin;

import com.betterself.growth.auth.CurrentUser;
import com.betterself.growth.shared.api.ApiException;
import com.betterself.growth.shared.id.PublicIdGenerator;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Clock;
import java.util.Map;

@Service
public class ContentGovernanceService {

    private final JdbcTemplate jdbc;
    private final PublicIdGenerator ids;
    private final AdminAuthorizationService authorization;
    private final AuditService audit;
    private final Clock clock;

    public ContentGovernanceService(JdbcTemplate jdbc, PublicIdGenerator ids, AdminAuthorizationService authorization, AuditService audit, Clock clock) {
        this.jdbc = jdbc;
        this.ids = ids;
        this.authorization = authorization;
        this.audit = audit;
        this.clock = clock;
    }

    @Transactional
    public PromptView publishPrompt(CurrentUser actor, PublishPromptCommand command, String requestId) {
        authorization.requireRecentMfa(actor);
        Long reviewerId = jdbc.query(
            "select id from sys_user where public_id = ? and role = 'ADMIN' and status = 'ACTIVE'",
            rs -> rs.next() ? rs.getLong(1) : null,
            command.reviewerUserPublicId()
        );
        if (reviewerId == null || reviewerId == actor.id()) {
            throw new ApiException(HttpStatus.CONFLICT, "INDEPENDENT_REVIEWER_REQUIRED", "A different active administrator must review publication");
        }
        Integer version = jdbc.queryForObject("select coalesce(max(version), 0) + 1 from ai_prompt_version where scene = ?", Integer.class, command.scene());
        jdbc.update("update ai_prompt_version set status = 'RETIRED' where scene = ? and status = 'PUBLISHED'", command.scene());
        String publicId = ids.next();
        jdbc.update(
            "insert into ai_prompt_version (public_id, scene, version, system_prompt, schema_json, status, reviewer_user_id, published_at) values (?, ?, ?, ?, cast(? as json), 'PUBLISHED', ?, ?)",
            publicId, command.scene(), version, command.systemPrompt(), command.schemaJson(), reviewerId, Timestamp.from(clock.instant())
        );
        audit.record(actor.id(), "PROMPT_PUBLISH", "AI_PROMPT", publicId, requestId, Map.of("scene", command.scene(), "version", version));
        return new PromptView(publicId, command.scene(), version, "PUBLISHED");
    }

    @Transactional
    public PromptView rollback(CurrentUser actor, String publicId, String requestId) {
        authorization.requireRecentMfa(actor);
        PromptView target = jdbc.query(
            "select public_id, scene, version, status from ai_prompt_version where public_id = ?",
            rs -> rs.next() ? new PromptView(rs.getString(1), rs.getString(2), rs.getInt(3), rs.getString(4)) : null,
            publicId
        );
        if (target == null) throw new ApiException(HttpStatus.NOT_FOUND, "PROMPT_NOT_FOUND", "Resource not found");
        jdbc.update("update ai_prompt_version set status = 'RETIRED' where scene = ? and status = 'PUBLISHED'", target.scene());
        jdbc.update("update ai_prompt_version set status = 'PUBLISHED', published_at = ? where public_id = ?", Timestamp.from(clock.instant()), publicId);
        audit.record(actor.id(), "PROMPT_ROLLBACK", "AI_PROMPT", publicId, requestId, Map.of("scene", target.scene(), "version", target.version()));
        return new PromptView(target.publicId(), target.scene(), target.version(), "PUBLISHED");
    }

    public record PublishPromptCommand(String scene, String systemPrompt, String schemaJson, String reviewerUserPublicId) {
    }

    public record PromptView(String publicId, String scene, int version, String status) {
    }
}
