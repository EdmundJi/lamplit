package com.betterself.growth.ai;

import com.betterself.growth.safety.SafetyService;
import com.betterself.growth.shared.api.ApiException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;

@Service
public class GoalTemplateService {

    private static final Set<String> DIMENSIONS = Set.of("KNOWLEDGE", "HEALTH", "CAREER", "RELATIONSHIP", "WELLBEING");
    private static final String SCHEMA = """
        {
          "type":"object",
          "additionalProperties":false,
          "required":["title","description","dimensionCode","durationDays","weeklyFocus","starterTasks"],
          "properties":{
            "title":{"type":"string","minLength":1,"maxLength":160},
            "description":{"type":"string","minLength":1,"maxLength":1000},
            "dimensionCode":{"type":"string","enum":["KNOWLEDGE","HEALTH","CAREER","RELATIONSHIP","WELLBEING"]},
            "durationDays":{"type":"integer","minimum":14,"maximum":84},
            "weeklyFocus":{"type":"string","minLength":1,"maxLength":300},
            "starterTasks":{
              "type":"array","minItems":1,"maxItems":3,
              "items":{
                "type":"object","additionalProperties":false,
                "required":["title","estimatedMinutes","difficulty"],
                "properties":{
                  "title":{"type":"string","minLength":1,"maxLength":160},
                  "estimatedMinutes":{"type":"integer","minimum":5,"maximum":60},
                  "difficulty":{"type":"integer","minimum":1,"maximum":3}
                }
              }
            }
          }
        }
        """;

    private final JdbcTemplate jdbc;
    private final QwenProvider provider;
    private final SafetyService safety;
    private final ObjectMapper objectMapper;

    public GoalTemplateService(JdbcTemplate jdbc, QwenProvider provider, SafetyService safety, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.provider = provider;
        this.safety = safety;
        this.objectMapper = objectMapper;
    }

