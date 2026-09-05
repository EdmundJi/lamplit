package com.betterself.growth.town;

import com.betterself.growth.ai.QwenProvider;
import com.betterself.growth.safety.CrisisResponseService;
import com.betterself.growth.safety.RiskLevel;
import com.betterself.growth.safety.SafetyService;
import com.betterself.growth.shared.api.ApiException;
import com.betterself.growth.shared.id.PublicIdGenerator;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.sql.Date;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * In-world dialogue with the town's NPCs. The character has its own day (mood, whereabouts,
 * a small event — {@link TownNpcDailyState}) and a coarse impression of the resident
 * ({@link TownNpcPerception}); today's actual task list is only ever handed to the model to
 * validate the {@code §§} action tail, never as material for the spoken reply. Option chips
 * and actions ride on that marker line, which never reaches the user as prose. Actions are
 * only proposals: the client executes them through the normal task-event endpoints after the
 * user taps.
 */
@Service
public class TownNpcService {

    static final int DAILY_CHAT_LIMIT = 80;
    static final int HISTORY_WINDOW = 8;
    static final int SUMMARY_EVERY = 10;
    private static final Logger log = LoggerFactory.getLogger(TownNpcService.class);
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm");

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final QwenProvider provider;
    private final SafetyService safety;
    private final CrisisResponseService crisis;
    private final PublicIdGenerator ids;
    private final Clock clock;
    private final TownService town;
    private final TownReflectionService reflections;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

    public TownNpcService(
        JdbcTemplate jdbc,
        TransactionTemplate transactions,
        QwenProvider provider,
        SafetyService safety,
        CrisisResponseService crisis,
        PublicIdGenerator ids,
        Clock clock,
        TownService town,
        TownReflectionService reflections
    ) {
        this.jdbc = jdbc;
        this.transactions = transactions;
        this.provider = provider;
        this.safety = safety;
        this.crisis = crisis;
        this.ids = ids;
        this.clock = clock;
        this.town = town;
        this.reflections = reflections;
    }

    public List<MessageView> messages(long userId, String npcCode) {
        String npc = requireNpc(npcCode);
        List<MessageView> rows = jdbc.query(
            """
                select public_id, role, content, options_json, actions_json, status, created_at
                from town_npc_message where user_id = ? and npc_code = ?
                order by id desc limit 20
                """,
            (rs, row) -> new MessageView(
                rs.getString("public_id"), rs.getString("role"), rs.getString("content"),
                readOptions(rs.getString("options_json")), readActions(rs.getString("actions_json")),
                rs.getString("status"), rs.getTimestamp("created_at").toInstant()
            ),
            userId, npc
        );
        return rows.reversed();
    }

