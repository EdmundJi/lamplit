package com.betterself.growth.town.companion.application;

import com.betterself.growth.town.companion.domain.CompanionRules;
import com.betterself.growth.town.companion.domain.CompanionWorld;
import com.betterself.growth.town.companion.domain.ResidentSimulation;
import com.betterself.growth.town.companion.domain.ResidentSeed;
import com.betterself.growth.town.companion.tools.InMemoryModelUsage;
import com.betterself.growth.town.companion.tools.InMemoryWorldStore;
import com.betterself.growth.town.companion.tools.MutableClock;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Independent acceptance probes for the accelerated-life work. These tests deliberately exercise
 * public application/model entry points where one exists. Direct domain calls are used only to
 * author the controlled starting condition (changing a job or adding a hand-written resident),
 * never as proof that the normal model dispatcher can reach the resulting behavior.
 */
class AcceleratedTownRunnerAutonomyIT {
    private static final Instant NOW = Instant.parse("2026-09-08T06:00:00Z");

    @Test void aChangedCareerSurvivesPublicProjectsAndActsAgainInALaterHour() {
        CompanionWorld world = CompanionRules.join("qa-career-persistence", "我", "Asia/Shanghai", NOW);
        world.conversations.clear();
        parkOtherResidents(world, "owner", NOW.plusSeconds(7_200));

        assertThat(ResidentSimulation.changeOccupation(world, "owner", "接插画和翻译的零活", NOW)).isTrue();
        var owner = ResidentSimulation.state(world, "owner");
        assertThat(owner.careerIntent).isNotNull();

        Instant firstActedAt = null;
        for (int second = 6; second <= 3_900; second += 6) {
            CompanionRules.advance(world, NOW.plusSeconds(second));
            if (owner.careerIntent.lastActedAt != null && firstActedAt == null) firstActedAt = owner.careerIntent.lastActedAt;
        }

        assertThat(firstActedAt).isNotNull();
        assertThat(owner.careerIntent.purpose).contains("插画和翻译");
        assertThat(owner.careerIntent.lastActedAt).isAfterOrEqualTo(firstActedAt.plusSeconds(3_600));
        assertThat(owner.occupation).contains("插画和翻译");
    }

    @Test void aHandWrittenResidentHasADynamicHomeOwnEvidenceAndAUsableModelContext() throws Exception {
        CompanionWorld world = CompanionRules.join("qa-manual-resident", "我", "Asia/Shanghai", NOW);
        world.conversations.clear();
        assertThat(ResidentSeed.addResident(world, "translator", "阿岚", "自由译者", "在家接翻译工作", NOW)).isTrue();
        parkOtherResidents(world, "translator", NOW.plusSeconds(7_200));

        var newcomer = ResidentSimulation.state(world, "translator");
        assertThat(world.locations).anyMatch(p -> p.id().equals("home-translator") && p.ownerId().equals("translator"));
        assertThat(world.positions).anyMatch(p -> p.id.equals("home-translator-bed") && p.ownerId.equals("translator"));
        assertThat(world.memories).anyMatch(m -> m.ownerId().equals("translator") && m.sourceType().equals("seed"));
        assertThat(newcomer.careerIntent.purpose).contains("翻译");

        // Move past the authored arrival rest and leave this newcomer as the only decision candidate.
        CompanionRules.advance(world, NOW.plusSeconds(66));
        world.modelConversationsEnabled = true;
        var captured = new AtomicReference<ResidentMind.Context>();
        ResidentMind mind = new ResidentMind() {
            @Override public boolean enabled() { return true; }
            @Override public Decision decide(Context context) {
                captured.set(context);
                return new Decision("work", "home", null, "先在家做一小段翻译", "", List.of(context.memories().getFirst().id()), null, null);
            }
        };
        var store = new InMemoryWorldStore(); store.seed(11L, world);
        var clock = new MutableClock(NOW.plusSeconds(66));
        var director = new ResidentDirector(store, mind, clock, 32, new InMemoryModelUsage());
        try {
            director.consider(11L, world);
            await(() -> captured.get() != null && store.read(11L).events.stream().anyMatch(e -> e.type().equals("thought")));
        } finally { director.close(); }

        ResidentMind.Context context = captured.get();
        assertThat(context.residentId()).isEqualTo("translator");
        assertThat(context.occupation()).contains("翻译");
        assertThat(context.memories()).isNotEmpty().allMatch(m -> m.ownerId().equals("translator"));
        assertThat(context.lifeIntent()).isNotNull();
    }

