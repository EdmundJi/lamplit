package com.betterself.growth.town;

import com.betterself.growth.ai.QwenProvider;
import com.betterself.growth.execution.TaskExecutionService;
import com.betterself.growth.shared.api.ApiException;
import com.betterself.growth.shared.id.PublicIdGenerator;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Date;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

/**
 * A Stanford-generative-agent-style nightly "reflection": a short, warm read of one day's
 * schedule turned into a greeting and a couple of concrete observations. Stored once per
 * user per local date; regenerating overwrites the same row.
 */
@Service
public class TownReflectionService {

    static final int DAILY_REFLECTION_LIMIT = 3;
    private static final String DEFAULT_GREETING = "晚上好，今天辛苦了。";
    private static final String DEFAULT_INSIGHT = "今天也在慢慢往前走。";
    private static final String SCHEMA = """
        {
          "type":"object","additionalProperties":false,"required":["greeting","insights"],
          "properties":{
            "greeting":{"type":"string","minLength":1,"maxLength":80},
            "insights":{"type":"array","minItems":1,"maxItems":3,"items":{"type":"string","minLength":1,"maxLength":60}}
          }
        }
        """;

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final QwenProvider provider;
    private final TaskExecutionService taskExecution;
    private final PublicIdGenerator ids;
    private final Clock clock;
    private final ObjectMapper objectMapper;

    public TownReflectionService(
        JdbcTemplate jdbc,
        TransactionTemplate transactions,
        QwenProvider provider,
        TaskExecutionService taskExecution,
        PublicIdGenerator ids,
        Clock clock,
        ObjectMapper objectMapper
    ) {
        this.jdbc = jdbc;
        this.transactions = transactions;
        this.provider = provider;
        this.taskExecution = taskExecution;
        this.ids = ids;
        this.clock = clock;
        this.objectMapper = objectMapper;
    }

    /** Most recent reflection on record for this user, or null if none has been generated yet. */
    public ReflectionView latest(long userId) {
        return jdbc.query(
            """
                select public_id, local_date, greeting, insights from town_reflection
                where user_id = ? order by local_date desc, id desc limit 1
                """,
            rs -> rs.next() ? toView(rs.getString("public_id"), rs.getDate("local_date").toLocalDate(), rs.getString("greeting"), rs.getString("insights")) : null,
            userId
        );
    }

    /** Generates (or regenerates) the reflection for one local date, subject to the daily quota. */
    public ReflectionView generate(long userId, LocalDate localDate) {
        reserveQuota(userId, localDate);
        String timezone = jdbc.queryForObject("select timezone from sys_user where id = ?", String.class, userId);
        List<TaskExecutionService.ScheduleView> schedules = taskExecution.schedules(userId, localDate);
        TownFacts facts = TownFacts.collect(jdbc, userId, ZoneId.of(timezone), clock);
        QwenProvider.StructuredResult result = provider.generateStructured(new QwenProvider.StructuredPrompt(
            "TOWN_REFLECTION", instruction(localDate, schedules, facts), SCHEMA
        ));
        Parsed parsed = parse(result.json());
        return store(userId, localDate, parsed, result.model());
    }

    private String instruction(LocalDate localDate, List<TaskExecutionService.ScheduleView> schedules, TownFacts facts) {
        StringBuilder text = new StringBuilder();
        text.append("你是成长小镇里那位温和、有观察力的向导「小助」，要为居民写一段简短的睡前反思。\n");
        text.append("只返回 schema 要求的 JSON，不要 markdown、不要多余文字。");
        text.append("greeting 是一句打招呼加一句概括，不超过 80 字；insights 是 1 到 3 条具体、温和、不说教的小观察，")
            .append("可以提到下面列出的具体任务名，不要虚构没发生的事，不要诊断或下判断。\n");
        text.append("日期：").append(localDate).append('\n');
        text.append("综合等级 LV.").append(facts.level());
        if (facts.dominantDimension() != null) {
            text.append("，主要方向：").append(facts.dominantDimension());
        }
        text.append("，最长连续行动 ").append(facts.longestStreak()).append(" 天，最近 7 天完成 ")
            .append(facts.completedLast7Days()).append(" 次，推迟 ").append(facts.deferredLast7Days()).append(" 次。\n");
        text.append("这一天的安排：\n");
        if (schedules.isEmpty()) {
            text.append("（这天没有安排任务）\n");
        }
        for (TaskExecutionService.ScheduleView item : schedules) {
            text.append("- 「").append(item.taskTitle()).append("」 状态=").append(item.status());
            if (item.deferred()) {
                text.append("（从更早推迟而来）");
            }
            text.append('\n');
        }
        return text.toString();
    }

