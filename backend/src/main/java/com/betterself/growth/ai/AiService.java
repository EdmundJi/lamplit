package com.betterself.growth.ai;

import com.betterself.growth.shared.api.ApiException;
import com.betterself.growth.shared.id.PublicIdGenerator;
import com.betterself.growth.safety.CrisisResponseService;
import com.betterself.growth.safety.RiskLevel;
import com.betterself.growth.safety.SafetyService;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Service
public class AiService {

    private final JdbcTemplate jdbc;
    private final QwenProvider provider;
    private final SafetyService safety;
    private final CrisisResponseService crisis;
    private final PublicIdGenerator ids;
    private final Clock clock;
    private final com.betterself.growth.daily.DailyStatusService dailyStatus;

    public AiService(
        JdbcTemplate jdbc,
        QwenProvider provider,
        SafetyService safety,
        CrisisResponseService crisis,
        PublicIdGenerator ids,
        Clock clock,
        com.betterself.growth.daily.DailyStatusService dailyStatus
    ) {
        this.jdbc = jdbc;
        this.provider = provider;
        this.safety = safety;
        this.crisis = crisis;
        this.ids = ids;
        this.clock = clock;
        this.dailyStatus = dailyStatus;
    }

    @Transactional
    public SessionView createSession(long userId, CreateSessionCommand command) {
        String scene = normalizeScene(command == null ? null : command.scene());
        String publicId = ids.next();
        jdbc.update(
            "insert into ai_session (public_id, user_id, scene, expires_at) values (?, ?, ?, ?)",
            publicId, userId, scene, Timestamp.from(clock.instant().plus(Duration.ofDays(90)))
        );
        return new SessionView(publicId, scene, "ACTIVE");
    }

    public List<MessageView> messages(long userId, String sessionPublicId) {
        long sessionId = sessionId(userId, sessionPublicId);
        return jdbc.query(
            """
                select public_id, role, content, risk_level, model_name, status, created_at
                from ai_message where user_id = ? and session_id = ? and deleted_at is null order by id
                """,
            (rs, row) -> new MessageView(
                rs.getString("public_id"), rs.getString("role"), rs.getString("content"),
                rs.getString("risk_level"), rs.getString("model_name"), rs.getString("status"),
                rs.getTimestamp("created_at").toInstant()
            ),
            userId, sessionId
        );
    }

    public List<SessionSummaryView> sessions(long userId) {
        return jdbc.query(
            """
                select
                    s.public_id,
                    s.scene,
                    s.status,
                    s.created_at,
                    coalesce(s.updated_at, s.created_at) as updated_at,
                    (
                        select count(*)
                        from ai_message m
                        where m.session_id = s.id and m.user_id = s.user_id and m.deleted_at is null
                    ) as message_count,
                    (
                        select m.role
                        from ai_message m
                        where m.session_id = s.id and m.user_id = s.user_id and m.deleted_at is null
                        order by m.id desc
                        limit 1
                    ) as last_role,
                    (
                        select m.content
                        from ai_message m
                        where m.session_id = s.id and m.user_id = s.user_id and m.deleted_at is null
                        order by m.id desc
                        limit 1
                    ) as last_message,
                    (
                        select m.created_at
                        from ai_message m
                        where m.session_id = s.id and m.user_id = s.user_id and m.deleted_at is null
                        order by m.id desc
                        limit 1
                    ) as last_message_at
                from ai_session s
                where s.user_id = ? and s.status = 'ACTIVE'
                order by coalesce(
                    (
                        select max(m.created_at)
                        from ai_message m
                        where m.session_id = s.id and m.user_id = s.user_id and m.deleted_at is null
                    ),
                    s.updated_at,
                    s.created_at
                ) desc
                limit 30
                """,
            (rs, row) -> new SessionSummaryView(
                rs.getString("public_id"),
                rs.getString("scene"),
                rs.getString("status"),
                timestamp(rs, "created_at", clock.instant()),
                timestamp(rs, "updated_at", timestamp(rs, "created_at", clock.instant())),
                rs.getInt("message_count"),
                rs.getString("last_role"),
                rs.getString("last_message"),
                timestamp(rs, "last_message_at", null)
            ),
            userId
        );
    }

