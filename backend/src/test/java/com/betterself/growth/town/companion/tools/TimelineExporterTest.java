package com.betterself.growth.town.companion.tools;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * docs/06-society.md "座位事件拆成两股": routine seat changes are furniture and must not reach either
 * a human reader (timeline.md) or the norm blind test's quiz - only the handful {@code TownPlaces}
 * itself flagged as noticeable (via {@code noticeReason}) earn a line, and the complete {@code
 * seat_state} record must never reach a human reader at all.
 */
class TimelineExporterTest {

    private static Map<String, Object> seatEvent(String type, String actor, String positionId, String noticeReason, String at) {
        Map<String, Object> extra = new LinkedHashMap<>();
        extra.put("eventType", type);
        extra.put("place", "cafe");
        extra.put("positionId", positionId);
        if (noticeReason != null) extra.put("noticeReason", noticeReason);
        Map<String, Object> e = new LinkedHashMap<>();
        e.put("at", at); e.put("kind", "event"); e.put("actorId", actor);
        e.put("actorName", actor); e.put("text", actor + (type.equals("took_spot") ? " 占了位子。" : " 起身了。"));
        e.put("extra", extra);
        return e;
    }

    private static Map<String, Object> seatState(String actor, String from, String to, String at) {
        Map<String, Object> extra = new LinkedHashMap<>();
        extra.put("eventType", to == null ? "left_spot" : "took_spot");
        extra.put("positionId", to);
        extra.put("from", from);
        Map<String, Object> e = new LinkedHashMap<>();
        e.put("at", at); e.put("kind", "seat_state"); e.put("actorId", actor);
        e.put("actorName", actor); e.put("text", actor + " 从 " + from + " 到 " + to);
        e.put("extra", extra);
        return e;
    }

    @Test
    @DisplayName("例行的坐下/起身——没有 noticeReason——不进规矩盲测的题面")
    void routineSeatChangesAreNotSocialTraces() {
        var quiz = TimelineExporter.buildNormBlindTest(
                List.of(seatEvent("took_spot", "owner", "home-owner-desk", null, "2026-01-01T02:00:00Z")),
                List.of(), "Asia/Shanghai");
        assertThat(quiz.quiz()).doesNotContain("占了位子");
    }

    @Test
    @DisplayName("坐了别人的位置——有 noticeReason——才进题面")
    void noticeableSeatChangesAreSocialTraces() {
        var quiz = TimelineExporter.buildNormBlindTest(
                List.of(seatEvent("took_spot", "owner", "cafe-window-seat", "took_others_spot", "2026-01-01T02:00:00Z")),
                List.of(), "Asia/Shanghai");
        assertThat(quiz.quiz()).contains("占了位子");
    }

    @Test
    @DisplayName("有人来了被挤走——left_spot 带 noticeReason——也进题面")
    void displacedDeparturesAreSocialTraces() {
        var quiz = TimelineExporter.buildNormBlindTest(
                List.of(seatEvent("left_spot", "owner", "cafe-window-seat", "displaced_by_owner", "2026-01-01T02:00:00Z")),
                List.of(), "Asia/Shanghai");
        assertThat(quiz.quiz()).contains("起身了");
    }

    @Test
    @DisplayName("完整座位记录（seat_state）从不进 timeline.md，那是给尺子看的")
    void completeSeatRecordNeverReachesTheMarkdownTimeline(@org.junit.jupiter.api.io.TempDir Path dir) throws IOException {
        List<Map<String, Object>> entries = List.of(
                seatState("owner", null, "cafe-window-2", "2026-01-01T02:00:00Z"),
                seatEvent("took_spot", "owner", "cafe-window-2", null, "2026-01-01T02:00:00Z"));
        Path file = dir.resolve("timeline.md");
        TimelineExporter.writeMarkdown(file, entries, "Asia/Shanghai");
        String text = Files.readString(file, StandardCharsets.UTF_8);
        assertThat(text).doesNotContain("从 null 到 cafe-window-2");
    }
}
