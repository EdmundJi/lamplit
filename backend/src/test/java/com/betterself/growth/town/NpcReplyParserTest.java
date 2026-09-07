package com.betterself.growth.town;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class NpcReplyParserTest {

    private static final TownService.ScheduleItem PLANNED = schedule("sched-planned", "PLANNED");
    private static final TownService.ScheduleItem IN_PROGRESS = schedule("sched-progress", "IN_PROGRESS");
    private static final TownService.ScheduleItem DONE = schedule("sched-done", "DONE");

    @Test
    void forwardableLengthHoldsBackNothingWithoutAMarker() {
        assertThat(NpcReplyParser.forwardableLength("先从最小的一步开始")).isEqualTo("先从最小的一步开始".length());
    }

    @Test
    void forwardableLengthHoldsBackATrailingPartialMarker() {
        // "§§" arrives one byte/char at a time; a lone trailing "§" might be the start of the marker.
        String buffer = "先做五分钟就好§";
        assertThat(NpcReplyParser.forwardableLength(buffer)).isEqualTo(buffer.length() - 1);
    }

    @Test
    void forwardableLengthStopsAtTheMarker() {
        String buffer = "先做五分钟就好§§{\"options\":[]}";
        assertThat(NpcReplyParser.forwardableLength(buffer)).isEqualTo(buffer.indexOf("§§"));
    }

    @Test
    void parseReadsOptionsAndValidatesActionsAgainstTodaysSchedules() {
        String full = """
            先开始五分钟，剩下的再说。§§{"options":[{"label":"好，我试试"},{"label":"等会儿再说"}],\
            "actions":[{"type":"START_TASK","scheduleId":"sched-planned","label":"现在开始"}]}""";
        NpcReplyParser.Parsed parsed = NpcReplyParser.parse(full, List.of(PLANNED, IN_PROGRESS, DONE));

        assertThat(parsed.text()).isEqualTo("先开始五分钟，剩下的再说。");
        assertThat(parsed.options()).extracting(NpcReplyParser.Option::label)
            .containsExactly("好，我试试", "等会儿再说");
        assertThat(parsed.actions()).hasSize(1);
        NpcReplyParser.Action action = parsed.actions().get(0);
        assertThat(action.type()).isEqualTo("START_TASK");
        assertThat(action.scheduleId()).isEqualTo("sched-planned");
        assertThat(action.taskTitle()).isEqualTo(PLANNED.title());
    }

    @Test
    void parseFillsInADefaultLabelWhenTheModelOmitsOne() {
        String full = "好呀。§§{\"actions\":[{\"type\":\"COMPLETE_TASK\",\"scheduleId\":\"sched-progress\"}]}";
        NpcReplyParser.Parsed parsed = NpcReplyParser.parse(full, List.of(IN_PROGRESS));

        assertThat(parsed.actions()).hasSize(1);
        assertThat(parsed.actions().get(0).label()).isEqualTo("标记完成：" + IN_PROGRESS.title());
    }

    @Test
    void parseKeepsNavigationActionsWithoutAScheduleId() {
        String full = "去看看今天的安排吧。§§{\"actions\":[{\"type\":\"OPEN_TODAY\"}]}";
        NpcReplyParser.Parsed parsed = NpcReplyParser.parse(full, List.of());

        assertThat(parsed.actions()).hasSize(1);
        NpcReplyParser.Action action = parsed.actions().get(0);
        assertThat(action.type()).isEqualTo("OPEN_TODAY");
        assertThat(action.scheduleId()).isNull();
        assertThat(action.label()).isEqualTo("去今日页");
    }

    @Test
    void parseDropsActionsThatDoNotMatchAKnownSchedule() {
        String full = "好的。§§{\"actions\":[{\"type\":\"START_TASK\",\"scheduleId\":\"does-not-exist\"}]}";
        NpcReplyParser.Parsed parsed = NpcReplyParser.parse(full, List.of(PLANNED));

        assertThat(parsed.actions()).isEmpty();
    }

    @Test
    void parseDropsStartTaskForAScheduleThatIsAlreadyInProgress() {
        String full = "好的。§§{\"actions\":[{\"type\":\"START_TASK\",\"scheduleId\":\"sched-progress\"}]}";
        NpcReplyParser.Parsed parsed = NpcReplyParser.parse(full, List.of(IN_PROGRESS));

        assertThat(parsed.actions()).isEmpty();
    }

    @Test
    void parseAllowsCompleteDeferAndSkipForPlannedOrInProgressButNotDone() {
        for (String type : List.of("COMPLETE_TASK", "DEFER_TASK", "SKIP_TASK")) {
            assertThat(NpcReplyParser.allowed(type, "PLANNED")).isTrue();
            assertThat(NpcReplyParser.allowed(type, "IN_PROGRESS")).isTrue();
            assertThat(NpcReplyParser.allowed(type, "DONE")).isFalse();
        }
        assertThat(NpcReplyParser.allowed("START_TASK", "PLANNED")).isTrue();
        assertThat(NpcReplyParser.allowed("START_TASK", "IN_PROGRESS")).isFalse();
    }

    @Test
    void parseStillDeliversProseWhenTheTailIsMalformed() {
        String full = "先别管标签了，聊聊今天怎么样。§§not even json";
        NpcReplyParser.Parsed parsed = NpcReplyParser.parse(full, List.of(PLANNED));

        assertThat(parsed.text()).isEqualTo("先别管标签了，聊聊今天怎么样。");
        assertThat(parsed.options()).isEmpty();
        assertThat(parsed.actions()).isEmpty();
    }

    @Test
    void parseCapsOptionsAtThreeAndActionsAtTwo() {
        String full = """
            好。§§{"options":[{"label":"一"},{"label":"二"},{"label":"三"},{"label":"四"}],\
            "actions":[{"type":"OPEN_TODAY"},{"type":"OPEN_GOALS"},{"type":"OPEN_AI"}]}""";
        NpcReplyParser.Parsed parsed = NpcReplyParser.parse(full, List.of());

        assertThat(parsed.options()).hasSize(3);
        assertThat(parsed.actions()).hasSize(2);
    }

    @Test
    void parseWithNoMarkerReturnsAllTextAndNoChips() {
        NpcReplyParser.Parsed parsed = NpcReplyParser.parse("就这样，没有标签。", List.of(PLANNED));

        assertThat(parsed.text()).isEqualTo("就这样，没有标签。");
        assertThat(parsed.options()).isEmpty();
        assertThat(parsed.actions()).isEmpty();
    }

    @Test
    void controlIsIndependentAndSuppressesChipsAndBusinessActions() {
        var parsed = NpcReplyParser.parse("我先去送信，晚点聊。§§{\"control\":{\"type\":\"/interrupt\",\"reason\":\"  要去送信  \"},\"options\":[{\"label\":\"继续\"}],\"actions\":[{\"type\":\"OPEN_TODAY\"}]}", List.of());
        assertThat(parsed.control()).isEqualTo(new NpcReplyParser.Control("/interrupt", "要去送信"));
        assertThat(parsed.options()).isEmpty();
        assertThat(parsed.actions()).isEmpty();
        assertThat(parsed.text()).isEqualTo("我先去送信，晚点聊。");
    }

    @Test
    void rejectsInvalidOrMisplacedControls() {
        for (String tail : List.of(
            "{\"control\":null}", "{\"control\":\"/interrupt\"}",
            "{\"control\":{\"type\":\"/leave\",\"reason\":\"送信\"}}",
            "{\"control\":{\"type\":\" /interrupt\",\"reason\":\"送信\"}}",
            "{\"control\":{\"type\":\"/interrupt\"}}",
            "{\"control\":{\"type\":\"/interrupt\",\"reason\":42}}",
            "{\"control\":{\"type\":\"/interrupt\",\"reason\":\"   \"}}",
            "{\"control\":{\"type\":\"/interrupt\",\"reason\":\"" + "字".repeat(201) + "\"}}",
            "{\"actions\":[{\"type\":\"/interrupt\",\"reason\":\"送信\"}]}",
            "{\"nested\":{\"control\":{\"type\":\"/interrupt\",\"reason\":\"送信\"}}}",
            "prefix {\"control\":{\"type\":\"/interrupt\",\"reason\":\"送信\"}}",
            "{\"control\":{\"type\":\"/interrupt\",\"reason\":\"送信\"}} {}",
            "{\"control\":{\"type\":\"/interrupt\",\"reason\":\"送信\"}"
        )) {
            assertThat(NpcReplyParser.parse("正文 /interrupt§§" + tail, List.of()).control()).as(tail).isNull();
        }
        assertThat(NpcReplyParser.parse("正文 /interrupt", List.of()).control()).isNull();
        assertThat(new NpcReplyParser.Parsed("旧构造", List.of(), List.of()).control()).isNull();
        assertThat(NpcReplyParser.parse("§§{\"control\":{\"type\":\"/interrupt\",\"reason\":\"" + "字".repeat(200) + "\"}}", List.of()).control()).isNotNull();
    }

    private static TownService.ScheduleItem schedule(String publicId, String status) {
        Instant start = Instant.parse("2026-09-05T01:00:00Z");
        return new TownService.ScheduleItem(
            publicId, "背单词 20 分钟", status, "STUDENT", "学生",
            start, start.plusSeconds(1200), 20, 2
        );
    }
}
