package com.betterself.growth.town.companion.tools;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** What this instrument must refuse to call a norm matters more than what it reports, so most of these
 * tests are about the refusals. See {@link NormDetector}'s class comment for why each gate exists. */
class NormDetectorTest {

    private static final String TZ = "Asia/Shanghai";

    private static Map<String, Object> contribution(String actor, String project, String place, String at) {
        Map<String, Object> extra = new LinkedHashMap<>();
        extra.put("eventType", "contribution");
        extra.put("place", place);
        extra.put("projectId", project);
        Map<String, Object> e = new LinkedHashMap<>();
        e.put("at", at);
        e.put("kind", "event");
        e.put("actorId", actor);
        e.put("actorName", actor);
        e.put("text", actor + " 在 " + project + " 上添了一笔");
        e.put("extra", extra);
        return e;
    }

    /** Day n at the given local time, expressed as the UTC instant the timeline actually stores. */
    private static String at(int day, int hour, int minute) {
        return Instant.parse("2026-01-0" + day + "T00:00:00Z").plusSeconds((hour - 8L) * 3600 + minute * 60L).toString();
    }

    @Test
    @DisplayName("两个人反复落在同一件事上，会被报成一条候选")
    void reportsAPairThatKeepsLandingOnTheSameThing() {
        List<Map<String, Object>> entries = new ArrayList<>();
        for (int day = 1; day <= 3; day++) {
            entries.add(contribution("artist", "quiet-corner", "cafe", at(day, 10, day * 7)));
            entries.add(contribution("fixer", "quiet-corner", "cafe", at(day, 15, day * 11)));
            entries.add(contribution("gardener", "seed-exchange", "garden", at(day, 9, 20)));
            entries.add(contribution("owner", "reading-night", "cafe", at(day, 19, 30)));
        }
        NormDetector.Report report = NormDetector.detect("run", entries, TZ);
        assertThat(report.candidates())
                .anySatisfy(c -> {
                    assertThat(c.dimension()).isEqualTo("pairAffinity");
                    assertThat(c.key()).isEqualTo("artist+fixer");
                    assertThat(c.support()).isEqualTo(6);
                    assertThat(c.days()).isEqualTo(3);
                });
    }

    @Test
    @DisplayName("每天同一分钟、同一批人——报成台钟，不报成规矩")
    void refusesATimerWearingANormsClothes() {
        List<Map<String, Object>> entries = new ArrayList<>();
        for (int day = 1; day <= 3; day++) {
            entries.add(contribution("artist", "quiet-corner", "cafe", at(day, 16, 3)));
            entries.add(contribution("fixer", "quiet-corner", "cafe", at(day, 16, 3)));
        }
        NormDetector.Report report = NormDetector.detect("run", entries, TZ);
        assertThat(report.candidates()).noneMatch(c -> c.key().equals("artist+fixer"));
        assertThat(report.dropped()).anySatisfy(d ->
                assertThat((String) d.get("reason")).contains("台钟"));
    }

