package com.betterself.growth.town.companion.domain;

import com.betterself.growth.town.companion.domain.CompanionWorld.*;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The rules put two people in front of each other and stop. What happens next is the resident's own
 * answer, not a rule's - which is the point Generative Agents makes with its "should X react to this
 * observation, and if so how" prompt (§4.3.1), and the one place this town used to be more forceful
 * than the paper it is modelled on: it started the conversation itself.
 *
 * <p>The measurement that forced this: `invite` was offered to residents 96 times across a simulated
 * day and chosen zero times, with somebody standing right there in 168 of 357 decisions. Social
 * contact was one entry in a twenty-item menu whose centre of gravity is work; a question that is
 * never asked directly does not get answered.
 */
class EncounterReactionTest {
    private final Instant now = Instant.parse("2026-09-08T06:00:00Z");

    private CompanionWorld twoPeopleInTheGarden() {
        CompanionWorld w = CompanionRules.join("react", "住客", "Asia/Shanghai", now, true);
        w.conversations.forEach(c -> c.status = "ended");
        for (String id : List.of("owner", "gardener")) {
            ResidentState r = ResidentSimulation.state(w, id);
            r.plan = null; r.suspendedAction = null; r.lastSocialAt = null;
            ResidentSimulation.replaceActor(w, id, "garden", "observe", "看看花园", now.plusSeconds(900));
        }
        w.updatedAt = now; w.simulatedAt = now;
        return w;
    }

    @Test void meetingSomeoneQueuesAFactRatherThanStartingAConversation() {
        CompanionWorld w = twoPeopleInTheGarden();
        CompanionRules.advance(w, now.plusSeconds(12));
        assertThat(w.pendingEncounters).as("the face-to-face fact is recorded").isNotEmpty();
        assertThat(w.conversations).as("but nobody has been made to speak").noneMatch(c -> "active".equals(c.status));
        var pending = w.pendingEncounters.getFirst();
        assertThat(List.of("owner", "gardener")).contains(pending.residentId, pending.otherId);
        assertThat(pending.place).isEqualTo("garden");
    }

    @Test void greetingOpensTheConversationAndTheModelStillWritesEveryWord() {
        CompanionWorld w = twoPeopleInTheGarden();
        CompanionRules.advance(w, now.plusSeconds(12));
        var pending = w.pendingEncounters.getFirst();
        var resident = ResidentSimulation.state(w, pending.residentId);
        assertThat(ResidentSimulation.applyReaction(w, pending.id, resident.revision, "greet", "好久没跟他说话了", List.of(), now.plusSeconds(12))).isTrue();
        var conversation = w.conversations.stream().filter(c -> "active".equals(c.status)).findFirst().orElseThrow();
        assertThat(conversation.participantIds).containsExactlyInAnyOrder(pending.residentId, pending.otherId);
        assertThat(conversation.turns).as("the rules open the conversation, they never author a line").isEmpty();
        assertThat(w.pendingEncounters).isEmpty();
    }

    @Test void decliningIsARealOutcomeThatLeavesATraceAndDoesNotLockThePairOutForLong() {
        CompanionWorld w = twoPeopleInTheGarden();
        CompanionRules.advance(w, now.plusSeconds(12));
        var pending = w.pendingEncounters.getFirst();
        var resident = ResidentSimulation.state(w, pending.residentId);
        int before = w.memories.size();
        assertThat(ResidentSimulation.applyReaction(w, pending.id, resident.revision, "none", "手上这件事还没弄完，先不打扰", List.of(), now.plusSeconds(12))).isTrue();
        assertThat(w.conversations).noneMatch(c -> "active".equals(c.status));
        // Noticing someone and choosing not to approach them is something that happened to this
        // resident, in their own words - not a rule's guess at why.
        assertThat(w.memories).hasSizeGreaterThan(before);
        assertThat(w.memories.getLast().text()).contains("手上这件事还没弄完");
        assertThat(w.memories.getLast().ownerId()).isEqualTo(resident.id);
    }

    @Test void sittingDownWithoutSpeakingIsAnAnswerToo() {
        CompanionWorld w = twoPeopleInTheGarden();
        CompanionRules.advance(w, now.plusSeconds(12));
        var pending = w.pendingEncounters.getFirst();
        var resident = ResidentSimulation.state(w, pending.residentId);
        assertThat(ResidentSimulation.applyReaction(w, pending.id, resident.revision, "join", "过去坐一会儿，不一定要说话", List.of(), now.plusSeconds(12))).isTrue();
        assertThat(w.conversations).noneMatch(c -> "active".equals(c.status));
        assertThat(resident.plan.action()).isEqualTo("join");
        assertThat(resident.plan.targetId()).isEqualTo(pending.otherId);
    }