    Parsed parse(String json) {
        try {
            JsonNode root = objectMapper.readTree(json);
            String greeting = trimmed(root.path("greeting").asText(""), 80);
            if (greeting.isEmpty()) {
                greeting = DEFAULT_GREETING;
            }
            List<String> insights = new ArrayList<>();
            for (JsonNode node : root.path("insights")) {
                String value = trimmed(node.asText(""), 60);
                if (!value.isEmpty() && insights.size() < 3) {
                    insights.add(value);
                }
            }
            if (insights.isEmpty()) {
                insights.add(DEFAULT_INSIGHT);
            }
            return new Parsed(greeting, insights);
        } catch (Exception exception) {
            return new Parsed(DEFAULT_GREETING, List.of(DEFAULT_INSIGHT));
        }
    }

    private String trimmed(String value, int maxLength) {
        String stripped = value == null ? "" : value.strip();
        return stripped.length() > maxLength ? stripped.substring(0, maxLength).strip() : stripped;
    }

    private ReflectionView store(long userId, LocalDate localDate, Parsed parsed, String model) {
        String insightsJson = writeJson(parsed.insights());
        return transactions.execute(status -> {
            int updated = jdbc.update(
                """
                    update town_reflection set greeting = ?, insights = cast(? as json), model_name = ?, created_at = ?
                    where user_id = ? and local_date = ?
                    """,
                parsed.greeting(), insightsJson, model, Timestamp.from(clock.instant()), userId, Date.valueOf(localDate)
            );
            String publicId;
            if (updated > 0) {
                publicId = jdbc.queryForObject(
                    "select public_id from town_reflection where user_id = ? and local_date = ?",
                    String.class, userId, Date.valueOf(localDate)
                );
            } else {
                publicId = ids.next();
                jdbc.update(
                    """
                        insert into town_reflection (public_id, user_id, local_date, greeting, insights, model_name, created_at)
                        values (?, ?, ?, ?, cast(? as json), ?, ?)
                        """,
                    publicId, userId, Date.valueOf(localDate), parsed.greeting(), insightsJson, model, Timestamp.from(clock.instant())
                );
            }
            return toView(publicId, localDate, parsed.greeting(), insightsJson);
        });
    }

    private void reserveQuota(long userId, LocalDate localDate) {
        transactions.executeWithoutResult(status -> {
            jdbc.update(
                """
                    insert into town_quota (user_id, local_date, reflection_count) values (?, ?, 1)
                    on duplicate key update reflection_count = reflection_count + 1
                    """,
                userId, Date.valueOf(localDate)
            );
            Integer count = jdbc.queryForObject(
                "select reflection_count from town_quota where user_id = ? and local_date = ?",
                Integer.class, userId, Date.valueOf(localDate)
            );
            if (count != null && count > DAILY_REFLECTION_LIMIT) {
                throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "TOWN_REFLECTION_LIMIT", "今天的反思已经写够了，明天再来看看");
            }
        });
    }

    private ReflectionView toView(String publicId, LocalDate localDate, String greeting, String insightsJson) {
        return new ReflectionView(publicId, localDate, greeting, readInsights(insightsJson));
    }

    private List<Insight> readInsights(String json) {
        try {
            List<Insight> insights = new ArrayList<>();
            for (JsonNode node : objectMapper.readTree(json)) {
                insights.add(new Insight(node.asText()));
            }
            return insights;
        } catch (Exception exception) {
            return List.of();
        }
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception exception) {
            return "[]";
        }
    }

    record Parsed(String greeting, List<String> insights) {
    }

    public record ReflectionView(String publicId, LocalDate localDate, String greeting, List<Insight> insights) {
    }

    public record Insight(String text) {
    }
}