    @Test
    @DisplayName("只发生在一天里的，不算规矩")
    void refusesSomethingThatOnlyHappenedOnce() {
        List<Map<String, Object>> entries = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            entries.add(contribution("artist", "quiet-corner", "cafe", at(1, 10 + i, i * 3)));
            entries.add(contribution("fixer", "quiet-corner", "cafe", at(1, 11 + i, i * 5)));
        }
        NormDetector.Report report = NormDetector.detect("run", entries, TZ);
        assertThat(report.candidates()).noneMatch(c -> c.dimension().equals("pairAffinity"));
        assertThat(report.dropped()).anySatisfy(d ->
                assertThat((String) d.get("reason")).contains("一天"));
    }

    @Test
    @DisplayName("一次都没发生的维度，报明确的 0，不是不出现")
    void reportsAnExplicitZeroForADimensionThatNeverFired() {
        NormDetector.Report report = NormDetector.detect("empty", List.of(), TZ);
        assertThat(report.counts()).containsEntry("contributionEvents", 0);
        assertThat(report.counts()).containsEntry("joins", 0);
        assertThat(report.counts()).containsEntry("candidates", 0);
        assertThat(report.candidates()).isEmpty();
    }

    @Test
    @DisplayName("关掉模型也长得出来的，就是我们写的，要减掉")
    void subtractsWhateverTheRuleOnlyControlAlsoGrew() {
        List<Map<String, Object>> entries = new ArrayList<>();
        for (int day = 1; day <= 3; day++) {
            entries.add(contribution("artist", "quiet-corner", "cafe", at(day, 10, day * 7)));
            entries.add(contribution("fixer", "quiet-corner", "cafe", at(day, 15, day * 11)));
            entries.add(contribution("gardener", "seed-exchange", "garden", at(day, 9, 20)));
            entries.add(contribution("owner", "reading-night", "cafe", at(day, 19, 30)));
        }
        NormDetector.Report run = NormDetector.detect("model-on", entries, TZ);
        NormDetector.Report control = NormDetector.detect("rules-only", entries, TZ);
        assertThat(run.candidates()).isNotEmpty();
        assertThat(NormDetector.notWrittenByUs(run, control)).isEmpty();
    }

    @Test
    @DisplayName("两次跑长出同一条，说明是种子干的，不是他们干的")
    void callsItTheSeedWhenBothRunsGrewTheSameThing() {
        List<Map<String, Object>> same = new ArrayList<>();
        for (int day = 1; day <= 3; day++) {
            same.add(contribution("artist", "quiet-corner", "cafe", at(day, 10, day * 7)));
            same.add(contribution("fixer", "quiet-corner", "cafe", at(day, 15, day * 11)));
            same.add(contribution("gardener", "seed-exchange", "garden", at(day, 9, 20)));
            same.add(contribution("owner", "reading-night", "cafe", at(day, 19, 30)));
        }
        NormDetector.Report a = NormDetector.detect("a", same, TZ);
        NormDetector.Report b = NormDetector.detect("b", same, TZ);
        NormDetector.Report control = NormDetector.detect("control", List.of(), TZ);
        assertThat(NormDetector.judge(a, b, control).stage()).isEqualTo(NormDetector.Stage.SAME_IN_BOTH_RUNS);
    }

    @Test
    @DisplayName("两次各长出一条不同的，统计这一半就算过了——但只到「还差有人说得出来」为止")
    void stopsShortOfMetUntilAResidentHasSaidIt() {
        List<Map<String, Object>> runA = new ArrayList<>();
        List<Map<String, Object>> runB = new ArrayList<>();
        for (int day = 1; day <= 3; day++) {
            runA.add(contribution("artist", "quiet-corner", "cafe", at(day, 10, day * 7)));
            runA.add(contribution("fixer", "quiet-corner", "cafe", at(day, 15, day * 11)));
            runA.add(contribution("gardener", "seed-exchange", "garden", at(day, 9, 20)));
            runA.add(contribution("owner", "reading-night", "cafe", at(day, 19, 30)));
            runB.add(contribution("gardener", "seed-exchange", "garden", at(day, 9, day * 5)));
            runB.add(contribution("weaver", "seed-exchange", "garden", at(day, 14, day * 9)));
            runB.add(contribution("artist", "street-colors", "street", at(day, 11, 10)));
            runB.add(contribution("owner", "reading-night", "cafe", at(day, 19, 30)));
        }
        NormDetector.Verdict verdict = NormDetector.judge(
                NormDetector.detect("a", runA, TZ),
                NormDetector.detect("b", runB, TZ),
                NormDetector.detect("control", List.of(), TZ));
        assertThat(verdict.stage()).isEqualTo(NormDetector.Stage.AWAITING_SPOKEN_CHECK);
        assertThat(verdict.stage()).isNotEqualTo(NormDetector.Stage.MET);
    }

    @Test
    @DisplayName("只有盲读的人能把它推到 MET，检测器自己推不动")
    void onlyABlindReaderCanCloseIt() {
        List<Map<String, Object>> runA = new ArrayList<>();
        List<Map<String, Object>> runB = new ArrayList<>();
        for (int day = 1; day <= 3; day++) {
            runA.add(contribution("artist", "quiet-corner", "cafe", at(day, 10, day * 7)));
            runA.add(contribution("fixer", "quiet-corner", "cafe", at(day, 15, day * 11)));
            runA.add(contribution("gardener", "seed-exchange", "garden", at(day, 9, 20)));
            runA.add(contribution("owner", "reading-night", "cafe", at(day, 19, 30)));
            runB.add(contribution("gardener", "seed-exchange", "garden", at(day, 9, day * 5)));
            runB.add(contribution("weaver", "seed-exchange", "garden", at(day, 14, day * 9)));
            runB.add(contribution("artist", "street-colors", "street", at(day, 11, 10)));
            runB.add(contribution("owner", "reading-night", "cafe", at(day, 19, 30)));
        }
        NormDetector.Verdict verdict = NormDetector.judge(
                NormDetector.detect("a", runA, TZ),
                NormDetector.detect("b", runB, TZ),
                NormDetector.detect("control", List.of(), TZ));
        assertThat(verdict.withSpokenBy("fixer", "在这儿干活，谁先开的头谁说了算").stage())
                .isEqualTo(NormDetector.Stage.MET);
    }

    @Test
    @DisplayName("统计还没过就报了一句居民的话，不会因此就算过")
    void aQuoteCannotRescueStatisticsThatDidNotPass() {
        NormDetector.Report empty = NormDetector.detect("empty", List.of(), TZ);
        NormDetector.Verdict verdict = NormDetector.judge(empty, empty, empty);
        assertThat(verdict.stage()).isEqualTo(NormDetector.Stage.NO_NORM);
        assertThat(verdict.withSpokenBy("fixer", "我们这儿都这样").stage())
                .isEqualTo(NormDetector.Stage.NO_NORM);
    }

    @Test
    @DisplayName("谁会走过去搭别人的手，是数得出来的")
    void countsWhoWalksOverToSomeoneElsesWork() {
        List<Map<String, Object>> entries = new ArrayList<>();
        for (int day = 1; day <= 3; day++) {
            entries.add(contribution("gardener", "seed-exchange", "garden", at(day, 8, 0)));
            entries.add(contribution("artist", "seed-exchange", "garden", at(day, 11, day * 13)));
            entries.add(contribution("owner", "reading-night", "cafe", at(day, 18, 0)));
            entries.add(contribution("artist", "reading-night", "cafe", at(day, 20, day * 6)));
        }
        NormDetector.Report report = NormDetector.detect("run", entries, TZ);
        assertThat(report.counts()).containsEntry("joins", 6);
        assertThat(report.candidates()).anySatisfy(c -> {
            assertThat(c.dimension()).isEqualTo("whoJoins");
            assertThat(c.key()).isEqualTo("artist");
            assertThat(c.variants()).isEqualTo(2);
        });
    }

    @Test
    @DisplayName("互惠数得出来，但事件少的时候老实说它不够，而不是拿三件事算个比率")
    void saysReciprocityIsUnderpoweredRatherThanGuessing() {
        List<Map<String, Object>> entries = new ArrayList<>();
        entries.add(contribution("gardener", "seed-exchange", "garden", at(1, 8, 0)));
        entries.add(contribution("artist", "seed-exchange", "garden", at(1, 11, 0)));
        entries.add(contribution("artist", "street-colors", "street", at(2, 9, 0)));
        entries.add(contribution("gardener", "street-colors", "street", at(2, 13, 0)));
        NormDetector.Report report = NormDetector.detect("run", entries, TZ);
        assertThat(report.counts()).containsEntry("helps", 2);
        assertThat(report.candidates()).noneMatch(c -> c.dimension().equals("reciprocity"));
        assertThat(report.dropped()).anySatisfy(d -> {
            assertThat((String) d.get("dimension")).isEqualTo("reciprocity");
            assertThat((String) d.get("reason")).contains("支持事件不足");
        });
    }

    @Test
    @DisplayName("一对人在两次跑里名字顺序不同，减法不能因此就把它当成新发现")
    void doesNotHandBackTheControlsOwnPairAsADiscovery() {
        List<Map<String, Object>> run = new ArrayList<>();
        List<Map<String, Object>> control = new ArrayList<>();
        for (int day = 1; day <= 3; day++) {
            // Same pair, same project - only the order in which the two of them first appear differs,
            // which is all it took to make the subtraction miss and report our own rules back to us.
            run.add(contribution("weaver", "quiet-corner", "cafe", at(day, 10, day * 7)));
            run.add(contribution("fixer", "quiet-corner", "cafe", at(day, 15, day * 11)));
            control.add(contribution("fixer", "quiet-corner", "cafe", at(day, 10, day * 7)));
            control.add(contribution("weaver", "quiet-corner", "cafe", at(day, 15, day * 11)));
            for (List<Map<String, Object>> side : List.of(run, control)) {
                side.add(contribution("gardener", "seed-exchange", "garden", at(day, 9, 20)));
                side.add(contribution("owner", "reading-night", "cafe", at(day, 19, 30)));
            }
        }
        NormDetector.Report r = NormDetector.detect("model-on", run, TZ);
        NormDetector.Report c = NormDetector.detect("rules-only", control, TZ);
        assertThat(r.candidates()).anyMatch(x -> x.dimension().equals("pairAffinity"));
        assertThat(NormDetector.notWrittenByUs(r, c))
                .noneMatch(x -> x.dimension().equals("pairAffinity"));
    }
}