    @Test void aLegacyJsonSnapshotSelfHealsBeforeContinuingLife() throws Exception {
        ObjectMapper json = new ObjectMapper().findAndRegisterModules();
        CompanionWorld original = CompanionRules.join("qa-legacy", "我", "Asia/Shanghai", NOW);
        ObjectNode tree = (ObjectNode) json.valueToTree(original);
        tree.remove(List.of("cafeOperatorId", "cafeOperating", "workArrangements"));
        for (var node : (ArrayNode) tree.get("residentStates")) {
            ObjectNode resident = (ObjectNode) node;
            resident.remove(List.of("occupation", "careerIntent", "lifeIntent", "suspendedAction", "desiredDurationSeconds"));
        }

        CompanionWorld legacy = json.treeToValue(tree, CompanionWorld.class);
        CompanionRules.advance(legacy, NOW.plusSeconds(6));

        assertThat(legacy.cafeOperatorId).isEqualTo("owner");
        assertThat(legacy.workArrangements).isNotNull();
        assertThat(legacy.residentStates).filteredOn(r -> !r.id.equals("self"))
            .allSatisfy(r -> {
                assertThat(r.occupation).isNotBlank();
                assertThat(r.careerIntent).isNotNull();
                assertThat(r.lifeIntent).isNotNull();
                assertThat(r.plan).isNotNull();
            });
    }

    @Test void ruleOnlyLifeDoesNotInventFixedFollowUpActivitiesForResidents() {
        CompanionWorld world = CompanionRules.join("qa-no-scripted-wishes", "我", "Asia/Shanghai", NOW);
        world.modelConversationsEnabled = false;
        for (int second = 6; second <= 7_200; second += 6) CompanionRules.advance(world, NOW.plusSeconds(second));

        assertThat(world.projects).extracting(p -> p.id)
            .containsExactlyInAnyOrder("reading-night", "quiet-corner", "street-colors", "seed-exchange");
        assertThat(world.events).noneMatch(e -> e.type().equals("new_wish"));
    }

    @Test void aNewCafeOperatorIsNotSentToTheirFormerFixedWorkWindow() {
        Instant eightInTown = Instant.parse("2026-09-09T00:00:00Z");
        CompanionWorld world = CompanionRules.join("qa-takeover-window", "我", "Asia/Shanghai", eightInTown);
        world.conversations.clear();
        assertThat(ResidentSimulation.proposeWorkArrangement(world, "owner", "takeover", "gardener", "想请你正式接手", eightInTown)).isTrue();
        String arrangementId = world.workArrangements.getLast().id;
        assertThat(ResidentSimulation.acceptWorkArrangement(world, "gardener", arrangementId, eightInTown.plusSeconds(1))).isTrue();
        ResidentSimulation.state(world, "gardener").plan = null;

        CompanionRules.advance(world, eightInTown.plusSeconds(6));

        var gardener = ResidentSimulation.state(world, "gardener");
        assertThat(world.cafeOperatorId).isEqualTo("gardener");
        assertThat(gardener.occupation).isEqualTo("经营咖啡馆");
        assertThat(gardener.plan.action()).isNotEqualTo("away");
        assertThat(gardener.plan.reason()).doesNotContain("花圃");
    }

