package com.betterself.growth.town;

import com.betterself.growth.ai.QwenProvider;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * LLM-backed {@link TownRetellGenerator}: one batched {@code generateStructured} call per up to
 * {@value #BATCH_SIZE} requests, scene {@code TOWN_RETELL}. Every parsed line is validated
 * against the same hard rules the model was asked to follow, and any line that fails —
 * mismatched or missing key, an Arabic numeral, over 40 characters, or blank — falls back to
 * {@link TemplateRetellGenerator} for that one item. Any exception anywhere in the batch
 * (provider failure, malformed JSON, ...) falls the *whole* batch back to the template and logs
 * at warn — this method must never throw and must never return fewer results than requested.
 */
@Primary
@Component
public class QwenRetellGenerator implements TownRetellGenerator {

    private static final Logger log = LoggerFactory.getLogger(QwenRetellGenerator.class);
    private static final int BATCH_SIZE = 20;
    private static final int MAX_CHARS = 40;
    private static final Pattern DIGIT = Pattern.compile("\\d");
    private static final String SCHEMA = """
        {"type":"object","properties":{"items":{"type":"array","items":{"type":"object",\
        "properties":{"key":{"type":"string"},"text":{"type":"string"}},"required":["key","text"]}}},\
        "required":["items"]}
        """;

    private final QwenProvider provider;
    private final TemplateRetellGenerator fallback;
    private final ObjectMapper objectMapper;

    public QwenRetellGenerator(QwenProvider provider, TemplateRetellGenerator fallback, ObjectMapper objectMapper) {
        this.provider = provider;
        this.fallback = fallback;
        this.objectMapper = objectMapper;
    }

    @Override
    public List<Retold> retell(List<Request> requests) {
        if (requests.isEmpty()) {
            return List.of();
        }
        List<Retold> results = new ArrayList<>(requests.size());
        for (int start = 0; start < requests.size(); start += BATCH_SIZE) {
            List<Request> batch = requests.subList(start, Math.min(start + BATCH_SIZE, requests.size()));
            results.addAll(retellBatch(batch));
        }
        return results;
    }

    private List<Retold> retellBatch(List<Request> batch) {
        Map<String, String> templateByKey = new LinkedHashMap<>();
        for (Retold retold : fallback.retell(batch)) {
            templateByKey.put(retold.key(), retold.text());
        }
        try {
            QwenProvider.StructuredResult result = provider.generateStructured(
                new QwenProvider.StructuredPrompt("TOWN_RETELL", instruction(batch), SCHEMA)
            );
            Map<String, String> modelTextByKey = parse(result.json());
            List<Retold> out = new ArrayList<>(batch.size());
            for (Request request : batch) {
                String modelText = modelTextByKey.get(request.key());
                String text = isValid(modelText) ? modelText.strip() : templateByKey.get(request.key());
                out.add(new Retold(request.key(), text));
            }
            return out;
        } catch (Exception exception) {
            log.warn("TOWN_RETELL generation failed for a batch of {} item(s); falling back to template", batch.size(), exception);
            List<Retold> out = new ArrayList<>(batch.size());
            for (Request request : batch) {
                out.add(new Retold(request.key(), templateByKey.get(request.key())));
            }
            return out;
        }
    }

    private boolean isValid(String text) {
        if (text == null) {
            return false;
        }
        String stripped = text.strip();
        if (stripped.isEmpty()) {
            return false;
        }
        if (stripped.length() > MAX_CHARS) {
            return false;
        }
        return !DIGIT.matcher(stripped).find();
    }

    private Map<String, String> parse(String json) {
        JsonNode root;
        try {
            root = objectMapper.readTree(json);
        } catch (Exception exception) {
            throw new IllegalStateException("malformed TOWN_RETELL json", exception);
        }
        JsonNode items = root.path("items");
        if (!items.isArray()) {
            throw new IllegalStateException("TOWN_RETELL json missing items array");
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

    private String instruction(List<Request> batch) {
        StringBuilder text = new StringBuilder();
        text.append("你是成长小镇里的信息传播模拟器。下面每一条是一个 NPC 正把一件事转述给别人听，")
            .append("请分别写出这个 NPC 此刻会说的那一句话，只返回 schema 要求的 JSON，不要 markdown、不要多余文字。\n");
        text.append("硬性规则：输出绝不能出现阿拉伯数字、任务标题或具体时刻；hops 越大就要越模糊、越夸张；")
            .append("每条不超过 40 个汉字；要像口语，不要书面语；items 里的 key 必须和下面给出的 key 一一对应。\n");
        for (Request request : batch) {
            text.append("- key=").append(request.key())
                .append("；说话人=").append(request.speakerName())
                .append("；人设=").append(oneLine(request.speakerPersona()))
                .append("；怪癖=").append(request.quirks() == null || request.quirks().isEmpty()
                    ? "无" : String.join("、", request.quirks()))
                .append("；hops=").append(request.hops())
                .append("；上一手听到的话=「").append(oneLine(request.previousText())).append("」\n");
        }
        return text.toString();
    }

    private String oneLine(String value) {
        return value == null ? "" : value.strip().replaceAll("\\s+", " ");
    }
}
