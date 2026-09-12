package com.betterself.growth.town.companion.tools;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** The社会版盲测 of docs/01-requirements.md 的「怎么验收」. What it must not do matters most: lead the reader. */
class NormBlindTestTest {

    private static Map<String, Object> event(String type, String actor, String name, String text, String at) {
        Map<String, Object> extra = new LinkedHashMap<>();
        extra.put("eventType", type);
        extra.put("place", "cafe");
        Map<String, Object> e = new LinkedHashMap<>();
        e.put("at", at); e.put("kind", "event"); e.put("actorId", actor);
        e.put("actorName", name); e.put("text", text); e.put("extra", extra);
        return e;
    }

    private static Map<String, Object> belief(String owner, String key, String text) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("ownerId", owner); m.put("text", text);
        m.put("supersedesKey", key); m.put("superseded", false);
        return m;
    }

    @Test
    @DisplayName("盲读的人拿到的是发生过的事，不是居民自己的结论")
    void theReaderGetsWhatHappenedAndNeverTheAnswers() {
        var quiz = TimelineExporter.buildNormBlindTest(
                List.of(event("contribution", "artist", "知夏", "知夏在咖啡馆添了一笔。", "2026-01-01T02:00:00Z")),
                List.of(belief("artist", "周野-说定的事", "周野说了会来，就一定会来。")),
                "Asia/Shanghai");
        assertThat(quiz.quiz()).contains("知夏在咖啡馆添了一笔");
        assertThat(quiz.quiz()).as("居民自己得出的结论不能出现在题面上").doesNotContain("周野说了会来");
        assertThat(quiz.key()).contains("周野说了会来");
    }

    @Test
    @DisplayName("「看不出规矩」被明确写成一个真实的答案")
    void sayingThereIsNoRuleIsOfferedAsARealAnswer() {
        var quiz = TimelineExporter.buildNormBlindTest(List.of(), List.of(), "Asia/Shanghai");
        assertThat(quiz.quiz()).contains("\"没有\"是一个真实的答案");
    }

    @Test
    @DisplayName("一条信念都没有时，答案页写明确的 0，而不是一片空白")
    void anEmptyKeyPageSaysSoOutLoud() {
        var quiz = TimelineExporter.buildNormBlindTest(List.of(), List.of(), "Asia/Shanghai");
        assertThat(quiz.key()).contains("这是一个真实的 0");
    }

    @Test
    @DisplayName("心里想的不进题面——一个规矩看不见就不是规矩")
    void innerMonologueIsLeftOut() {
        var quiz = TimelineExporter.buildNormBlindTest(
                List.of(event("thought", "artist", "知夏", "知夏心里想着别的事。", "2026-01-01T02:00:00Z"),
                        event("greeting", "artist", "知夏", "知夏和周野打了个招呼。", "2026-01-01T02:30:00Z")),
                List.of(), "Asia/Shanghai");
        assertThat(quiz.quiz()).doesNotContain("心里想着别的事");
        assertThat(quiz.quiz()).contains("打了个招呼");
    }

    @Test
    @DisplayName("名字保留——这一场问的是人跟人之间的事，去掉名字就什么都看不出来了")
    void namesAreKeptUnlikeThePersonalityBlindTest() {
        var quiz = TimelineExporter.buildNormBlindTest(
                List.of(event("contribution", "gardener", "青叔", "青叔接着知夏起的头往下做。", "2026-01-01T02:00:00Z")),
                List.of(), "Asia/Shanghai");
        assertThat(quiz.quiz()).contains("青叔").contains("知夏");
    }
}