    /** Validates and reserves quota synchronously, then streams on a virtual thread. */
    public void chat(long userId, String npcCode, ChatCommand command, SseEmitter emitter) {
        String npc = requireNpc(npcCode);
        String message = command == null || command.message() == null ? "" : command.message().strip();
        if (message.isEmpty() || message.length() > 1000) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_TOWN_MESSAGE", "说点什么吧，1 到 1000 个字");
        }
        TownService.UserRow user = town.user(userId);
        ZoneId zone = ZoneId.of(user.timezone());
        reserveQuota(userId, clock.instant().atZone(zone).toLocalDate());
        executor.submit(() -> run(user, zone, npc, message, emitter));
    }

    private void run(TownService.UserRow user, ZoneId zone, String npc, String message, SseEmitter emitter) {
        long userId = user.id();
        try {
            SafetyService.SafetyDecision input = safety.classifyInput(npc, message);
            transactions.executeWithoutResult(status -> insertMessage(userId, npc, "USER", message, input.level(), null, null, null, "COMPLETED"));
            if (input.level() == RiskLevel.L3) {
                CrisisResponseService.CrisisResponse response = transactions.execute(status -> crisis.respond(userId, null, "TOWN_" + npc, input.ruleCodes()));
                String id = transactions.execute(status -> insertMessage(userId, npc, "ASSISTANT", response.message(), RiskLevel.L3, null, null, null, "BLOCKED"));
                emitter.send(SseEmitter.event().name("meta").data(new StreamMeta(npc, id, null, "L3")));
                emitter.send(SseEmitter.event().name("safety").data(response));
                emitter.send(SseEmitter.event().name("done").data(new StreamDone(id, "BLOCKED", List.of(), List.of())));
                emitter.complete();
                return;
            }
            if (input.level() == RiskLevel.L2) {
                String boundary = "这个话题我只能陪你聊一般性的感受，不能替代医生或专业人士。要不要先把最让你在意的一点写下来，我们一起看看能做的小事？";
                String id = transactions.execute(status -> insertMessage(userId, npc, "ASSISTANT", boundary, RiskLevel.L2, null, null, null, "BLOCKED"));
                emitter.send(SseEmitter.event().name("meta").data(new StreamMeta(npc, id, null, "L2")));
                emitter.send(SseEmitter.event().name("safety").data(new SafetyNotice("L2", boundary)));
                emitter.send(SseEmitter.event().name("done").data(new StreamDone(id, "BLOCKED", List.of(), List.of())));
                emitter.complete();
                return;
            }

            Instant turnStart = clock.instant();
            Long justRecordedPromiseId = recordPromiseIfAny(userId, npc, message, turnStart);
            String promiseToAsk = pendingPromiseToAsk(userId, npc, justRecordedPromiseId, turnStart);
            List<TownService.ScheduleItem> schedules = town.todaySchedules(userId, zone);
            String systemPrompt = systemPrompt(user, zone, npc, schedules, promiseToAsk);
            // The message id is minted up front so `meta` can go out before the first `delta`,
            // and prose streams to the client live; only the `§§` tail is held back.
            String id = ids.next();
            emitter.send(SseEmitter.event().name("meta").data(new StreamMeta(npc, id, null, input.level().name())));
            StringBuilder buffer = new StringBuilder();
            int[] sent = {0};
            QwenProvider.StreamMetadata streamed = null;
            try {
                streamed = provider.stream(
                    new QwenProvider.ChatPrompt("TOWN_" + npc, systemPrompt, message),
                    delta -> {
                        buffer.append(delta);
                        int forwardable = NpcReplyParser.forwardableLength(buffer.toString());
                        if (forwardable > sent[0]) {
                            String chunk = buffer.substring(sent[0], forwardable);
                            sent[0] = forwardable;
                            try {
                                emitter.send(SseEmitter.event().name("delta").data(new StreamDelta(chunk)));
                            } catch (IOException exception) {
                                throw new StreamClosed(exception);
                            }
                        }
                    }
                );
            } catch (ApiException exception) {
                // Prose lands long before the option tail does. If the provider drops out
                // afterwards, keep the reply the user already read instead of replacing it
                // with an error; the chips are the only thing lost.
                if (buffer.toString().isBlank()) {
                    throw exception;
                }
                log.warn("town npc stream truncated for user {}: {}", userId, exception.toString());
            }
            String model = streamed == null ? null : streamed.model();
            String full = buffer.toString();
            int markerIndex = full.indexOf(NpcReplyParser.MARKER);
            String rawText = markerIndex >= 0 ? full.substring(0, markerIndex) : full;
            if (sent[0] < rawText.length()) {
                emitter.send(SseEmitter.event().name("delta").data(new StreamDelta(rawText.substring(sent[0]))));
            }
            NpcReplyParser.Parsed parsed = NpcReplyParser.parse(full, schedules);
            SafetyService.SafetyDecision output = safety.classifyOutput(npc, parsed.text());
            if (!output.allowGeneration()) {
                String boundary = "这句我说得不太合适，换个方式：先挑今天最小的一件事，我陪你开始。";
                transactions.executeWithoutResult(status -> insertMessage(id, userId, npc, "ASSISTANT", boundary, output.level(), model, null, null, "BLOCKED"));
                emitter.send(SseEmitter.event().name("safety").data(new SafetyNotice(output.level().name(), boundary)));
                emitter.send(SseEmitter.event().name("done").data(new StreamDone(id, "BLOCKED", List.of(), List.of())));
                emitter.complete();
                return;
            }
            transactions.executeWithoutResult(status -> insertMessage(
                id, userId, npc, "ASSISTANT", parsed.text(), output.level(), model,
                json(parsed.options()), json(parsed.actions()), "COMPLETED"
            ));
            emitter.send(SseEmitter.event().name("done").data(new StreamDone(id, "COMPLETED", parsed.options(), parsed.actions())));
            emitter.complete();
            rememberIfDue(userId, npc);
        } catch (IOException exception) {
            emitter.completeWithError(exception);
        } catch (StreamClosed exception) {
            emitter.completeWithError(exception.getCause());
        } catch (RuntimeException exception) {
            log.warn("town npc stream failed for user {}: {}", userId, exception.toString());
            try {
                emitter.send(SseEmitter.event().name("error").data(new StreamError(code(exception), "小镇里的信号不太好，稍后再来找我吧。")));
                emitter.complete();
            } catch (IOException ignored) {
                emitter.completeWithError(exception);
            }
        }
    }

    private String systemPrompt(
        TownService.UserRow user, ZoneId zone, String npc, List<TownService.ScheduleItem> schedules, String promiseToAsk
    ) {
        long userId = user.id();
        boolean guide = TownPersonas.GUIDE.equals(npc);
        Instant instant = clock.instant();
        LocalDate today = instant.atZone(zone).toLocalDate();
        LocalTime now = instant.atZone(zone).toLocalTime();
        List<MessageView> history = messages(userId, npc);
        // A person doesn't re-announce where they are and how they feel on every reply —
        // only when the conversation is actually starting (see TownNpcCadence for the rule).
        boolean freshMeeting = TownNpcCadence.isFreshMeeting(history, zone, instant);
        StringBuilder prompt = new StringBuilder(TownPersonas.persona(npc));

        // The character's own day comes first and is what it should actually open with the
        // first time today — an NPC that leads with "what are you up to today" every single
        // turn reads as an assistant, not a resident who already said hello once.
        TownNpcDailyState day = TownNpcDailyState.forMoment(today, now, npc);
        prompt.append("\n【你现在的状态（背景信息，不代表要主动播报）】\n");
        prompt.append("你在").append(day.whereabouts()).append("，").append(day.mood()).append("。")
            .append(day.situation()).append("。\n");
        prompt.append("现在是 ").append(clock.instant().atZone(zone).format(DateTimeFormatter.ofPattern("MM-dd HH:mm"))).append("。\n");
        boolean busy = TownNpcDailyState.busyNow(npc, now);
        if (freshMeeting) {
            if (busy) {
                prompt.append(guide ? "这会儿是你手头忙的时候（招呼人、整理东西），" : "这会儿是你送信的时段，")
                    .append("可以直说现在不太方便细聊、晚点再约，简短回应就好，但仍然要按下面的格式收尾。\n");
            }
            prompt.append("这是今天第一次开口，开口先说说你自己的处境，不要一上来就问对方今天准备做什么。\n");
        } else {
            if (busy) {
                prompt.append("你还在忙碌的时段里，回复可以简短一点，但不用再提醒对方你在忙。\n");
            }
            prompt.append("你已经跟对方打过招呼了，不要再复述你今天的状态或处境，直接接着上文往下说。\n");
        }

        // Everything below is what this character could plausibly know about the resident —
        // a coarse impression and what the resident told it directly, never a task-by-task readout.
        prompt.append("【你对这位居民的印象】\n");
        prompt.append("称呼：").append(user.displayName()).append('\n');
        TownFacts facts = TownFacts.collect(jdbc, userId, zone, clock);
        prompt.append(TownNpcPerception.levelBucket(facts.level())).append("，")
            .append(TownNpcPerception.frequencyBucket(facts.completedLast7Days())).append("。\n");
        prompt.append(guide
            ? TownNpcPerception.guidePresenceLine(presentAtAcademyToday(schedules))
            : TownNpcPerception.postmanSignalLine(town.unreadCount(userId))
        ).append('\n');

        TownReflectionService.ReflectionView reflection = reflections.latest(userId);
        if (reflection != null) {
            if (guide) {
                prompt.append("【你昨晚的观察】\n");
                for (TownReflectionService.Insight insight : reflection.insights()) {
                    prompt.append("- ").append(insight.text()).append('\n');
                }
            } else {
                // The postman didn't observe this itself — it only relays what 小助 told it,
                // and must talk about it that way instead of claiming it as its own read.
                prompt.append("【小助跟你说的】\n");
                for (TownReflectionService.Insight insight : reflection.insights()) {
                    prompt.append("- 小助说：").append(insight.text()).append('\n');
                }
                prompt.append("这是小助转述给你的，你只是带个话，别说成是你自己看出来的。\n");
            }
        }
        String memory = memorySummary(userId, npc);
        if (memory != null && !memory.isBlank()) {
            prompt.append("【你记得的往事】\n").append(memory).append('\n');
        }
        if (promiseToAsk != null) {
            prompt.append("【追问】\n对方上次跟你说过：「").append(promiseToAsk)
                .append("」。自然地问一句后来怎么样了，随口一提就行，别用列表，也只问这一次。\n");
        }
        if (!history.isEmpty()) {
            prompt.append("【最近的对话】\n");
            for (MessageView item : history.subList(Math.max(0, history.size() - HISTORY_WINDOW), history.size())) {
                prompt.append("USER".equals(item.role()) ? user.displayName() : TownPersonas.displayName(npc))
                    .append("：").append(item.content()).append('\n');
            }
        }

        // The schedule is real data the model needs to pick a valid action — but it is not
        // material for the spoken reply, so the boundary is stated right where the data sits.
        prompt.append("【今天的任务清单——只用来判断下面 §§ 里的 action，正文里绝不能提这些任务的标题或时间，除非对方自己先说起】\n");
        if (schedules.isEmpty()) {
            prompt.append("（今天没有安排任务）\n");
        }
        for (TownService.ScheduleItem item : schedules) {
            prompt.append("- scheduleId=").append(item.publicId()).append(" 「").append(item.title()).append("」 ")
                .append(item.plannedStartAt().atZone(zone).format(TIME)).append(" ")
                .append(item.estimatedMinutes()).append(" 分钟 状态=").append(item.status()).append('\n');
        }
        prompt.append("""
            【回复格式】
            先用不超过三句口语回复，像面对面说话，不要列表、不要标题、不要引用上面的标签，不要复述任务清单里的标题或时间。
            然后必须另起一行，以 §§ 开头输出一个 JSON 对象作为结尾，这一行任何情况下都不能省略：
            {"options":[{"label":"用户可能想接着说的话，最多 3 条，每条不超过 12 个字"}],"actions":[{"type":"动作类型","scheduleId":"来自今天任务列表","label":"按钮文字"}]}
            options 至少给 1 条、最多 3 条，写成用户会说的口气。
            actions 最多 2 条，只能选：START_TASK（状态为 PLANNED 的任务）、COMPLETE_TASK、DEFER_TASK、SKIP_TASK（状态为 PLANNED 或 IN_PROGRESS）、OPEN_TODAY、OPEN_GOALS、OPEN_AI、OPEN_FRIENDS（这四个不带 scheduleId）。
            没有合适的动作就给 "actions":[]，但 §§ 这一行仍然要写。不要在 §§ 之前提到这些动作类型。
            例如：
            那就先看五分钟，看不下去就停。
            §§{"options":[{"label":"好，我现在就开始"},{"label":"有点累，想歇会儿"}],"actions":[]}
            """);
        return prompt.toString();
    }

    private static boolean presentAtAcademyToday(List<TownService.ScheduleItem> schedules) {
        return schedules.stream().anyMatch(item ->
            ("STUDENT".equals(item.roleCode()) || "WORKER".equals(item.roleCode()))
                && List.of("DONE", "PARTIAL", "IN_PROGRESS").contains(item.status())
        );
    }

    /**
     * Cheap lexical hook, not a model call — see {@link TownPromiseDetector}. Returns the new
     * row's id (or null if nothing was detected) so the very row just written can be excluded
     * by identity rather than by comparing timestamps: {@code town_npc_promise.made_at} is a
     * {@code DATETIME(3)} column, so it silently floors away everything finer than a
     * millisecond on write. An in-memory {@link Instant} from the same turn still carries its
     * sub-millisecond remainder, so "made_at < thisTurnsInstant" is true almost every time —
     * which is exactly how a promise once got asked about in the same breath it was made.
     */
    private Long recordPromiseIfAny(long userId, String npc, String message, Instant madeAt) {
        Optional<String> content = TownPromiseDetector.detect(message);
        if (content.isEmpty()) {
            return null;
        }
        KeyHolder key = new GeneratedKeyHolder();
        transactions.executeWithoutResult(status -> jdbc.update(connection -> {
            PreparedStatement statement = connection.prepareStatement(
                "insert into town_npc_promise (user_id, npc_code, content, made_at) values (?, ?, ?, ?)",
                Statement.RETURN_GENERATED_KEYS
            );
            statement.setLong(1, userId);
            statement.setString(2, npc);
            statement.setString(3, content.get());
            statement.setTimestamp(4, Timestamp.from(madeAt));
            return statement;
        }, key));
        return key.getKey().longValue();
    }

    /**
     * The oldest not-yet-surfaced promise that's fair game to ask about right now. The
     * same-turn row is ruled out by id, not by comparing timestamps — see
     * {@link TownPromiseWindow} for why a timestamp comparison alone can't be trusted here.
     * The eligibility decision itself (id + minimum gap) is deliberately plain Java, not SQL,
     * so it stays covered by a fast unit test instead of depending on one database's rounding.
     */
    private String pendingPromiseToAsk(long userId, String npc, Long justRecordedId, Instant now) {
        long excludeId = justRecordedId == null ? -1 : justRecordedId;
        List<PromiseRow> unasked = jdbc.query(
            """
                select id, content, made_at from town_npc_promise
                where user_id = ? and npc_code = ? and asked_at is null
                order by made_at limit 5
                """,
            (rs, row) -> new PromiseRow(rs.getLong("id"), rs.getString("content"), rs.getTimestamp("made_at").toInstant()),
            userId, npc
        );
        PromiseRow chosen = unasked.stream()
            .filter(row -> TownPromiseWindow.canSurface(row.id(), excludeId, row.madeAt(), now))
            .findFirst()
            .orElse(null);
        if (chosen == null) {
            return null;
        }
        transactions.executeWithoutResult(status -> jdbc.update(
            "update town_npc_promise set asked_at = ? where id = ?", Timestamp.from(clock.instant()), chosen.id()
        ));
        return chosen.content();
    }

    private record PromiseRow(long id, String content, Instant madeAt) {
    }

    private void rememberIfDue(long userId, String npc) {
        try {
            Integer count = jdbc.query(
                "select message_count from town_npc_memory where user_id = ? and npc_code = ?",
                rs -> rs.next() ? rs.getInt(1) : null, userId, npc
            );
            int next = (count == null ? 0 : count) + 2;
            boolean memoryEnabled = Boolean.TRUE.equals(jdbc.query(
                "select ai_memory_enabled from user_preference where user_id = ?",
                rs -> rs.next() ? rs.getBoolean(1) : Boolean.TRUE, userId
            ));
            String summary = null;
            if (memoryEnabled && next % SUMMARY_EVERY == 0) {
                summary = summarize(userId, npc);
            }
            final String finalSummary = summary;
            transactions.executeWithoutResult(status -> jdbc.update(
                """
                    insert into town_npc_memory (user_id, npc_code, summary, message_count, updated_at)
                    values (?, ?, coalesce(?, ''), ?, ?)
                    on duplicate key update message_count = values(message_count),
                        summary = coalesce(values(summary), summary), updated_at = values(updated_at)
                    """,
                userId, npc, finalSummary, next, Timestamp.from(clock.instant())
            ));
        } catch (RuntimeException exception) {
            log.warn("town memory update skipped for user {}: {}", userId, exception.toString());
        }
    }

    private String summarize(long userId, String npc) {
        StringBuilder instruction = new StringBuilder("把下面这段居民和 ")
            .append(TownPersonas.displayName(npc))
            .append(" 的对话，连同已有的记忆摘要，压缩成一段不超过 200 字的中文记忆。只保留对未来对话有用的事实：目标、习惯、偏好、正在担心的事、约定过的事。不要评价。\n");
        String existing = memorySummary(userId, npc);
        if (existing != null && !existing.isBlank()) {
            instruction.append("已有记忆：").append(existing).append('\n');
        }
        for (MessageView item : messages(userId, npc)) {
            instruction.append(item.role()).append("：").append(item.content()).append('\n');
        }
        QwenProvider.StructuredResult result = provider.generateStructured(new QwenProvider.StructuredPrompt(
            "TOWN_MEMORY", instruction.toString(),
            "{\"type\":\"object\",\"required\":[\"summary\"],\"properties\":{\"summary\":{\"type\":\"string\"}}}"
        ));
        try {
            return JSON.readTree(result.json()).path("summary").asText("");
        } catch (JsonProcessingException exception) {
            return null;
        }
    }

    private String memorySummary(long userId, String npc) {
        return jdbc.query(
            "select summary from town_npc_memory where user_id = ? and npc_code = ?",
            rs -> rs.next() ? rs.getString(1) : null, userId, npc
        );
    }

    private void reserveQuota(long userId, LocalDate localDate) {
        transactions.executeWithoutResult(status -> {
            jdbc.update(
                """
                    insert into town_quota (user_id, local_date, chat_count) values (?, ?, 1)
                    on duplicate key update chat_count = chat_count + 1
                    """,
                userId, Date.valueOf(localDate)
            );
            Integer count = jdbc.queryForObject(
                "select chat_count from town_quota where user_id = ? and local_date = ?",
                Integer.class, userId, Date.valueOf(localDate)
            );
            if (count != null && count > DAILY_CHAT_LIMIT) {
                throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "TOWN_CHAT_LIMIT", "今天聊得够多了，明天再来找我吧");
            }
        });
    }

    private String insertMessage(
        long userId, String npc, String role, String content, RiskLevel risk,
        String model, String optionsJson, String actionsJson, String status
    ) {
        return insertMessage(ids.next(), userId, npc, role, content, risk, model, optionsJson, actionsJson, status);
    }

    private String insertMessage(
        String publicId, long userId, String npc, String role, String content, RiskLevel risk,
        String model, String optionsJson, String actionsJson, String status
    ) {
        jdbc.update(
            """
                insert into town_npc_message (
                    public_id, user_id, npc_code, role, content, risk_level, options_json, actions_json, model_name, status, created_at
                ) values (?, ?, ?, ?, ?, ?, cast(? as json), cast(? as json), ?, ?, ?)
                """,
            publicId, userId, npc, role, content, risk.name(), optionsJson, actionsJson, model, status,
            Timestamp.from(clock.instant())
        );
        return publicId;
    }

    private static String requireNpc(String code) {
        String npc = code == null ? "" : code.toUpperCase(Locale.ROOT);
        if (!TownPersonas.exists(npc)) {
            throw new ApiException(HttpStatus.NOT_FOUND, "TOWN_NPC_NOT_FOUND", "镇上没有这个人");
        }
        return npc;
    }

    private static String code(RuntimeException exception) {
        return exception instanceof ApiException api ? api.code() : "TOWN_STREAM_FAILED";
    }

    private static String json(Object value) {
        try {
            return JSON.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            return "[]";
        }
    }

    private static List<NpcReplyParser.Option> readOptions(String json) {
        try {
            return json == null ? List.of() : List.of(JSON.readValue(json, NpcReplyParser.Option[].class));
        } catch (JsonProcessingException exception) {
            return List.of();
        }
    }

    private static List<NpcReplyParser.Action> readActions(String json) {
        try {
            return json == null ? List.of() : List.of(JSON.readValue(json, NpcReplyParser.Action[].class));
        } catch (JsonProcessingException exception) {
            return List.of();
        }
    }

    private static final class StreamClosed extends RuntimeException {
        StreamClosed(IOException cause) {
            super(cause);
        }
    }

    public record ChatCommand(String message) {
    }

    public record MessageView(
        String publicId,
        String role,
        String content,
        List<NpcReplyParser.Option> options,
        List<NpcReplyParser.Action> actions,
        String status,
        Instant createdAt
    ) {
    }

    public record StreamMeta(String npc, String messagePublicId, String model, String riskLevel) {
    }

    public record StreamDelta(String text) {
    }

    public record StreamDone(String messagePublicId, String status, List<NpcReplyParser.Option> options, List<NpcReplyParser.Action> actions) {
    }

    public record SafetyNotice(String riskLevel, String message) {
    }

    public record StreamError(String code, String message) {
    }
}
