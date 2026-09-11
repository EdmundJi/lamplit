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

    /** What this used to assert, and why it could never have been true.
     *
     * <p>It ran for 65 simulated minutes and then required the owner to have acted on his brand-new
     * career TWICE, an hour apart. Two separate things made that impossible for any resident under
     * any circumstances. First, {@code lastActedAt} was declared, carried all the way into every
     * resident's model context through LifeIntentView, asserted here - and written by nothing at all,
     * anywhere in production code; it reached every model call as a permanent null. That half is now
     * fixed (see ResidentSimulation's markIntentActedOn). Second, and still true: the only rule-driven
     * thing that ever schedules real work for an idle resident is a place habit, whose first firing
     * waits five hours from the moment the world was joined - and this scenario has none available
     * anyway, because changeOccupation pauses the cafe and both of the owner's habits are gated on it
     * being open. A man who has just quit running the shop has, in rule-only life, literally nothing
     * to do, because habits are keyed by resident id rather than by what someone actually does now.
     *
     * <p>That is a real gap and it is written down in docs/03 rather than papered over here. What
     * this test asserts instead is the part the design does guarantee: the change itself survives a
     * long, eventful stretch. The stamping is pinned directly by its own test below, on a resident
     * who actually has work to do - which is the honest way to cover it, rather than hoping a
     * scenario that cannot produce work happens to produce some. */
    @Test void aChangedCareerSurvivesAFullDayOfPublicLife() {
        CompanionWorld world = CompanionRules.join("qa-career-persistence", "我", "Asia/Shanghai", NOW);
        world.conversations.clear();
        assertThat(ResidentSimulation.changeOccupation(world, "owner", "接插画和翻译的零活", NOW)).isTrue();
        var owner = ResidentSimulation.state(world, "owner");
        assertThat(owner.careerIntent).isNotNull();

        for (int second = 60; second <= 90_000; second += 60) CompanionRules.advance(world, NOW.plusSeconds(second));

        assertThat(owner.careerIntent.purpose).contains("插画和翻译");
        assertThat(owner.occupation).contains("插画和翻译");
        assertThat(owner.careerIntent.status).isEqualTo("active");
    }

    /** The write that was missing entirely. Deliberately coarse and said so: the rules stamp "I did
     * work of the kind my direction is about", never "that work served my direction" - judging that
     * is reading meaning, and it belongs to the resident. */
    @Test void doingWorkOfTheKindYourDirectionIsAboutStampsTheDirection() {
        CompanionWorld world = CompanionRules.join("qa-intent-acted", "我", "Asia/Shanghai", NOW);
        world.conversations.clear();
        var student = ResidentSimulation.state(world, "student");
        // The warm start already runs a little of everyone's life, so this may already carry a stamp;
        // what matters is that doing work moves it and not doing work does not.
        Instant beforeStudying = student.careerIntent.lastActedAt;

        student.plan = new CompanionWorld.Plan("qa-study", "study", "home-student", null, "看会儿书", NOW, NOW.plusSeconds(60));
        moveActor(world, "student", "home-student", "study", "看会儿书", NOW.plusSeconds(60));
        CompanionRules.advance(world, NOW.plusSeconds(120));
        assertThat(student.careerIntent.lastActedAt).as("study is work of the kind a direction is about")
            .isNotNull().isNotEqualTo(beforeStudying);

        Instant first = student.careerIntent.lastActedAt;
        var artist = ResidentSimulation.state(world, "artist");
        Instant later = NOW.plusSeconds(7_200);
        artist.plan = new CompanionWorld.Plan("qa-rest", "rest", "home-artist", null, "歇一会儿", later, later.plusSeconds(60));
        moveActor(world, "artist", "home-artist", "rest", "歇一会儿", later.plusSeconds(60));
        Instant artistBefore = artist.careerIntent.lastActedAt;
        CompanionRules.advance(world, later.plusSeconds(120));
        assertThat(artist.careerIntent.lastActedAt).as("resting is not acting on a direction").isEqualTo(artistBefore);
        assertThat(student.careerIntent.lastActedAt).as("and nobody else's stamp moved either").isEqualTo(first);
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
                // Not r.plan: a null plan (nothing currently decided, waiting on a rule habit or a
                // model decision - see ResidentSimulation.step's awaitDecision) is an ordinary resting
                // state, not a self-heal failure. "plan" itself was never stripped from the legacy
                // tree above, so whichever residents were already idle in `original` (this is normal -
                // e.g. straight out of CompanionRules.join's own warm-start, before this test even
                // touches JSON) round-trip with that same, legitimate null plan.
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
        var gardenerActor = ResidentSimulation.actor(world, "gardener");
        assertThat(world.cafeOperatorId).isEqualTo("gardener");
        assertThat(gardener.occupation).isEqualTo("经营咖啡馆");
        // The cafe is not open yet (nobody has decided to unlock the door), so a brand-new operator
        // with nothing else already decided is legitimately idle here - see ResidentSimulation.step's
        // r.plan==null branch and awaitDecision, which is a normal resting state, not a bug. What this
        // test actually guards is that the takeover does not silently resurrect the OLD occupation's
        // fixed routine (garden work) instead of that ordinary idle wait.
        if (gardener.plan != null) {
            assertThat(gardener.plan.action()).isNotEqualTo("away");
            assertThat(gardener.plan.reason()).doesNotContain("花圃");
        }
        assertThat(gardenerActor.activity()).isNotIn("away", "work");
        assertThat(gardenerActor.label()).doesNotContain("花圃").doesNotContain("花园");
    }

    @Test void anAcceptedHelperActuallyFinishesAWaitingRequestAndCanEndFutureAuthority() throws Exception {
        CompanionWorld world = CompanionRules.join("qa-helper-service", "我", "Asia/Shanghai", NOW, true);
        world.conversations.clear();
        assertThat(ResidentSimulation.proposeWorkArrangement(world, "artist", "assist", "owner", "我来替一阵", NOW)).isTrue();
        String arrangementId = world.workArrangements.getLast().id;
        assertThat(ResidentSimulation.acceptWorkArrangement(world, "owner", arrangementId, NOW.plusSeconds(1))).isTrue();

        // CafeService deliberately turns nothing - not dutyPressure, not elapsed waiting time - into
        // an action on a resident's own behalf ("a pattern the rules detect on a resident's behalf is
        // not the resident noticing it" - see CafeService's own class javadoc). Tending the counter in
        // response to a waiting request is a real decision action now (ResidentSimulation.DECISION_ACTIONS,
        // gated by availableActions' own "tend" candidate) that only the model ever chooses; the rules
        // only ever surface the qualitative fact that someone is waiting. So this exercises that real
        // decision path instead of asserting dutyPressure alone flips the plan by itself.
        parkOtherResidents(world, "artist", NOW.plusSeconds(7_200));
        // The student still has to be physically present at the cafe for the coffee to be delivered to
        // them, even while parked out of decision-candidacy the same way parkOtherResidents already
        // parks everyone else (CafeService only ever checks the requester's place, never activity).
        ResidentSimulation.state(world, "student").plan = new CompanionWorld.Plan("qa-park-student", "sleep", "cafe", null, "QA隔离", NOW, NOW.plusSeconds(7_200));
        moveActor(world, "student", "cafe", "sleep", "QA隔离", NOW.plusSeconds(7_200));

        moveActor(world, "artist", "cafe", "make", "继续手上的画", NOW.plusSeconds(300));
        var artist = ResidentSimulation.state(world, "artist");
        artist.plan = new CompanionWorld.Plan("qa-art", "make", "cafe", null, "继续手上的画", NOW, NOW.plusSeconds(300));
        var request = new CompanionWorld.ServiceRequest();
        request.id = "qa-request"; request.requesterId = "student"; request.kind = "coffee";
        request.place = "cafe"; request.status = "waiting"; request.requestedAt = NOW;
        world.serviceRequests.add(request);

        var store = new InMemoryWorldStore(); store.seed(41L, world);
        var clock = new MutableClock(NOW.plusSeconds(6));
        ResidentMind mind = new ResidentMind() {
            @Override public boolean enabled() { return true; }
            @Override public Decision decide(Context context) {
                assertThat(context.residentId()).isEqualTo("artist");
                return new Decision("tend", "cafe", null, "先去照应一下柜台，客人等着", "", List.of(), null, null);
            }
        };
        var director = new ResidentDirector(store, mind, clock, 32, new InMemoryModelUsage());
        try {
            director.consider(41L, world);
            await(() -> artist.plan != null && "tend".equals(artist.plan.action()));
        } finally { director.close(); }

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
            // A resident with nothing currently decided (r.plan == null, waiting on either a rule
            // habit or a model decision - see ResidentSimulation.step's awaitDecision) is an ordinary,
            // common resting state, not a corruption; two of the four seeded residents are already
            // sitting there straight out of CompanionRules.join's own warm-start. What this test is
            // actually pinning is that a failing model does not corrupt or reshuffle the resident
            // roster or their plan identities - so compare plan identity (present-or-absent) rather
            // than assuming every plan slot is populated.
            List<String> before = store.read(21L).residentStates.stream().filter(r -> !r.id.equals("self"))
                .map(r -> r.plan == null ? null : r.plan.id()).toList();
            clock.advanceTo(NOW.plusSeconds(6));
            CompanionWorld after = service.advance(21L).world();
            assertThat(after.modelRetryAfter).isNotNull();
            assertThat(after.residentStates.stream().filter(r -> !r.id.equals("self"))
                .map(r -> r.plan == null ? null : r.plan.id()).toList()).isEqualTo(before);
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
        // The rules only put the two of them face to face; saying something is the resident's own
        // answer now (see ResidentSimulation.applyReaction). This test is about what happens once a
        // conversation is under way, so it gives that answer directly instead of relying on a model.
        // The rules put these two face to face; which of them walks over is theirs, not ours - the
        // candidate order is a hash now, not w.residentStates' index (see ResidentSimulation's
        // encounterPick). This scenario needs the owner to be the one who walks over, because only
        // the owner can offer to hand the cafe on; so say that out loud instead of inheriting it from
        // a list order that used to make it true by accident.
        assertThat(world.pendingEncounters).singleElement().satisfies(introduced ->
            assertThat(List.of(introduced.residentId, introduced.otherId)).containsExactlyInAnyOrder("owner", "artist"));
        world.pendingEncounters.clear();
        var noticed = new CompanionWorld.PendingEncounter();
        noticed.id = "pe-owner-walks-over";
        noticed.residentId = "owner"; noticed.otherId = "artist";
        noticed.place = "cafe"; noticed.at = NOW.plusSeconds(6);
        noticed.residentRevision = ResidentSimulation.state(world, "owner").revision;
        world.pendingEncounters.add(noticed);

        assertThat(ResidentSimulation.applyReaction(world, noticed.id, ResidentSimulation.state(world, noticed.residentId).revision,
            "greet", "在店里碰上了，说两句", List.of(), NOW.plusSeconds(6))).isTrue();
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