    private Instant timestamp(ResultSet rs, String column, Instant fallback) throws SQLException {
        Timestamp value = rs.getTimestamp(column);
        return value == null ? fallback : value.toInstant();
    }

    @Transactional
    public void stream(long userId, String sessionPublicId, ChatCommand command, SseEmitter emitter) {
        long sessionId = sessionId(userId, sessionPublicId);
        if (command == null || command.message() == null || command.message().isBlank() || command.message().length() > 4000) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_AI_MESSAGE", "Message must contain 1 to 4000 characters");
        }
        String scene = jdbc.queryForObject("select scene from ai_session where id = ?", String.class, sessionId);
        SafetyService.SafetyDecision input = safety.classifyInput(scene, command.message());
        insertMessage(sessionId, userId, "USER", command.message(), input.level(), null, "COMPLETED", null);
        try {
            if (input.level() == RiskLevel.L3) {
                CrisisResponseService.CrisisResponse response = crisis.respond(userId, sessionId, scene, input.ruleCodes());
                String assistantId = insertMessage(
                    sessionId, userId, "ASSISTANT", response.message(), RiskLevel.L3, null, "BLOCKED", null
                );
                emitter.send(SseEmitter.event().name("meta").data(new StreamMeta(sessionPublicId, assistantId, null, "L3")));
                emitter.send(SseEmitter.event().name("safety").data(response));
                emitter.send(SseEmitter.event().name("done").data(new StreamDone(assistantId, "BLOCKED")));
                emitter.complete();
                return;
            }
            if (input.level() == RiskLevel.L2) {
                String boundary = "我不能提供诊断、处方或替代专业服务。可以帮助你整理问题，并准备向合格专业人士咨询的要点。";
                String assistantId = insertMessage(
                    sessionId, userId, "ASSISTANT", boundary, RiskLevel.L2, null, "BLOCKED", null
                );
                emitter.send(SseEmitter.event().name("meta").data(new StreamMeta(sessionPublicId, assistantId, null, "L2")));
                emitter.send(SseEmitter.event().name("safety").data(new SafetyNotice("L2", boundary)));
                emitter.send(SseEmitter.event().name("done").data(new StreamDone(assistantId, "BLOCKED")));
                emitter.complete();
                return;
            }
            String systemPrompt = prompt(scene);
            systemPrompt = withDailyStatus(systemPrompt, userId);
            List<String> deltas = new ArrayList<>();
            QwenProvider.StreamMetadata metadata = provider.stream(
                new QwenProvider.ChatPrompt(scene, systemPrompt, command.message()), deltas::add
            );
            String content = String.join("", deltas);
            SafetyService.SafetyDecision output = safety.classifyOutput(scene, content);
            if (!output.allowGeneration()) {
                String boundary = "这次回复未通过安全检查。你可以缩小问题范围，或改用手动计划功能。";
                String assistantId = insertMessage(
                    sessionId, userId, "ASSISTANT", boundary, output.level(), metadata, "BLOCKED", null
                );
                emitter.send(SseEmitter.event().name("meta").data(new StreamMeta(sessionPublicId, assistantId, metadata.model(), output.level().name())));
                emitter.send(SseEmitter.event().name("safety").data(new SafetyNotice(output.level().name(), boundary)));
                emitter.send(SseEmitter.event().name("done").data(new StreamDone(assistantId, "BLOCKED")));
                emitter.complete();
                return;
            }
            String assistantId = insertMessage(
                sessionId, userId, "ASSISTANT", content, output.level(), metadata, "COMPLETED", null
            );
            emitter.send(SseEmitter.event().name("meta").data(new StreamMeta(sessionPublicId, assistantId, metadata.model(), output.level().name())));
            for (String delta : deltas) {
                emitter.send(SseEmitter.event().name("delta").data(new StreamDelta(delta)));
            }
            emitter.send(SseEmitter.event().name("done").data(new StreamDone(assistantId, "COMPLETED")));
            emitter.complete();
        } catch (IOException exception) {
            emitter.completeWithError(exception);
        } catch (RuntimeException exception) {
            try {
                emitter.send(SseEmitter.event().name("error").data(new StreamError("AI_STREAM_FAILED", "AI service is temporarily unavailable")));
                emitter.complete();
            } catch (IOException ignored) {
                emitter.completeWithError(exception);
            }
        }
    }

    private String insertMessage(
        long sessionId,
        long userId,
        String role,
        String content,
        RiskLevel risk,
        QwenProvider.StreamMetadata metadata,
        String status,
        Long promptVersionId
    ) {
        String publicId = ids.next();
        jdbc.update(
            """
                insert into ai_message (
                    public_id, session_id, user_id, role, content, risk_level, model_name,
                    prompt_version_id, provider_request_id, input_tokens, output_tokens,
                    latency_ms, status, expires_at
                ) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
            publicId, sessionId, userId, role, content, risk.name(), metadata == null ? null : metadata.model(),
            promptVersionId, metadata == null ? null : metadata.requestId(),
            metadata == null ? null : metadata.inputTokens(), metadata == null ? null : metadata.outputTokens(),
            metadata == null ? null : metadata.latencyMs(), status,
            Timestamp.from(clock.instant().plus(Duration.ofDays(retentionDays(userId))))
        );
        return publicId;
    }

    private long retentionDays(long userId) {
        Integer days = jdbc.queryForObject("select ai_retention_days from user_preference where user_id = ?", Integer.class, userId);
        return days == null ? 30 : days;
    }

    private long sessionId(long userId, String publicId) {
        Long id = jdbc.query(
            "select id from ai_session where user_id = ? and public_id = ? and status = 'ACTIVE'",
            rs -> rs.next() ? rs.getLong(1) : null,
            userId, publicId
        );
        if (id == null) {
            throw new ApiException(HttpStatus.NOT_FOUND, "AI_SESSION_NOT_FOUND", "Resource not found");
        }
        return id;
    }

    private String prompt(String scene) {
        String prompt = jdbc.query(
            "select system_prompt from ai_prompt_version where scene = ? and status = 'PUBLISHED' order by version desc limit 1",
            rs -> rs.next() ? rs.getString(1) : null,
            scene
        );
        if (prompt == null) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "AI_PROMPT_UNAVAILABLE", "AI service is temporarily unavailable");
        }
        return prompt;
    }

    private String withDailyStatus(String systemPrompt, long userId) {
        com.betterself.growth.daily.DailyStatusService.StatusView status = dailyStatus.latest(userId);
        if (status == null) {
            return systemPrompt;
        }
        String energy = switch (status.energy()) {
            case "LOW" -> "偏低";
            case "OPEN" -> "充足";
            default -> "稳定";
        };
        String advice = switch (status.advice()) {
            case "SHRINK" -> "缩小任务：建议今天保留一件最小行动，降低完成比例目标";
            case "KEEP" -> "保持原计划：状态和时间足够，按原计划推进";
            default -> "轻量推进：建议先完成一项任务，其余根据实际精力决定";
        };
        return systemPrompt + "\n\n【用户今日状态检查】精力：" + energy
            + "，可用时间：" + status.availableMinutes() + " 分钟，节奏建议：" + advice
            + "。安排建议时优先遵循该节奏。";
    }

    private String normalizeScene(String scene) {
        if (scene == null || !List.of("STUDY", "FITNESS", "CAREER", "EMOTIONAL_SUPPORT").contains(scene.toUpperCase())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_AI_SCENE", "AI scene is invalid");
        }
        return scene.toUpperCase();
    }

    public record CreateSessionCommand(String scene) {
    }

    public record ChatCommand(String message) {
    }

    public record SessionView(String publicId, String scene, String status) {
    }

    public record SessionSummaryView(
        String publicId,
        String scene,
        String status,
        java.time.Instant createdAt,
        java.time.Instant updatedAt,
        int messageCount,
        String lastRole,
        String lastMessage,
        java.time.Instant lastMessageAt
    ) {
    }

    public record MessageView(
        String publicId,
        String role,
        String content,
        String riskLevel,
        String model,
        String status,
        java.time.Instant createdAt
    ) {
    }

    public record StreamMeta(String sessionPublicId, String messagePublicId, String model, String riskLevel) {
    }

    public record StreamDelta(String text) {
    }

    public record StreamDone(String messagePublicId, String status) {
    }

    public record SafetyNotice(String riskLevel, String message) {
    }

    public record StreamError(String code, String message) {
    }
}