    @Test void anAcceptedHelperActuallyFinishesAWaitingRequestAndCanEndFutureAuthority() {
        CompanionWorld world = CompanionRules.join("qa-helper-service", "我", "Asia/Shanghai", NOW);
        world.conversations.clear();
        assertThat(ResidentSimulation.proposeWorkArrangement(world, "artist", "assist", "owner", "我来替一阵", NOW)).isTrue();
        String arrangementId = world.workArrangements.getLast().id;
        assertThat(ResidentSimulation.acceptWorkArrangement(world, "owner", arrangementId, NOW.plusSeconds(1))).isTrue();

        moveActor(world, "artist", "cafe", "make", "继续手上的画", NOW.plusSeconds(300));
        var artist = ResidentSimulation.state(world, "artist");
        artist.plan = new CompanionWorld.Plan("qa-art", "make", "cafe", null, "继续手上的画", NOW, NOW.plusSeconds(300));
        artist.dutyPressure = 100;
        moveActor(world, "student", "cafe", "rest", "等一杯热饮", NOW.plusSeconds(300));
        var request = new CompanionWorld.ServiceRequest();
        request.id = "qa-request"; request.requesterId = "student"; request.kind = "coffee";
        request.place = "cafe"; request.status = "waiting"; request.requestedAt = NOW;
        world.serviceRequests.add(request);

        CompanionRules.advance(world, NOW.plusSeconds(6));
        assertThat(artist.plan.action()).isEqualTo("tend");
        assertThat(request.status).isEqualTo("preparing");
        assertThat(ResidentSimulation.endWorkArrangement(world, "artist", arrangementId, "ended", NOW.plusSeconds(7))).isTrue();
        assertThat(ResidentSimulation.mayTend(world, "artist")).isFalse();
        CompanionRules.advance(world, NOW.plusSeconds(30));
        assertThat(request.status).isIn("delivered", "consumed");
    }

    @Test void modelFailureDoesNotStopTheApplicationAdvanceLoop() throws Exception {
        CompanionWorld world = CompanionRules.join("qa-model-fallback", "我", "Asia/Shanghai", NOW);
        world.conversations.clear();
        var store = new InMemoryWorldStore(); store.seed(21L, world);
        var clock = new MutableClock(NOW);
        ResidentMind unavailable = new ResidentMind() {
            @Override public boolean enabled() { return true; }
            @Override public Decision decide(Context context) { throw new IllegalStateException("qa outage"); }
        };
        var director = new ResidentDirector(store, unavailable, clock, 32, new InMemoryModelUsage());
        var service = new CompanionService(store, clock, director, (userId, day) -> List.of());
        try {
            service.advance(21L);
            await(() -> store.read(21L).modelConsecutiveFailures > 0);
            List<String> before = store.read(21L).residentStates.stream().filter(r -> !r.id.equals("self")).map(r -> r.plan.id()).toList();
            clock.advanceTo(NOW.plusSeconds(6));
            CompanionWorld after = service.advance(21L).world();
            assertThat(after.modelRetryAfter).isNotNull();
            assertThat(after.residentStates).filteredOn(r -> !r.id.equals("self")).allMatch(r -> r.plan != null);
            assertThat(after.residentStates.stream().filter(r -> !r.id.equals("self")).map(r -> r.plan.id()).toList()).hasSize(before.size());
        } finally { director.close(); }
    }

