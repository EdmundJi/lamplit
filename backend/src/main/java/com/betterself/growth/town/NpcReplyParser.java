package com.betterself.growth.town;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * NPC replies are plain prose followed by a marker line carrying option chips and
 * executable actions as JSON. The prose streams to the client as it arrives; the
 * JSON tail is held back, validated against the user's own schedules, and sent once.
 */
public final class NpcReplyParser {

    public static final String MARKER = "§§";
    static final Set<String> TASK_ACTIONS = Set.of("START_TASK", "COMPLETE_TASK", "DEFER_TASK", "SKIP_TASK");
    static final Set<String> NAV_ACTIONS = Set.of("OPEN_TODAY", "OPEN_GOALS", "OPEN_AI", "OPEN_FRIENDS");
    private static final ObjectMapper JSON = new ObjectMapper();

    private NpcReplyParser() {
    }

    /** How much of the accumulated buffer is safe to forward as prose right now. */
    public static int forwardableLength(String buffer) {
        int marker = buffer.indexOf(MARKER);
        if (marker >= 0) {
            return marker;
        }
        return buffer.endsWith("§") ? buffer.length() - 1 : buffer.length();
    }

    public static Parsed parse(String full, List<TownService.ScheduleItem> schedules) {
        int marker = full.indexOf(MARKER);
        String text = (marker >= 0 ? full.substring(0, marker) : full).strip();
        List<Option> options = new ArrayList<>();
        List<Action> actions = new ArrayList<>();
        if (marker >= 0) {
            String tail = full.substring(marker + MARKER.length()).strip();
            int start = tail.indexOf('{');
            int end = tail.lastIndexOf('}');
            if (start >= 0 && end > start) {
                try {
                    JsonNode node = JSON.readTree(tail.substring(start, end + 1));
                    for (JsonNode option : node.path("options")) {
                        String label = option.path("label").asText("").strip();
                        if (!label.isEmpty() && options.size() < 3) {
                            options.add(new Option(label));
                        }
                    }
                    for (JsonNode action : node.path("actions")) {
                        Action validated = validate(action, schedules);
                        if (validated != null && actions.size() < 2) {
                            actions.add(validated);
                        }
                    }
                } catch (Exception ignored) {
                    // A malformed tail only costs the chips; the prose is still delivered.
                }
            }
        }
        return new Parsed(text, options, actions);
    }

    private static Action validate(JsonNode node, List<TownService.ScheduleItem> schedules) {
        String type = node.path("type").asText("").strip();
        String label = node.path("label").asText("").strip();
        if (NAV_ACTIONS.contains(type)) {
            return new Action(type, null, label.isEmpty() ? defaultLabel(type) : label, null);
        }
        if (!TASK_ACTIONS.contains(type)) {
            return null;
        }
        String scheduleId = node.path("scheduleId").asText("").strip();
        TownService.ScheduleItem schedule = schedules.stream()
            .filter(item -> item.publicId().equals(scheduleId))
            .findFirst()
            .orElse(null);
        if (schedule == null || !allowed(type, schedule.status())) {
            return null;
        }
        return new Action(type, scheduleId, label.isEmpty() ? defaultLabel(type) + "：" + schedule.title() : label, schedule.title());
    }

    static boolean allowed(String type, String status) {
        return switch (type) {
            case "START_TASK" -> "PLANNED".equals(status);
            case "COMPLETE_TASK", "DEFER_TASK", "SKIP_TASK" -> "PLANNED".equals(status) || "IN_PROGRESS".equals(status);
            default -> false;
        };
    }

    static String defaultLabel(String type) {
        return switch (type) {
            case "START_TASK" -> "现在开始";
            case "COMPLETE_TASK" -> "标记完成";
            case "DEFER_TASK" -> "推到明天";
            case "SKIP_TASK" -> "今天跳过";
            case "OPEN_TODAY" -> "去今日页";
            case "OPEN_GOALS" -> "看看目标";
            case "OPEN_AI" -> "找小助细聊";
            case "OPEN_FRIENDS" -> "去找朋友";
            default -> type;
        };
    }

    public record Parsed(String text, List<Option> options, List<Action> actions) {
    }

    public record Option(String label) {
    }

    public record Action(String type, String scheduleId, String label, String taskTitle) {
    }
}
