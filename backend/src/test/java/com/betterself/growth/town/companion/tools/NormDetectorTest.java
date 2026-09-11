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

    /** A world-snapshot.json {@code memories} entry - only the fields {@link NormDetector} looks at. */
    private static Map<String, Object> memory(String ownerId, String supersedesKey, String text) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("ownerId", ownerId);
        m.put("supersedesKey", supersedesKey);
        m.put("text", text);
        return m;
    }

    /** A memory-kind timeline entry - the only shape {@link NormDetector} needs to learn a real name for
     * an actorId, since that pairing is how every exported timeline entry already reads. */
    private static Map<String, Object> named(String actorId, String actorName) {
        Map<String, Object> e = new LinkedHashMap<>();
        e.put("at", at(1, 8, 0));
        e.put("kind", "memory");
        e.put("actorId", actorId);
        e.put("actorName", actorName);
        e.put("text", actorName + " 的一句话");
        return e;
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

    // ---- 信念：材料，不是判决 ------------------------------------------------------------------

    @Test
    @DisplayName("一条信念都没有的时候，四个信念数都要是明确的 0，而不是干脆不出现")
    void reportsExplicitZerosWhenThereAreNoBeliefs() {
        NormDetector.Report report = NormDetector.detect("empty", List.of(), List.of(), TZ);
        assertThat(report.counts()).containsEntry("beliefs", 0);
        assertThat(report.counts()).containsEntry("beliefsAboutOthers", 0);
        assertThat(report.counts()).containsEntry("beliefHolders", 0);
        assertThat(report.counts()).containsEntry("sharedBeliefKeys", 0);
        assertThat(report.beliefs()).isEmpty();
        assertThat(NormDetector.markdown(report)).contains("没有。**这是一个真实的 0**，不是没找。");
    }

    @Test
    @DisplayName("老的三参数重载不受影响——照样能跑，只是没有信念材料")
    void theThreeArgOverloadStillWorksWithNoBeliefMaterial() {
        NormDetector.Report report = NormDetector.detect("run", List.of(), TZ);
        assertThat(report.counts()).containsEntry("beliefs", 0);
        assertThat(report.beliefs()).isEmpty();
    }

    @Test
    @DisplayName("只有 supersedesKey 非空的记忆才算信念，普通记忆不算")
    void onlyMemoriesWithASupersedesKeyCountAsBeliefs() {
        List<Map<String, Object>> memories = new ArrayList<>();
        memories.add(memory("student", null, "普通的一条记忆，没有信念"));
        memories.add(memory("fixer", "", "supersedesKey 是空字符串，也不算"));
        memories.add(memory("artist", "habit:artist:paint", "受托工作一结束就找地方坐下研读资料"));
        NormDetector.Report report = NormDetector.detect("run", List.of(), memories, TZ);
        assertThat(report.counts()).containsEntry("beliefs", 1);
        assertThat(report.beliefs()).hasSize(1);
        assertThat(report.beliefs().get(0)).containsEntry("ownerId", "artist");
    }

    @Test
    @DisplayName("信念材料原样带出 ownerId、supersedesKey、text，供盲读的人直接看")
    void carriesBeliefMaterialVerbatim() {
        List<Map<String, Object>> memories = List.of(memory("gardener", "青叔-独自扛", "一个人慢慢弄，省得麻烦别人"));
        NormDetector.Report report = NormDetector.detect("run", List.of(), memories, TZ);
        assertThat(report.beliefs()).containsExactly(Map.of(
                "ownerId", "gardener", "supersedesKey", "青叔-独自扛", "text", "一个人慢慢弄，省得麻烦别人"));
        assertThat(NormDetector.markdown(report)).contains("青叔-独自扛").contains("一个人慢慢弄，省得麻烦别人");
    }

    @Test
    @DisplayName("有几个不同的居民写过信念，beliefHolders 就数几个——同一个人写三条也只算一个")
    void countsDistinctBeliefHolders() {
        List<Map<String, Object>> memories = new ArrayList<>();
        memories.add(memory("student", "habit:student:quiet", "第一条"));
        memories.add(memory("student", "habit:student:quiet", "同一个人后来又写了一条"));
        memories.add(memory("fixer", "habit:fixer:check", "另一个人写的"));
        NormDetector.Report report = NormDetector.detect("run", List.of(), memories, TZ);
        assertThat(report.counts()).containsEntry("beliefs", 3);
        assertThat(report.counts()).containsEntry("beliefHolders", 2);
    }

    @Test
    @DisplayName("同一个 key 只被一个人反复持有，不算 sharedBeliefKey——得是两个不同的人各自持有")
    void doesNotCountARepeatedKeyFromTheSameOwnerAsShared() {
        List<Map<String, Object>> memories = new ArrayList<>();
        memories.add(memory("student", "habit:student:quiet", "第一次这么写"));
        memories.add(memory("student", "habit:student:quiet", "后来又这么写了一次"));
        NormDetector.Report report = NormDetector.detect("run", List.of(), memories, TZ);
        assertThat(report.counts()).containsEntry("sharedBeliefKeys", 0);
    }

    @Test
    @DisplayName("同一个 key 被两个不同的人各自独立持有，才算一个 sharedBeliefKey")
    void countsAKeyHeldByTwoDifferentOwnersAsShared() {
        List<Map<String, Object>> memories = new ArrayList<>();
        memories.add(memory("student", "habit:quiet-when-interrupted", "被打断就回一句知道了"));
        memories.add(memory("fixer", "habit:quiet-when-interrupted", "小川每次被打断都回一句知道了"));
        memories.add(memory("artist", "habit:artist:paint", "自己的、没人共享的一条"));
        NormDetector.Report report = NormDetector.detect("run", List.of(), memories, TZ);
        assertThat(report.counts()).containsEntry("sharedBeliefKeys", 1);
    }

    @Test
    @DisplayName("supersedesKey 或 text 里提到了别的居民的 id，在没有名字表时按 id 兜底算作关于别人")
    void fallsBackToIdMatchingWhenNoNameTableIsAvailable() {
        List<Map<String, Object>> memories = new ArrayList<>();
        memories.add(memory("student", "小川-观察fixer", "fixer 每次修完东西都要在门口站一会儿"));
        memories.add(memory("fixer", "habit:fixer:check", "自己的手闲不住的毛病"));
        // No entries at all - there is no actorName table to read a real name from, so this can only
        // land via the id fallback, which is the point of the test.
        NormDetector.Report report = NormDetector.detect("run", List.of(), memories, TZ);
        assertThat(report.counts()).containsEntry("beliefs", 2);
        assertThat(report.counts()).containsEntry("beliefsAboutOthers", 1);
    }

    @Test
    @DisplayName("有名字表时按真实姓名判定，比 id 兜底更准——中文文本里从来不会出现英文 id")
    void usesTheRealNameFromTheTimelineWhenOneIsAvailable() {
        List<Map<String, Object>> entries = new ArrayList<>();
        entries.add(named("fixer", "周野"));
        entries.add(named("student", "小川"));
        List<Map<String, Object>> memories = new ArrayList<>();
        memories.add(memory("fixer", "小川-应对冲突", "小川每次被我怼完都回句你翻你的就走了，这反应倒是挺固定"));
        memories.add(memory("student", "habit:student:quiet", "每次被打断，我都只回一句知道了"));
        NormDetector.Report report = NormDetector.detect("run", entries, memories, TZ);
        assertThat(report.counts()).containsEntry("beliefs", 2);
        assertThat(report.counts()).containsEntry("beliefsAboutOthers", 1);
    }

    @Test
    @DisplayName("信念只提到自己（哪怕 supersedesKey 里写的是自己的中文名），beliefsAboutOthers 就该是 0")
    void aBeliefAboutOnesOwnHabitIsNotAboutOthers() {
        List<Map<String, Object>> entries = List.of(named("gardener", "青叔"));
        List<Map<String, Object>> memories = List.of(
                memory("gardener", "青叔-独自扛", "一个人慢慢弄，省得麻烦别人，连累了歇会儿都怕出错"));
        NormDetector.Report report = NormDetector.detect("run", entries, memories, TZ);
        assertThat(report.counts()).containsEntry("beliefs", 1);
        assertThat(report.counts()).containsEntry("beliefsAboutOthers", 0);
    }

    // ---- 占用权 ---------------------------------------------------------------------------------

    private static Map<String, Object> spot(String id, String place, String owner) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id); m.put("place", place); m.put("ownerId", owner);
        return m;
    }

    private static Map<String, Object> took(String actor, String positionId, String place, String at) {
        Map<String, Object> extra = new LinkedHashMap<>();
        extra.put("eventType", "took_spot");
        extra.put("place", place);
        extra.put("positionId", positionId);
        Map<String, Object> e = new LinkedHashMap<>();
        e.put("at", at); e.put("kind", "event"); e.put("actorId", actor);
        e.put("actorName", actor); e.put("text", actor + " 坐下了"); e.put("extra", extra);
        return e;
    }

    private static Map<String, Object> left(String actor, String positionId, String place, String at) {
        Map<String, Object> e = took(actor, positionId, place, at);
        @SuppressWarnings("unchecked")
        Map<String, Object> extra = (Map<String, Object>) e.get("extra");
        extra.put("eventType", "left_spot");
        e.put("text", actor + " 起身了");
        return e;
    }

    /** One owned seat among four, the shape the town actually has in its cafe. */
    private static final List<Map<String, Object>> CAFE = List.of(
            spot("cafe-window-seat", "cafe", "student"),
            spot("cafe-window-2", "cafe", null),
            spot("cafe-window-3", "cafe", null),
            spot("cafe-window-4", "cafe", null));

    @Test
    @DisplayName("别人的位子空着也没人去坐——这条规矩是从「没发生的事」里读出来的")
    void readsThePossessionRuleOutOfWhatDidNotHappen() {
        List<Map<String, Object>> entries = new ArrayList<>();
        for (int day = 1; day <= 3; day++)
            for (String who : List.of("owner", "artist", "fixer", "weaver"))
                entries.add(took(who, "cafe-window-" + (2 + (who.length() % 3)), "cafe", at(day, 10 + who.length() % 6, 5)));

        NormDetector.Report report = NormDetector.detect("run", entries, List.of(), CAFE, TZ);
        assertThat(report.candidates()).anySatisfy(c -> {
            assertThat(c.dimension()).isEqualTo("spotRespect");
            assertThat(c.statement()).contains("绕着走");
        });
    }

    @Test
    @DisplayName("大家照坐不误的时候，这条规矩就不该报出来")
    void staysSilentWhenNobodyActuallyAvoidsIt() {
        List<Map<String, Object>> entries = new ArrayList<>();
        for (int day = 1; day <= 3; day++)
            for (String who : List.of("owner", "artist", "fixer", "weaver"))
                entries.add(took(who, "cafe-window-seat", "cafe", at(day, 10 + who.length() % 6, 5)));

        NormDetector.Report report = NormDetector.detect("run", entries, List.of(), CAFE, TZ);
        assertThat(report.candidates()).noneMatch(c -> c.dimension().equals("spotRespect"));
    }

    @Test
    @DisplayName("屋里没有任何人的位置时，这一次落座什么也说明不了")
    void aRoomWithNobodysSpotInItProvesNothing() {
        List<Map<String, Object>> free = List.of(
                spot("street-bench", "street", null), spot("street-bench-2", "street", null));
        List<Map<String, Object>> entries = new ArrayList<>();
        for (int day = 1; day <= 3; day++)
            entries.add(took("owner", "street-bench", "street", at(day, 10, 0)));

        NormDetector.Report report = NormDetector.detect("run", entries, List.of(), free, TZ);
        assertThat(report.counts()).containsEntry("spotTakesWhereSomeoneElsesWasFree", 0);
        assertThat(report.candidates()).noneMatch(c -> c.dimension().equals("spotRespect"));
    }

    @Test
    @DisplayName("总坐同一个位置，数得出来")
    void countsWhoAlwaysSitsInTheSameSpot() {
        List<Map<String, Object>> entries = new ArrayList<>();
        for (int day = 1; day <= 3; day++) {
            entries.add(took("student", "cafe-window-seat", "cafe", at(day, 9, 10)));
            entries.add(took("student", "cafe-window-seat", "cafe", at(day, 15, 20)));
        }
        NormDetector.Report report = NormDetector.detect("run", entries, List.of(), CAFE, TZ);
        assertThat(report.candidates()).anySatisfy(c -> {
            assertThat(c.dimension()).isEqualTo("ownSpot");
            assertThat(c.key()).isEqualTo("student");
            assertThat(c.support()).isEqualTo(6);
        });
    }

    @Test
    @DisplayName("一条 took_spot 都没有的时候，报明确的 0")
    void reportsAnExplicitZeroWhenNobodyEverSatAnywhere() {
        NormDetector.Report report = NormDetector.detect("run", List.of(), List.of(), CAFE, TZ);
        assertThat(report.counts()).containsEntry("spotTakes", 0);
        assertThat(report.counts()).containsEntry("spotTakesWhereSomeoneElsesWasFree", 0);
    }

    @Test
    @DisplayName("玩家的小人怎么坐都不算规矩——它背后没有人，只有规则")
    void theAvatarsOwnBehaviourIsNeverReadAsANorm() {
        // 我 does exactly what the town's clearest-looking "norm" looks like: sits in the cafe over and
        // over and never once in the seat that belongs to somebody else. There is no mind behind it, so
        // there is nothing here to discover - CompanionRules did all of it.
        List<Map<String, Object>> entries = new ArrayList<>();
        for (int day = 1; day <= 3; day++)
            for (int round = 0; round < 3; round++) {
                entries.add(took("self", "cafe-window-2", "cafe", at(day, 9 + round, 0)));
                entries.add(left("self", "cafe-window-2", "cafe", at(day, 9 + round, 30)));
                entries.add(contribution("self", "garden-beds", "garden", at(day, 14 + round, 0)));
            }

        NormDetector.Report report = NormDetector.detect("run", entries, List.of(), CAFE, TZ);
        assertThat(report.counts()).containsEntry("spotTakes", 0);
        assertThat(report.candidates()).allSatisfy(c -> assertThat(c.key()).doesNotContain("self"));
        assertThat(report.candidates()).noneMatch(c -> c.dimension().equals("spotRespect"));
    }

    @Test
    @DisplayName("座位记录有缺口的时候，这条维度拒答，而不是照着错的回放算一个数出来")
    void declinesThePossessionQuestionWhenTheSeatRecordHasGapsInIt() {
        // 阿禾 moves from one seat to another and the export never says she got up. One gap is enough:
        // from here on the replay believes she is in two places, and every later "that spot was taken"
        // is this instrument's own bookkeeping rather than anything that happened in the town.
        List<Map<String, Object>> entries = new ArrayList<>();
        for (int day = 1; day <= 3; day++)
            for (String who : List.of("owner", "artist", "fixer", "weaver"))
                entries.add(took(who, "cafe-window-" + (2 + (who.length() % 3)), "cafe", at(day, 10, 5)));
        entries.add(took("owner", "cafe-window-3", "cafe", at(3, 11, 0))); // no left_spot for window-4

        NormDetector.Report report = NormDetector.detect("run", entries, List.of(), CAFE, TZ);
        assertThat(report.counts()).containsEntry("seatRecordGaps", 1);
        assertThat(report.candidates()).noneMatch(c -> c.dimension().equals("spotRespect"));
        assertThat(report.dropped()).anySatisfy(d -> {
            assertThat(d).containsEntry("dimension", "spotRespect");
            assertThat(String.valueOf(d.get("reason"))).contains("说不出当时那个位置空不空");
        });
    }

    @Test
    @DisplayName("别人的位子当时有人坐着，绕开它就什么也没证明")
    void aSeatingProvesNothingWhileTheOwnedSpotWasOccupiedAnyway() {
        // This is the shape that produced the round's most convincing false finding: 131 seatings, not
        // one of them in somebody else's spot, chance said 35. The owner was sitting in it the whole time.
        List<Map<String, Object>> entries = new ArrayList<>();
        entries.add(took("student", "cafe-window-seat", "cafe", at(1, 8, 0)));
        for (int day = 1; day <= 3; day++)
            for (String who : List.of("owner", "artist", "fixer", "weaver"))
                entries.add(took(who, "cafe-window-" + (2 + (who.length() % 3)), "cafe", at(day, 10, 5)));

        NormDetector.Report report = NormDetector.detect("run", entries, List.of(), CAFE, TZ);
        assertThat(report.counts()).containsEntry("seatRecordGaps", 0);
        assertThat(report.counts()).containsEntry("spotTakesWhereSomeoneElsesWasFree", 0);
        assertThat(report.candidates()).noneMatch(c -> c.dimension().equals("spotRespect"));
    }

    @Test
    @DisplayName("位子空出来以后再绕开它，才算数")
    void onceTheOwnedSpotIsVacatedAvoidingItCountsAgain() {
        List<Map<String, Object>> entries = new ArrayList<>();
        entries.add(took("student", "cafe-window-seat", "cafe", at(1, 8, 0)));
        entries.add(left("student", "cafe-window-seat", "cafe", at(1, 9, 0)));
        for (int day = 1; day <= 3; day++)
            for (String who : List.of("owner", "artist", "fixer", "weaver"))
                entries.add(took(who, "cafe-window-" + (2 + (who.length() % 3)), "cafe", at(day, 10, 5)));

        NormDetector.Report report = NormDetector.detect("run", entries, List.of(), CAFE, TZ);
        assertThat((Integer) report.counts().get("spotTakesWhereSomeoneElsesWasFree")).isPositive();
        assertThat(report.candidates()).anySatisfy(c -> {
            assertThat(c.dimension()).isEqualTo("spotRespect");
            assertThat(c.statement()).contains("有得选的落座");
        });
    }
}