    @Test void normalSimulationStartsANonProjectWorkConversationAndBothModelTurnsAreRequired() throws Exception {
        CompanionWorld world = CompanionRules.join("qa-normal-work-dialogue", "我", "Asia/Shanghai", NOW, true);
        world.projects.clear();
        world.conversations.clear();
        parkOtherResidents(world, "owner", NOW.plusSeconds(7_200));
        // Artist must remain available as the owner's conversation partner.
        var artist = ResidentSimulation.state(world, "artist");
        artist.plan = new CompanionWorld.Plan("qa-artist-life", "observe", "cafe", null, "想聊聊近况", NOW, NOW.plusSeconds(300));
        artist.lastSocialAt = NOW.minusSeconds(2_000);
        moveActor(world, "artist", "cafe", "observe", "想聊聊近况", NOW.plusSeconds(300));
        var owner = ResidentSimulation.state(world, "owner");
        owner.plan = new CompanionWorld.Plan("qa-owner-life", "observe", "cafe", null, "想聊聊工作", NOW, NOW.plusSeconds(300));
        owner.lastSocialAt = NOW.minusSeconds(2_000);
        moveActor(world, "owner", "cafe", "observe", "想聊聊工作", NOW.plusSeconds(300));

        CompanionRules.advance(world, NOW.plusSeconds(6));
        var conversation = world.conversations.stream().filter(c -> c.status.equals("active") && c.topicId.equals("life")).findFirst().orElseThrow();

        AtomicInteger turns = new AtomicInteger();
        ResidentMind mind = new ResidentMind() {
            @Override public boolean enabled() { return true; }
            @Override public Decision decide(Context context) { throw new AssertionError("work negotiation must stay in each resident's dialogue turn"); }
            @Override public com.betterself.growth.town.companion.domain.ConversationLifecycle.Utterance generateTurn(DialogueRequest request) {
                int turn = turns.incrementAndGet();
                if (turn == 1) {
                    assertThat(request.perspective().residentId()).isEqualTo("owner");
                    return new com.betterself.growth.town.companion.domain.ConversationLifecycle.Utterance(
                        "我想停一停。你愿意试着接手咖啡馆吗？", false, "忐忑", "none", null, List.of(), "☕", "offer_takeover", "artist");
                }
                assertThat(request.perspective().residentId()).isEqualTo("artist");
                var proposal = request.perspective().workArrangements().stream().filter(a -> a.status().equals("proposed")).findFirst().orElseThrow();
                return new com.betterself.growth.town.companion.domain.ConversationLifecycle.Utterance(
                    "我愿意试一阵，但会按自己的方法来。", true, "认真", "none", null, List.of(), "☕🎨", "accept_work", proposal.id());
            }
        };
        var store = new InMemoryWorldStore(); store.seed(31L, world);
        var clock = new MutableClock(NOW.plusSeconds(6));
        var director = new ResidentDirector(store, mind, clock, 32, new InMemoryModelUsage());
        try {
            director.consider(31L, world);
            await(() -> world.workArrangements.size() == 1);
            assertThat(world.workArrangements.getFirst().status).isEqualTo("proposed");
            assertThat(world.cafeOperatorId).isEqualTo("owner");
            clock.advanceTo(NOW.plusSeconds(13));
            director.consider(31L, world);
            await(() -> world.workArrangements.getFirst().status.equals("active"));
            assertThat(turns).hasValue(2);
            assertThat(world.cafeOperatorId).isEqualTo("artist");
            assertThat(conversation.turns).extracting(CompanionWorld.Turn::speakerId).containsExactly("owner", "artist");
        } finally { director.close(); }
    }

    private static void parkOtherResidents(CompanionWorld world, String activeId, Instant until) {
        for (var resident : world.residentStates) {
            if (resident.id.equals("self") || resident.id.equals(activeId)) continue;
            resident.plan = new CompanionWorld.Plan("qa-park-" + resident.id, "sleep", "home-" + resident.id, null, "QA隔离", NOW, until);
            for (int i = 0; i < world.residents.size(); i++) {
                var actor = world.residents.get(i);
                if (actor.id().equals(resident.id))
                    world.residents.set(i, new CompanionWorld.Actor(actor.id(), actor.name(), actor.role(), "home-" + resident.id, "sleep", "QA隔离", actor.x(), actor.y(), until));
            }
        }
    }

    private static void moveActor(CompanionWorld world, String id, String place, String activity, String label, Instant until) {
        for (int i = 0; i < world.residents.size(); i++) {
            var actor = world.residents.get(i);
            if (actor.id().equals(id))
                world.residents.set(i, new CompanionWorld.Actor(actor.id(), actor.name(), actor.role(), place, activity, label, actor.x(), actor.y(), until));
        }
    }

    private static void await(java.util.function.BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + 3_000_000_000L;
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) Thread.sleep(10);
        assertThat(condition.getAsBoolean()).isTrue();
    }
}
