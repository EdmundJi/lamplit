package com.betterself.growth.town.companion.domain;

import com.betterself.growth.town.companion.domain.CompanionWorld.Memory;
import com.betterself.growth.town.companion.domain.CompanionWorld.ResidentState;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Rule-side reflection contract: WHEN a resident is due to think something over
 * ({@link ResidentSimulation#needsReflection}), WHAT material a reflection may look at
 * ({@link ResidentSimulation#reflectionSource}) and HOW a model's conclusion lands
 * ({@link ResidentSimulation#applyReflection}). None of these decide what a resident concludes -
 * that stays a model call outside this module - so every assertion here is about timing, material
 * and validation, never about the content of a thought. */
class ResidentReflectionTest {
    private final Instant now = Instant.parse("2026-09-08T10:00:00Z");

    private CompanionWorld world() {
        return CompanionRules.join("reflection-world", "住客", "Asia/Shanghai", now);
    }

    @Test void needsReflectionIsFalseRightAfterJoiningEvenThoughSeedMemoriesExist() {
        CompanionWorld w = world();
        for (String id : List.of("owner", "student", "artist", "gardener"))
            assertThat(ResidentSimulation.needsReflection(w, id, now)).as(id).isFalse();
    }

    @Test void aShortGapBlocksReflectionNoMatterHowMuchHasHappened() {
        CompanionWorld w = world();
        ResidentState artist = ResidentSimulation.state(w, "artist");
        artist.lastReflectionAt = now.minusSeconds(1000); // under the three-hour minimum gap
        addRawMemory(w, "artist", now.minusSeconds(500), 10);
        addRawMemory(w, "artist", now.minusSeconds(200), 10);
        addRawMemory(w, "artist", now.minusSeconds(100), 10);
        assertThat(ResidentSimulation.needsReflection(w, "artist", now)).isFalse();
    }

    @Test void aLongGapWithoutEnoughNewExperienceStillDoesNotTrigger() {
        CompanionWorld w = world();
        ResidentState artist = ResidentSimulation.state(w, "artist");
        Instant lastReflection = now.minusSeconds(4 * 3600); // well past the minimum gap
        artist.lastReflectionAt = lastReflection;
        // A quiet stretch, deliberately isolated from whatever the warm start already seeded: strip
        // any raw experience already sitting in the window, then add back only a little - nowhere
        // near the importance threshold. This instant is not this resident's usual sleep time either.
        w.memories = new ArrayList<>(w.memories.stream()
            .filter(m -> !(m.ownerId().equals("artist") && m.at().isAfter(lastReflection))).toList());
        addRawMemory(w, "artist", now.minusSeconds(3600), 4);
        assertThat(ResidentSimulation.needsReflection(w, "artist", now)).isFalse();
    }

    @Test void enoughAccumulatedImportanceAfterTheMinimumGapTriggersReflection() {
        CompanionWorld w = world();
        ResidentState artist = ResidentSimulation.state(w, "artist");
        artist.lastReflectionAt = now.minusSeconds(4 * 3600);
        addRawMemory(w, "artist", now.minusSeconds(3600), 9);
        addRawMemory(w, "artist", now.minusSeconds(1800), 9);
        addRawMemory(w, "artist", now.minusSeconds(600), 9); // sum 27 >= threshold
        assertThat(ResidentSimulation.needsReflection(w, "artist", now)).isTrue();
    }

    @Test void anEarlierReflectionOrBeliefNeverCountsAsFreshExperienceTowardTheThreshold() {
        CompanionWorld w = world();
        ResidentState artist = ResidentSimulation.state(w, "artist");
        Instant lastReflection = now.minusSeconds(4 * 3600);
        artist.lastReflectionAt = lastReflection;
        w.memories = new ArrayList<>(w.memories.stream()
            .filter(m -> !(m.ownerId().equals("artist") && m.at().isAfter(lastReflection))).toList());
        // High-importance, but already-processed material: a reflection/belief is the product of
        // thinking, not new raw material for the next round of it.
        addMemory(w, "artist", "reflection", now.minusSeconds(3600), 10);
        addMemory(w, "artist", "belief", now.minusSeconds(1800), 10);
        assertThat(ResidentSimulation.needsReflection(w, "artist", now)).isFalse();
    }

    @Test void reachingOnesOwnUsualSleepTimeTriggersReflectionEvenOnAQuietDay() {
        CompanionWorld w = world();
        ResidentState artist = ResidentSimulation.state(w, "artist");
        ZoneId zone = ZoneId.of(w.timezone);
        Instant yesterdayReflection = ZonedDateTime.of(2026, 9, 7, 20, 0, 0, 0, zone).toInstant();
        artist.lastReflectionAt = yesterdayReflection;
        // Force a genuinely quiet day: strip anything recorded after the last reflection, so the
        // importance threshold cannot be the reason this fires - only the day boundary can be.
        w.memories = new ArrayList<>(w.memories.stream()
            .filter(m -> !(m.ownerId().equals("artist") && m.at().isAfter(yesterdayReflection))).toList());
        // usualSleepMinute is 23:00 by default (see ResidentSimulation.reconcileLife) - land "now"
        // squarely inside that window, on the next day.
        Instant sleepTime = ZonedDateTime.of(2026, 9, 8, 23, 30, 0, 0, zone).toInstant();
        assertThat(ResidentSimulation.needsReflection(w, "artist", sleepTime)).isTrue();
    }

    @Test void needsReflectionNeverFiresForTheAvatar() {
        CompanionWorld w = world();
        ResidentState self = ResidentSimulation.state(w, "self");
        self.lastReflectionAt = now.minusSeconds(4 * 3600);
        assertThat(ResidentSimulation.needsReflection(w, "self", now)).isFalse();
    }

    /** The hard constraint this batch cannot violate: a single 60-second action must never make one
     * resident dominate reflection calls. Run a full simulated day and, every time needsReflection
     * says yes, immediately land a trivial reflection (as a future model-wired caller would) so the
     * gate resets - then assert the whole day stayed in the single digits. */
    @Test void needsReflectionStaysInTheSingleDigitsOverAFullSimulatedDay() {
        CompanionWorld w = world();
        Instant t = now;
        Instant end = now.plusSeconds(24 * 3600);
        int triggers = 0;
        while (t.isBefore(end)) {
            t = t.plusSeconds(60);
            CompanionRules.advance(w, t);
            for (String id : List.of("owner", "student", "artist", "gardener")) {
                if (ResidentSimulation.needsReflection(w, id, t)) {
                    triggers++;
                    ResidentState r = ResidentSimulation.state(w, id);
                    List<String> evidence = w.memories.stream().filter(m -> m.ownerId().equals(id))
                        .sorted((a, b) -> b.at().compareTo(a.at())).limit(1).map(Memory::id).toList();
                    if (!evidence.isEmpty())
                        ResidentSimulation.applyReflection(w, id, r.revision, "今天想了想手上的事。", evidence, null, t);
                }
            }
        }
        assertThat(triggers).isLessThan(10);
    }

    @Test void reflectionSourceOnlyEverReturnsThisResidentsOwnMemories() {
        CompanionWorld w = world();
        List<Memory> source = ResidentSimulation.reflectionSource(w, "artist", now);
        assertThat(source).isNotEmpty();
        assertThat(source).allMatch(m -> m.ownerId().equals("artist"));
    }

    @Test void reflectionSourceExcludesASupersededMemory() {
        CompanionWorld w = world();
        ResidentState artist = ResidentSimulation.state(w, "artist");
        long revision = artist.revision;
        String evidenceId = w.memories.stream().filter(m -> m.ownerId().equals("artist")).findFirst().orElseThrow().id();
        assertThat(ResidentSimulation.applyReflection(w, "artist", revision, "阿禾好像喜欢靠窗的位置。",
            List.of(evidenceId), "artist:seat:owner", now)).isTrue();
        String firstBeliefId = w.memories.stream().filter(m -> m.ownerId().equals("artist") && "belief".equals(m.sourceType())).findFirst().orElseThrow().id();
        assertThat(ResidentSimulation.reflectionSource(w, "artist", now)).extracting(Memory::id).contains(firstBeliefId);

        ResidentState artistAfter = ResidentSimulation.state(w, "artist");
        assertThat(ResidentSimulation.applyReflection(w, "artist", artistAfter.revision, "现在更确定阿禾就是喜欢靠窗的位置。",
            List.of(evidenceId), "artist:seat:owner", now.plusSeconds(10))).isTrue();

        assertThat(ResidentSimulation.reflectionSource(w, "artist", now.plusSeconds(10))).extracting(Memory::id).doesNotContain(firstBeliefId);
        // The superseded belief is still on record, just not retrievable.
        assertThat(w.memories.stream().anyMatch(m -> m.id().equals(firstBeliefId) && m.superseded())).isTrue();
    }

    @Test void applyReflectionRejectsAnEvidenceIdThatBelongsToSomeoneElse() {
        CompanionWorld w = world();
        ResidentState artist = ResidentSimulation.state(w, "artist");
        String othersMemory = w.memories.stream().filter(m -> m.ownerId().equals("owner")).findFirst().orElseThrow().id();
        int before = w.memories.size();
        boolean applied = ResidentSimulation.applyReflection(w, "artist", artist.revision, "借用了别人的记忆。",
            List.of(othersMemory), null, now);
        assertThat(applied).isFalse();
        assertThat(w.memories).hasSize(before); // nothing written at all
        assertThat(artist.thought).isNotEqualTo("借用了别人的记忆。");
    }

    @Test void applyReflectionRejectsAStaleResidentRevision() {
        CompanionWorld w = world();
        ResidentState artist = ResidentSimulation.state(w, "artist");
        String ownEvidence = w.memories.stream().filter(m -> m.ownerId().equals("artist")).findFirst().orElseThrow().id();
        int before = w.memories.size();
        boolean applied = ResidentSimulation.applyReflection(w, "artist", artist.revision - 1, "过期的结论。",
            List.of(ownEvidence), null, now);
        assertThat(applied).isFalse();
        assertThat(w.memories).hasSize(before);
    }

    @Test void applyReflectionRejectsAnEmptyEvidenceList() {
        CompanionWorld w = world();
        ResidentState artist = ResidentSimulation.state(w, "artist");
        int before = w.memories.size();
        assertThat(ResidentSimulation.applyReflection(w, "artist", artist.revision, "凭空的结论。", List.of(), null, now)).isFalse();
        assertThat(w.memories).hasSize(before);
    }

    @Test void aSupersedingBeliefRetiresTheEarlierOneButKeepsItOnDiskForAudit() {
        CompanionWorld w = world();
        ResidentState artist = ResidentSimulation.state(w, "artist");
        String evidenceId = w.memories.stream().filter(m -> m.ownerId().equals("artist")).findFirst().orElseThrow().id();
        String key = "artist:seat-preference:owner";
        assertThat(ResidentSimulation.applyReflection(w, "artist", artist.revision, "阿禾好像喜欢靠门的桌子。", List.of(evidenceId), key, now)).isTrue();
        String firstId = w.memories.stream().filter(m -> m.ownerId().equals("artist") && key.equals(m.supersedesKey())).findFirst().orElseThrow().id();

        ResidentState afterFirst = ResidentSimulation.state(w, "artist");
        assertThat(ResidentSimulation.applyReflection(w, "artist", afterFirst.revision, "后来发现阿禾其实更喜欢窗边。", List.of(evidenceId), key, now.plusSeconds(30))).isTrue();

        Memory first = w.memories.stream().filter(m -> m.id().equals(firstId)).findFirst().orElseThrow();
        assertThat(first.superseded()).isTrue(); // retired, not deleted
        Memory second = w.memories.stream().filter(m -> key.equals(m.supersedesKey()) && !m.id().equals(firstId)).findFirst().orElseThrow();
        assertThat(second.superseded()).isFalse();
        assertThat(second.sourceType()).isEqualTo("belief");
        assertThat(CompanionRecall.retrieve(w.memories, "artist", "", now.plusSeconds(30), 30)).extracting(Memory::id).doesNotContain(firstId);
    }

    // Eviction order (see Memory's own doc comment and ResidentSimulation.memory()): once a
    // resident's memory fills up, a raw observation is the first thing to go, never a belief - the
    // most durable layer is protected until nothing lower-tier is left to trim.
    @Test void fillingMemoryToCapacityEvictsRawObservationsBeforeEverTouchingABelief() {
        CompanionWorld w = world();
        w.memories = new ArrayList<>();
        String beliefId = ResidentSimulation.memory(w, "artist", "artist", "belief", now.minusSeconds(100000), "seat",
            "阿禾喜欢窗边的位置", List.of(), 9);
        for (int i = 0; i < 200; i++)
            ResidentSimulation.memory(w, "artist", "artist", "observed", now.minusSeconds(200 - i), "seat", "第" + i + "次路过", List.of(), 5);

        assertThat(w.memories).hasSize(200); // capped, the belief survived the trim
        assertThat(w.memories.stream().anyMatch(m -> m.id().equals(beliefId))).isTrue();
        assertThat(w.memories.stream().filter(m -> "belief".equals(m.sourceType())).count()).isEqualTo(1);
    }

    private static void addRawMemory(CompanionWorld w, String owner, Instant at, int importance) {
        addMemory(w, owner, "observed", at, importance);
    }

    private static void addMemory(CompanionWorld w, String owner, String type, Instant at, int importance) {
        List<Memory> memories = new ArrayList<>(w.memories);
        memories.add(new Memory("test-" + memories.size() + "-" + at.toEpochMilli(), owner, owner, type, at, "测试用记忆", null, List.of(), importance));
        w.memories = memories;
    }

    @Test void aConclusionGroundedInAPlainObservationWithNoTopicDoesNotCrash() {
        // Stream.findFirst() throws on a null element, so `map(Memory::topicId).findFirst()` blew up
        // on any evidence memory without a topic - which is what an ordinary observation looks like.
        // The whole reflection path died there, and nothing in the suite happened to hit it because
        // every existing fixture used seeded, topic-carrying memories.
        CompanionWorld w = CompanionRules.join("reflect-null-topic", "住客", "Asia/Shanghai", now, true);
        var owner = ResidentSimulation.state(w, "owner");
        String id = ResidentSimulation.memory(w, "owner", "owner", "observed", now, null,
            "看见小川又坐在窗边那个位置。", java.util.List.of(), 5);
        assertThat(w.memories).anyMatch(m -> m.id().equals(id) && m.topicId() == null);
        assertThat(ResidentSimulation.applyReflection(w, "owner", owner.revision,
            "小川好像就是喜欢窗边那个位置。", java.util.List.of(id), "小川-座位", now.plusSeconds(60))).isTrue();
        assertThat(w.memories).anyMatch(m -> m.ownerId().equals("owner") && "belief".equals(m.sourceType()));
    }
}