    public GoalTemplateView generate(long userId, GenerateGoalTemplateCommand command) {
        if (command == null || command.sessionPublicId() == null || command.sessionPublicId().isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "AI_SESSION_REQUIRED", "请先进行一段对话");
        }
        SessionRow session = jdbc.query(
            "select id, scene from ai_session where user_id = ? and public_id = ? and status <> 'DELETED'",
            rs -> rs.next() ? new SessionRow(rs.getLong("id"), rs.getString("scene")) : null,
            userId,
            command.sessionPublicId()
        );
        if (session == null) {
            throw new ApiException(HttpStatus.NOT_FOUND, "AI_SESSION_NOT_FOUND", "对话不存在");
        }
        List<ConversationMessage> messages = jdbc.query(
            """
                select role, content from ai_message
                where user_id = ? and session_id = ? and deleted_at is null and status = 'COMPLETED'
                order by created_at desc, id desc limit 12
                """,
            (rs, row) -> new ConversationMessage(rs.getString("role"), rs.getString("content")),
            userId,
            session.id()
        );
        Collections.reverse(messages);
        if (messages.isEmpty()) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "AI_CONVERSATION_REQUIRED", "请先告诉 AI 你想实现什么");
        }
        String conversation = messages.stream()
            .map(item -> item.role() + ": " + item.content())
            .reduce((left, right) -> left + "\n" + right)
            .orElse("");
        if (conversation.length() > 12_000) {
            conversation = conversation.substring(conversation.length() - 12_000);
        }
        SafetyService.SafetyDecision decision = safety.classifyInput(session.scene(), conversation);
        if (!decision.allowGeneration()) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "AI_SAFETY_BLOCKED", "当前对话不能生成目标模板");
        }
        QwenProvider.StructuredResult result = provider.generateStructured(new QwenProvider.StructuredPrompt(
            "GOAL_TEMPLATE",
            instruction(conversation),
            SCHEMA
        ));
        return parse(command.sessionPublicId(), result);
    }

    private String instruction(String conversation) {
        return """
            Read the following conversation and draft one practical personal-growth goal template.
            Return Chinese content and only the JSON object required by the schema, without markdown.
            The goal must last 14 to 84 days, have an observable completion standard, and avoid diagnosis or guarantees.
            Choose exactly one dimensionCode. Starter tasks must each take 5 to 60 minutes and use difficulty 1 to 3.

            Conversation:
            %s
            """.formatted(conversation);
    }

    GoalTemplateView parse(String sessionPublicId, QwenProvider.StructuredResult result) {
        try {
            JsonNode root = objectMapper.readTree(result.json());
            String title = text(root, "title", 160);
            String description = text(root, "description", 1000);
            String dimensionCode = normalizeDimension(root.path("dimensionCode").asText());
            int durationDays = clamp(root.path("durationDays").asInt(28), 14, 84);
            String weeklyFocus = text(root, "weeklyFocus", 300);
            if (dimensionCode == null) throw invalidOutput();
            JsonNode tasksNode = root.path("starterTasks");
            if (!tasksNode.isArray() || tasksNode.isEmpty() || tasksNode.size() > 3) throw invalidOutput();
            List<StarterTaskView> tasks = new ArrayList<>();
            for (JsonNode task : tasksNode) {
                String taskTitle = text(task, "title", 160);
                int minutes = clamp(task.path("estimatedMinutes").asInt(15), 5, 60);
                int difficulty = clamp(task.path("difficulty").asInt(2), 1, 3);
                tasks.add(new StarterTaskView(taskTitle, minutes, difficulty));
            }
            return new GoalTemplateView(
                sessionPublicId, title, description, dimensionCode, durationDays, weeklyFocus, tasks,
                result.model(), result.requestId()
            );
        } catch (ApiException exception) {
            throw exception;
        } catch (Exception exception) {
            throw invalidOutput();
        }
    }

    private int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private String text(JsonNode node, String field, int maxLength) {
        String value = node.path(field).asText().trim();
        if (value.isEmpty()) throw invalidOutput();
        return value.length() > maxLength ? value.substring(0, maxLength).trim() : value;
    }

    /**
     * 模型偶发返回变体维度码（大小写、中文名、英文别名等），这里统一归一到系统五个维度。
     */
    static String normalizeDimension(String raw) {
        if (raw == null) return null;
        String value = raw.trim().toUpperCase().replace("-", "_").replace(" ", "_");
        switch (value) {
            case "KNOWLEDGE", "智力", "ZHILI", "XUEXI", "STUDY", "LEARNING", "ACADEMIC":
                return "KNOWLEDGE";
            case "HEALTH", "体力", "TILI", "JIANKANG", "FITNESS", "BODY":
                return "HEALTH";
            case "CAREER", "职场", "职业", "执行力", "ZHICHANG", "WORK", "PROFESSIONAL":
                return "CAREER";
            case "RELATIONSHIP", "社交", "关系", "社交力", "SHEJIAO", "RELATION", "SOCIAL":
                return "RELATIONSHIP";
            case "WELLBEING", "情绪", "心境", "心境力", "EMOTIONAL", "EMOTION", "MINDSET", "WELL-BEING", "WELL_BEING":
                return "WELLBEING";
            default:
                return null;
        }
    }

    private ApiException invalidOutput() {
        return new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "AI_INVALID_GOAL_TEMPLATE", "AI 返回的目标模板格式无效，请重试");
    }

    public record GenerateGoalTemplateCommand(String sessionPublicId) {
    }

    public record GoalTemplateView(
        String sourceSessionPublicId,
        String title,
        String description,
        String dimensionCode,
        int durationDays,
        String weeklyFocus,
        List<StarterTaskView> starterTasks,
        String model,
        String providerRequestId
    ) {
    }

    public record StarterTaskView(String title, int estimatedMinutes, int difficulty) {
    }

    private record SessionRow(long id, String scene) {
    }

    private record ConversationMessage(String role, String content) {
    }
}