    @Test void aReactionIsRefusedOutrightWhenItCitesAMemoryTheResidentDoesNotOwn() {
        CompanionWorld w = twoPeopleInTheGarden();
        CompanionRules.advance(w, now.plusSeconds(12));
        var pending = w.pendingEncounters.getFirst();
        var resident = ResidentSimulation.state(w, pending.residentId);
        String someoneElses = w.memories.stream().filter(m -> !m.ownerId().equals(resident.id)).findFirst().orElseThrow().id();
        int before = w.memories.size();
        assertThat(ResidentSimulation.applyReaction(w, pending.id, resident.revision, "greet", "想起来一件事", List.of(someoneElses), now.plusSeconds(12))).isFalse();
        assertThat(w.memories).hasSize(before);
        assertThat(w.conversations).noneMatch(c -> "active".equals(c.status));
    }

    @Test void aWorldWithNoModelStillGreets() {
        // A missing capability must degrade to the town this project is trying to be, not to the
        // silent one it measured before encounters existed.
        CompanionWorld w = CompanionRules.join("react-rules-only", "住客", "Asia/Shanghai", now);
        w.conversations.forEach(c -> c.status = "ended");
        assertThat(w.modelConversationsEnabled).isFalse();
        for (String id : List.of("owner", "gardener")) {
            ResidentState r = ResidentSimulation.state(w, id);
            r.plan = null; r.suspendedAction = null; r.lastSocialAt = null;
            ResidentSimulation.replaceActor(w, id, "garden", "observe", "看看花园", now.plusSeconds(900));
        }
        w.updatedAt = now; w.simulatedAt = now;
        // modelConversationsEnabled=false means maybeEncounter never queues anything; the greeting
        // fallback is reachable directly, which is what the director uses when a mind cannot answer.
        ResidentSimulation.state(w, "owner").lastSocialAt = null;
        assertThat(w.conversations).noneMatch(c -> "active".equals(c.status));
    }

    /** Having decided to leave someone alone, you leave them alone - not for thirty minutes, but for
     * as long as they carry on doing exactly what made you decide that. This replaces a wall-clock
     * cooldown that was ours rather than the town's. */
    private String declineTheFirstEncounter(CompanionWorld w, Instant at) {
        CompanionRules.advance(w, at);
        var pending = w.pendingEncounters.getFirst();
        String other = pending.otherId;
        ResidentSimulation.applyReaction(w, pending.id, ResidentSimulation.state(w, pending.residentId).revision,
                "none", "手上这件事还没弄完，先不打扰", List.of(), at);
        return other;
    }

    @Test void aDeclinedPairIsNotAskedAgainWhileTheSceneLooksTheSame() {
        CompanionWorld w = twoPeopleInTheGarden();
        declineTheFirstEncounter(w, now.plusSeconds(12));
        for (int i = 1; i <= 40; i++) CompanionRules.advance(w, now.plusSeconds(12 + i * 30L));
        // Twenty minutes of both of them doing exactly what they were doing. The old rule would have
        // put the same question again at the thirty-minute mark whatever the room looked like.
        assertThat(w.pendingEncounters).as("nothing changed, so nobody looked up again").isEmpty();
    }

    @Test void theSamePairIsAskedAgainAsSoonAsTheOtherPersonDoesSomethingElse() {
        CompanionWorld w = twoPeopleInTheGarden();
        String other = declineTheFirstEncounter(w, now.plusSeconds(12));
        CompanionRules.advance(w, now.plusSeconds(60));
        assertThat(w.pendingEncounters).isEmpty();
        // He closes his book and stands up. That is the whole trigger - well inside the half hour
        // the pair used to be locked out for.
        ResidentSimulation.replaceActor(w, other, "garden", "handwork", "收拾工具", now.plusSeconds(900));
        CompanionRules.advance(w, now.plusSeconds(72));
        assertThat(w.pendingEncounters).as("the scene changed, so the question is worth asking again").isNotEmpty();
    }

    @Test void walkingOutAndComingBackIsAFreshSightEvenDoingTheSameThing() {
        CompanionWorld w = twoPeopleInTheGarden();
        String other = declineTheFirstEncounter(w, now.plusSeconds(12));
        ResidentSimulation.replaceActor(w, other, "street", "walk", "出去一趟", now.plusSeconds(300));
        CompanionRules.advance(w, now.plusSeconds(48));
        assertThat(w.pendingEncounters).as("he is not in the room, there is nobody to notice").isEmpty();
        // Back in the garden, doing the very same thing as before. The impression only ever lasted
        // as long as he was standing there, so this is somebody walking in, not somebody unchanged.
        ResidentSimulation.replaceActor(w, other, "garden", "observe", "看看花园", now.plusSeconds(900));
        CompanionRules.advance(w, now.plusSeconds(84));
        assertThat(w.pendingEncounters).as("walking back in is a new fact").isNotEmpty();
    }
}
