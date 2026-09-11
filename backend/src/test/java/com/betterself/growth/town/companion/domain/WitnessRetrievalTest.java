package com.betterself.growth.town.companion.domain;

import com.betterself.growth.town.companion.domain.CompanionWorld.Memory;
import com.betterself.growth.town.companion.domain.CompanionWorld.ResidentState;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link ResidentSimulation#witnessPeople} exists so that a resident's memories can contain material
 * about <b>other people</b> - "who was here, doing what" - dense enough for {@link
 * ResidentSimulation#reflectionSource} to eventually surface a pattern a model can turn into a belief
 * ("阿禾总是坐窗边"). That only matters if the material is actually reachable: {@link
 * ResidentSimulation#reflectionSource} does not return everything a resident has ever recorded - it
 * asks {@link CompanionRecall} for a ranked, capped slice (20 memories, see {@code
 * REFLECTION_SOURCE_LIMIT}), and {@code w.memories} itself is capped at 200 town-wide with layered
 * eviction (raw observation first, belief last - see {@link ResidentSimulation}'s {@code memory()}).
 *
 * <p>Two failure modes this file exists to catch, in order of how bad they are:
 * <ol>
 *   <li><b>Fatal</b>: a who-was-here memory is written but {@link CompanionRecall}'s scoring always
 *   ranks it below the visible cutoff, so it is recorded and then never actually read by anything.
 *   The whole mechanism would then be dead weight - it would cost memory slots and buy nothing.</li>
 *   <li><b>Degraded</b>: who-was-here memories are visible, but there are now so many of them (a
 *   measured rule-only run put 75-88% of a resident's own retained memories under this one topic)
 *   that they push a resident's <em>other</em> raw experience out of {@code reflectionSource}'s
 *   20-item window before a reflection ever gets to look at it - even though the memory itself is
 *   never deleted from {@code w.memories} until the 200-cap actually forces eviction.</li>
 * </ol>
 *
 * <p>What is <b>not</b> at risk, and the third test documents why: {@link CompanionRecall#score}
 * gives {@code belief}/{@code reflection} a whole extra tier of weight (0.30 of the total, at
 * tier=2/2 for a belief) that a fresh, low-importance (2 or 3) who-was-here observation cannot
 * outscore no matter how recent it is. Standing conclusions, once formed, stay visible; it is only
 * the raw, tier-0 material underneath them that a witness flood can crowd out.
 */
class WitnessRetrievalTest {
    private final Instant now = Instant.parse("2026-09-08T06:00:00Z");

    /** Same shape as {@link WitnessPeopleTest}'s own fixture: two residents alone in the garden, one
     * watching the other closely enough (sensitivity 80, the "detail" tier) to write down where they
     * sat. Kept private and separate rather than reused across files, the same way the other domain
     * tests in this package each keep their own small fixture. */
    private CompanionWorld twoInTheGarden() {
        CompanionWorld w = CompanionRules.join("witness-retrieval", "住客", "Asia/Shanghai", now, false);
        w.conversations.forEach(c -> c.status = "ended");
        for (String id : List.of("owner", "gardener")) {
            ResidentState r = ResidentSimulation.state(w, id);
            r.plan = null; r.suspendedAction = null; r.lastSocialAt = now;
            ResidentSimulation.replaceActor(w, id, "garden", "observe", "看看花园", now.plusSeconds(7200));
        }
        ResidentSimulation.state(w, "owner").sensitivity = 80;
        ResidentSimulation.state(w, "gardener").positionId = "garden-plot";
        ResidentSimulation.replaceActor(w, "gardener", "garden", "make", "手上的活", now.plusSeconds(7200));
        // join() already ran a tick before this test says anything - start from a clean slate the
        // same way WitnessPeopleTest does.
        w.memories.removeIf(m -> ResidentSimulation.WITNESS_TOPIC.equals(m.topicId()));
        for (ResidentState r : w.residentStates) { r.lastSeenOfOthers.clear(); r.lastWitnessOfOthersAt.clear(); }
        w.updatedAt = now; w.simulatedAt = now;
        return w;
    }

    private List<Memory> whoWasHere(CompanionWorld w, String owner) {
        return w.memories.stream()
            .filter(m -> m.ownerId().equals(owner) && ResidentSimulation.WITNESS_TOPIC.equals(m.topicId()))
            .toList();
    }

    /** Only what {@code owner} wrote down about {@code about} - see WitnessPeopleTest's own version
     * of this helper. In the join() fixture the user's own avatar also wanders through the garden on
     * a scripted intent, so a raw "topicId equals who-was-here" filter alone would pick up an
     * incidental sighting of "self" that has nothing to do with what this test is driving. */
    private List<Memory> whoWasHere(CompanionWorld w, String owner, String about) {
        return whoWasHere(w, owner).stream().filter(m -> m.sourceId().equals(about)).toList();
    }

    @Test
    @DisplayName("真实路径写下的一条 who-was-here 记忆，确实出现在 reflectionSource 里——不是记下来就没人看得见")
    void aRealWitnessSightingIsRetrievableThroughReflectionSource() {
        CompanionWorld w = twoInTheGarden();
        CompanionRules.advance(w, now.plusSeconds(12));

        List<Memory> sightings = whoWasHere(w, "owner", "gardener");
        assertThat(sightings).as("witnessPeople should have written exactly one sighting").hasSize(1);

        List<Memory> source = ResidentSimulation.reflectionSource(w, "owner", now.plusSeconds(12));
        assertThat(source).extracting(Memory::id).contains(sightings.get(0).id());
    }

    @Test
    @DisplayName("对方走了又回来好几次，每一次都被重新记下，而且全部都还留在 reflectionSource 的可见范围内——" +
        "这正是「反复做同一件事」的材料，缺了任何一条都形不成关于他的稳定看法")
    void repeatedReturnVisitsOfTheSamePersonAllStayVisibleInReflectionSource() {
        CompanionWorld w = twoInTheGarden();
        Instant t = now;
        // Five leave-and-return cycles, mirroring WitnessPeopleTest's
        // recordsHimAgainWhenHeComesBackToTheSameSeat, repeated enough times that the resulting
        // memories cannot all be freshness coincidences.
        for (int i = 0; i < 5; i++) {
            t = t.plusSeconds(12);
            CompanionRules.advance(w, t);
            // Derived from the floor rather than written as a number: the floor moved from 45 minutes
            // to two hours once a rule-only day was measured writing 301 sightings into a world that
            // holds 200 memories, and this cycle silently stopped producing a second sighting.
            t = t.plusSeconds(ResidentSimulation.WITNESS_MIN_GAP_SECONDS);
            ResidentSimulation.replaceActor(w, "gardener", "cafe", "rest", "去坐坐", t.plusSeconds(1800));
            CompanionRules.advance(w, t);
            t = t.plusSeconds(1800);
            ResidentSimulation.replaceActor(w, "gardener", "garden", "make", "手上的活", t.plusSeconds(7200));
        }
        t = t.plusSeconds(12);
        CompanionRules.advance(w, t);

        List<Memory> sightings = whoWasHere(w, "owner", "gardener");
        assertThat(sightings).as("six separate return visits -> six separate sightings").hasSize(6);

        List<Memory> source = ResidentSimulation.reflectionSource(w, "owner", t);
        Set<String> visibleIds = source.stream().map(Memory::id).collect(java.util.stream.Collectors.toCollection(HashSet::new));
        for (Memory sighting : sightings)
            assertThat(visibleIds).as("sighting at " + sighting.at()).contains(sighting.id());
    }

    @Test
    @DisplayName("哪怕同一天里堆了几十条『谁也在』的观察，之前已经形成的 reflection/belief 依然留在可见范围内——" +
        "分层打分保护的是已经沉淀下来的结论，不是被观察淹没的原始素材")
    void aFloodOfWitnessSightingsDoesNotCrowdOutAnExistingReflectionOrBelief() {
        CompanionWorld w = CompanionRules.join("witness-retrieval-flood", "住客", "Asia/Shanghai", now, false);
        w.memories = new java.util.ArrayList<>(w.memories.stream().filter(m -> !m.ownerId().equals("artist")).toList());

        String beliefId = ResidentSimulation.memory(w, "artist", "artist", "belief", now.minusSeconds(6 * 3600),
            "seat", "阿禾好像总是坐窗边。", List.of(), 9, "artist:seat:owner");
        String reflectionId = ResidentSimulation.memory(w, "artist", "artist", "reflection", now.minusSeconds(3 * 3600),
            "life", "今天过得挺充实。", List.of(), 8);

        // Thirty fresh who-was-here sightings, exactly the shape witnessPeople itself writes
        // (topic WITNESS_TOPIC, sourceType "observed", importance 2/3), each about a different
        // person/moment so none collide with the dedupe in memory().
        for (int i = 0; i < 30; i++)
            ResidentSimulation.memory(w, "artist", "student", "observed", now.minusSeconds(i * 120L),
                ResidentSimulation.WITNESS_TOPIC, "小川也在咖啡馆，具体在做什么我没留意。第" + i + "次。", List.of(), 2);

        List<Memory> source = ResidentSimulation.reflectionSource(w, "artist", now);
        assertThat(source).extracting(Memory::id).as("belief must survive a witness flood").contains(beliefId);
        assertThat(source).extracting(Memory::id).as("reflection must survive a witness flood").contains(reflectionId);
    }

    @Test
    @DisplayName("已知代价：当天足够多的新鲜 who-was-here 观察，会把几小时前的普通原始记忆挤出 reflectionSource 的" +
        "可见窗口——记忆本身还在 w.memories 里，只是这次反思看不到了。这是本文件量出来的真实数字，不是猜测")
    void aFloodOfFreshWitnessSightingsCanPushAnOlderPlainObservationOutOfTheVisibleWindow() {
        CompanionWorld w = CompanionRules.join("witness-retrieval-crowd-out", "住客", "Asia/Shanghai", now, false);
        w.memories = new java.util.ArrayList<>(w.memories.stream().filter(m -> !m.ownerId().equals("artist")).toList());

        // Ordinary, non-witness raw experience from three hours ago - the kind of thing a reflection
        // would normally have available to reason from.
        String olderPlainMemoryId = ResidentSimulation.memory(w, "artist", "student", "heard", now.minusSeconds(3 * 3600),
            "life", "小川当面说：“今天复习到很晚。”", List.of(), 6);

        // Twenty who-was-here sightings, all inside the last thirty minutes - exactly what a busy
        // shared space produces in a short stretch once several residents cycle through it.
        for (int i = 0; i < 20; i++)
            ResidentSimulation.memory(w, "artist", "gardener", "observed", now.minusSeconds(i * 90L),
                ResidentSimulation.WITNESS_TOPIC, "青叔也在花园，具体在做什么我没留意。第" + i + "次。", List.of(), 3);

        List<Memory> source = ResidentSimulation.reflectionSource(w, "artist", now);
        assertThat(source).hasSize(20); // the cap itself - see REFLECTION_SOURCE_LIMIT
        assertThat(source).extracting(Memory::id)
            .as("a three-hour-old plain observation loses to twenty fresh witness sightings under the "
                + "current recency/importance/layer weights - recorded here so a future change to "
                + "those weights (or to how often witnessPeople fires) has a concrete number to check "
                + "itself against, not just this comment")
            .doesNotContain(olderPlainMemoryId);
    }
}
