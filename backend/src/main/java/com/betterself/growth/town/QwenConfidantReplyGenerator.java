package com.betterself.growth.town;

import com.betterself.growth.ai.QwenProvider;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * LLM 版树洞回信，仿 {@link QwenRetellGenerator} 的写法：一次 {@code generateStructured} 调用
 * 批量生成，scene {@code TOWN_CONFIDANT}，任何异常/解析失败/校验不过都逐条或整批回退到
 * {@link TemplateConfidantReplyGenerator}——必须有模板兜底，且这个方法本身绝不能抛异常。
 *
 * <p>prompt 里只放 {@link TownConfidantReplyGenerator.Request#message()}：树洞对玩家一无所知，
 * 这条边界不是靠模型自觉，是靠调用方从一开始就没把别的东西塞进 instruction 里。
 */
@Primary
@Component
public class QwenConfidantReplyGenerator implements TownConfidantReplyGenerator {

    private static final Logger log = LoggerFactory.getLogger(QwenConfidantReplyGenerator.class);
    private static final int BATCH_SIZE = 20;
    private static final int FAILURES_BEFORE_GIVING_UP = 2;
    /** 和 {@link QwenRetellGenerator} 同一个道理：网关可能每次都成功但每次都慢，必须有总时长预算。 */
    private static final Duration PROVIDER_BUDGET = Duration.ofSeconds(60);
    private static final int MAX_CHARS = 120;
    private static final Pattern DIGIT = Pattern.compile("\\d");
    private static final String SCHEMA = """
        {"type":"object","properties":{"items":{"type":"array","items":{"type":"object",\
        "properties":{"key":{"type":"string"},"text":{"type":"string"}},"required":["key","text"]}}},\
        "required":["items"]}
        """;

    private final QwenProvider provider;
    private final TemplateConfidantReplyGenerator fallback;
    private final ObjectMapper objectMapper;

    public QwenConfidantReplyGenerator(QwenProvider provider, TemplateConfidantReplyGenerator fallback,
                                       ObjectMapper objectMapper) {
        this.provider = provider;
        this.fallback = fallback;
        this.objectMapper = objectMapper;
    }

    @Override
    public List<Reply> reply(List<Request> requests) {
        if (requests.isEmpty()) {
            return List.of();
        }
        List<Reply> results = new ArrayList<>(requests.size());
        int consecutiveFailures = 0;
        long deadline = System.nanoTime() + PROVIDER_BUDGET.toNanos();
        for (int start = 0; start < requests.size(); start += BATCH_SIZE) {
            List<Request> batch = requests.subList(start, Math.min(start + BATCH_SIZE, requests.size()));
            if (consecutiveFailures >= FAILURES_BEFORE_GIVING_UP || System.nanoTime() > deadline) {
                results.addAll(fallback.reply(batch));
                continue;
            }
            BatchOutcome outcome = replyBatch(batch);
            consecutiveFailures = outcome.providerFailed() ? consecutiveFailures + 1 : 0;
            results.addAll(outcome.replies());
        }
        return results;
    }

    private record BatchOutcome(List<Reply> replies, boolean providerFailed) {
    }

    private BatchOutcome replyBatch(List<Request> batch) {
        Map<String, String> templateByKey = new LinkedHashMap<>();
        for (Reply reply : fallback.reply(batch)) {
            templateByKey.put(reply.key(), reply.text());
        }
        try {
            QwenProvider.StructuredResult result = provider.generateStructured(
                new QwenProvider.StructuredPrompt("TOWN_CONFIDANT", instruction(batch), SCHEMA)
            );
            Map<String, String> modelTextByKey = parse(result.json());
            List<Reply> out = new ArrayList<>(batch.size());
            for (Request request : batch) {
                String modelText = modelTextByKey.get(request.key());
                String text = isValid(modelText) ? modelText.strip() : templateByKey.get(request.key());
                out.add(new Reply(request.key(), text));
            }
            return new BatchOutcome(out, false);
        } catch (Exception exception) {
            log.warn("TOWN_CONFIDANT generation failed for a batch of {} item(s); falling back to template",
                batch.size(), exception);
            List<Reply> out = new ArrayList<>(batch.size());
            for (Request request : batch) {
                out.add(new Reply(request.key(), templateByKey.get(request.key())));
            }
            return new BatchOutcome(out, true);
        }
    }

    private boolean isValid(String text) {
        if (text == null) {
            return false;
        }
        String stripped = text.strip();
        if (stripped.isEmpty() || stripped.length() > MAX_CHARS) {
            return false;
        }
        return !DIGIT.matcher(stripped).find();
    }

    private Map<String, String> parse(String json) {
        JsonNode root;
        try {
            root = objectMapper.readTree(json);
        } catch (Exception exception) {
            throw new IllegalStateException("malformed TOWN_CONFIDANT json", exception);
        }
        JsonNode items = root.path("items");
        if (!items.isArray()) {
            throw new IllegalStateException("TOWN_CONFIDANT json missing items array");
        }
        Map<String, String> map = new LinkedHashMap<>();
        for (JsonNode item : items) {
            String key = item.path("key").asText(null);
            if (key == null) {
                continue;
            }
            map.put(key, item.path("text").asText(null));
        }
        return map;
    }

    /**
     * 唯一能进这段 prompt 的玩家相关内容就是 {@code message()} 本身——树洞的「一无所知」是从这里
     * 焊死的，不是指望模型自己克制。
     */
    private String instruction(List<Request> batch) {
        StringBuilder text = new StringBuilder();
        text.append("你是成长小镇里一个从不评判、只会倾听的树洞笔友。你完全不认识写信的人，")
            .append("不知道对方的名字、年龄、目标、任务或任何数据——你能知道的只有这一封信写了什么。\n");
        text.append("请给下面每一条来信写一封简短的回信：只倾听、只回应、只共情，绝不给建议、")
            .append("绝不追问具体细节、绝不出现阿拉伯数字。每条不超过 ").append(MAX_CHARS)
            .append(" 个汉字，语气像手写信一样温和，不要用书面客套话开头。")
            .append("只返回 schema 要求的 JSON，不要 markdown、不要多余文字；")
            .append("items 里的 key 必须和下面给出的 key 一一对应。\n");
        for (Request request : batch) {
            text.append("- key=").append(request.key())
                .append("；来信内容=「").append(oneLine(request.message())).append("」\n");
        }
        return text.toString();
    }

    private String oneLine(String value) {
        return value == null ? "" : value.strip().replaceAll("\\s+", " ");
    }
}
