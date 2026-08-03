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

    private GoalTemplateView parse(String sessionPublicId, QwenProvider.StructuredResult result) {
        try {
            JsonNode root = objectMapper.readTree(result.json());
            String title = text(root, "title", 160);
            String description = text(root, "description", 1000);
            String dimensionCode = root.path("dimensionCode").asText();
            int durationDays = root.path("durationDays").asInt();
            String weeklyFocus = text(root, "weeklyFocus", 300);
            if (!DIMENSIONS.contains(dimensionCode) || durationDays < 14 || durationDays > 84) throw invalidOutput();
            JsonNode tasksNode = root.path("starterTasks");
            if (!tasksNode.isArray() || tasksNode.isEmpty() || tasksNode.size() > 3) throw invalidOutput();
            List<StarterTaskView> tasks = new ArrayList<>();
            for (JsonNode task : tasksNode) {
                String taskTitle = text(task, "title", 160);
                int minutes = task.path("estimatedMinutes").asInt();
                int difficulty = task.path("difficulty").asInt();
                if (minutes < 5 || minutes > 60 || difficulty < 1 || difficulty > 3) throw invalidOutput();
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

    private String text(JsonNode node, String field, int maxLength) {
        String value = node.path(field).asText().trim();
        if (value.isEmpty() || value.length() > maxLength) throw invalidOutput();
        return value;
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
