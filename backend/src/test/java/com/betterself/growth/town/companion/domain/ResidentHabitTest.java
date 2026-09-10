package com.betterself.growth.town.companion.domain;

import com.betterself.growth.town.companion.domain.CompanionWorld.*;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Item 1: habits sunk from ResidentSeed.NARRATIVES' actingSelf prose down into the rules layer -
 * the rules act on a resident's own behalf, without asking the model, whenever a condition the
 * rules already track holds. These tests pin the three things the task asks to be checked directly:
 * a concrete person under a concrete condition really performs their habit (and it lands as an
 * unexplained deed), the habit never advances any world state, and the frequency stays bounded.
 * Item 3 (a resident's own standing belief turning its own habit down) is pinned here too, since it
 * is the same mechanism's other half.
 */
class ResidentHabitTest {
    private static final Instant DAY = Instant.parse("2026-09-09T04:00:00Z"); // noon Shanghai, cafe open

    private CompanionWorld world(String id) {
        CompanionWorld w = CompanionRules.join(id, "住客", "Asia/Shanghai", DAY);
        w.conversations.forEach(c -> c.status = "ended");
        return w;
    }

    /** Runs step() forward in one-minute increments, keeping the structural condition supplied by
     * `keepConditionTrue` true on every tick, until the habit fires (recorded in lastHabitAt) or a
     * generous budget of simulated hours is exhausted. */
    private boolean runUntilHabitFires(CompanionWorld w, ResidentState r, String habitId, Instant start, Runnable keepConditionTrue) {
        Instant t = start;
        for (int minute = 0; minute < 600; minute++) {
            keepConditionTrue.run();
            t = t.plusSeconds(60);
            ResidentSimulation.step(w, t);
            if (r.lastHabitAt.containsKey(habitId)) return true;
        }
        return false;
    }

    @Test void theOwnerTidiesUpInsteadOfSayingHeIsUncomfortable() {
        // 阿禾's actingSelf: "心里不舒服时去擦桌子、理杯子，而不是说出来" - dutyPressure (never sent to
        // any model) is the rules' own tracked measure of exactly that unspoken discomfort.
        CompanionWorld w = world("habit-owner-tidy");
        ResidentState owner = ResidentSimulation.state(w, "owner");
        int projectProgressBefore = w.projects.get(0).progress;
        int conversationsBefore = w.conversations.size();
        String cafeOperatorBefore = w.cafeOperatorId;
        String cafeStatusBefore = w.cafeStatus;
        int deedsBefore = ResidentSimulation.unexplainedDeeds(w, "owner").size();

        owner.plan = new Plan("owner-busy", "observe", "cafe", null, "看看店里的情况", DAY, DAY.plusSeconds(300000));
        ResidentSimulation.replaceActor(w, "owner", "cafe", "observe", "看看店里的情况", DAY.plusSeconds(300000));

        boolean fired = runUntilHabitFires(w, owner, "tidy", DAY, () -> owner.dutyPressure = 50);
        assertThat(fired).as("the owner's tidy habit fired within the test budget").isTrue();

        var deeds = ResidentSimulation.unexplainedDeeds(w, "owner");
        assertThat(deeds.size()).isGreaterThan(deedsBefore);
        assertThat(deeds).anyMatch(d -> "tidy".equals(d.action) && "cafe".equals(d.place));
        // Only what an observer would see - never a motive (the same discipline DeedExplanationTest
        // pins for a model-explained deed already applies to a rule-fired habit).
        assertThat(deeds.getLast().note).doesNotContain("因为").doesNotContain("觉得");

        // The habit never advanced any world state: no project progressed, no conversation started,
        // cafe authority and status are untouched. It only ever colors the label of whatever the
        // owner's real, ongoing plan already was.
        assertThat(w.projects.get(0).progress).isEqualTo(projectProgressBefore);
        assertThat(w.conversations.size()).isEqualTo(conversationsBefore);
        assertThat(w.cafeOperatorId).isEqualTo(cafeOperatorBefore);
        assertThat(w.cafeStatus).isEqualTo(cafeStatusBefore);
        assertThat(owner.plan.action()).isEqualTo("observe"); // the real plan itself is untouched
        assertThat(owner.plan.place()).isEqualTo("cafe");
    }

    @Test void theGardenerHandsOverAToolInsteadOfTalkingWhenSomeoneElseIsThere() {
        // 青叔's actingSelf: "话少，动手多：用东西代替话" - someone else sharing the garden right now is
        // the rules' own "身边有人" fact.
        CompanionWorld w = world("habit-gardener-handwork");
        ResidentState gardener = ResidentSimulation.state(w, "gardener");
        gardener.plan = new Plan("gardener-busy", "work", "garden", null, "整理花圃", DAY, DAY.plusSeconds(300000));
        ResidentSimulation.replaceActor(w, "gardener", "garden", "work", "整理花圃", DAY.plusSeconds(300000));
        ResidentSimulation.replaceActor(w, "student", "garden", "observe", "在花园里走走", DAY.plusSeconds(300000));

        boolean fired = runUntilHabitFires(w, gardener, "handwork", DAY, () -> {});
        assertThat(fired).isTrue();
        assertThat(ResidentSimulation.unexplainedDeeds(w, "gardener")).anyMatch(d -> "tend_object".equals(d.action));
        assertThat(gardener.plan.action()).isEqualTo("work"); // still just a label on the real plan
    }

    @Test void habitsStayInTheSingleDigitsOverAFullSimulatedDay() {
        // Item 1's own frequency backstop: "每人每小时最多一次那种量级"; this pins the sharper, testable
        // claim the task actually asks for - single digits across a whole day, per resident.
        CompanionWorld w = world("habit-frequency");
        // Keep the structural conditions favourable all day so this is a genuine upper-bound test,
        // not one that happens to pass because the condition rarely holds.
        ResidentState owner = ResidentSimulation.state(w, "owner");
        ResidentSimulation.replaceActor(w, "student", "garden", "observe", "在花园里走走", DAY.plusSeconds(90000));
        ResidentSimulation.replaceActor(w, "artist", "cafe", "observe", "在店里坐着", DAY.plusSeconds(90000));

        Map<String, Integer> fireCounts = new HashMap<>();
        Map<String, Instant> lastSeen = new HashMap<>();
        Instant t = DAY;
        Instant end = DAY.plusSeconds(24 * 3600);
        while (t.isBefore(end)) {
            owner.dutyPressure = 50; // keep the owner's own condition true all day
            t = t.plusSeconds(60);
            ResidentSimulation.step(w, t);
            for (String id : List.of("owner", "student", "artist", "gardener", "fixer", "weaver")) {
                ResidentState r = ResidentSimulation.state(w, id);
                if (r == null) continue;
                for (Map.Entry<String, Instant> e : r.lastHabitAt.entrySet()) {
                    String key = id + ":" + e.getKey();
                    if (!e.getValue().equals(lastSeen.get(key))) {
                        lastSeen.put(key, e.getValue());
                        fireCounts.merge(key, 1, Integer::sum);
                    }
                }
            }
        }
        assertThat(fireCounts).as("at least one habit actually fired over the day").isNotEmpty();
        fireCounts.forEach((key, count) -> assertThat(count).as(key).isLessThan(10));
    }

    @Test void aStandingBeliefUnderTheReservedKeyWeakensTheHabitWithoutTheRulesReadingItsText() {
        // Item 3: the rules never read what the belief says - only its structured shape (owner,
        // sourceType, supersedesKey, importance, evidenceIds, at). A belief under the exact reserved
        // key "habit:owner:tidy" is enough to make the habit fire far less often, regardless of what
        // the belief's own text happens to say.
        CompanionWorld w = world("habit-belief-damping");
        ResidentState owner = ResidentSimulation.state(w, "owner");
        String evidenceId = w.memories.stream().filter(m -> m.ownerId().equals("owner")).findFirst().orElseThrow().id();
        // Text is deliberately irrelevant to the mechanism - only the key/type/importance are read.
        assertThat(ResidentSimulation.applyReflection(w, "owner", owner.revision,
            "我一紧张就去擦桌子，其实没必要。", List.of(evidenceId), "habit:owner:tidy", DAY)).isTrue();

        owner.plan = new Plan("owner-busy", "observe", "cafe", null, "看看店里的情况", DAY, DAY.plusSeconds(300000));
        ResidentSimulation.replaceActor(w, "owner", "cafe", "observe", "看看店里的情况", DAY.plusSeconds(300000));

        // With the belief standing, the habit must not fire within a window that reliably fires it
        // when no such belief exists (see theOwnerTidiesUpInsteadOfSayingHeIsUncomfortable above,
        // which succeeds within 300 one-minute ticks without one).
        Instant t = DAY;
        for (int minute = 0; minute < 300; minute++) {
            owner.dutyPressure = 50;
            t = t.plusSeconds(60);
            ResidentSimulation.step(w, t);
        }
        assertThat(owner.lastHabitAt).as("a standing, importance-9 belief damps the habit far past this window").doesNotContainKey("tidy");
    }

    // ---- two people on one thing: the reflex half ------------------------------------------------

    /** Steps one continuous clock forward - a habit that walks somebody somewhere and then works
     * there needs the trip AND the work to run out, so the simulated time cannot be rewound between
     * the two the way {@link #runUntilHabitFires} would if it were called first. */
    /** Everybody except the named resident put out of the way for the length of a test, so a race
     * between neighbours cannot decide what the test measures. */
    private void parkEveryoneElse(CompanionWorld w, String keep) {
        for (ResidentState other : w.residentStates)
            if (!other.id.equals(keep) && !other.id.equals("self"))
                other.plan = new Plan("park-" + other.id, "sleep", TownPlaces.homeOf(other.id), null, "隔离", DAY, DAY.plusSeconds(200_000));
    }

    private void runUntilContribution(CompanionWorld w, ResidentState r, String habitId, java.util.function.BooleanSupplier landed) {
        Instant t = DAY;
        for (int minute = 0; minute < 900 && !landed.getAsBoolean(); minute++) {
            t = t.plusSeconds(60);
            ResidentSimulation.step(w, t);
        }
    }

    /** The measurement this exists for: across a full simulated day, `create` was offered to residents
     * 216 times and chosen 0, `help` 216 and 0, `invite` 233 and 0, `join` 233 and 0. Nothing in this
     * town was ever worked on by more than one person, so nothing communal was ever finished - a
     * communal project stops dead at SOLO_PROGRESS_CAP until a second pair of hands arrives. */
    @Test void theOwnerLaysDownTheFirstStrokeOfHisOwnGatheringWithoutBeingAsked() {
        // An idea nobody has started is an idea nobody can join, and the person with the least excuse
        // is whoever wanted it.
        CompanionWorld w = world("own-gathering");
        // Nobody else in the way: with the noticeboard now telling everyone about everyone's things,
        // a neighbour who wanders in can genuinely get to his project before he does - which is the
        // whole point of the rest of this work, and exactly what this one test must not measure.
        for (ResidentState other : w.residentStates)
            if (!other.id.equals("owner") && !other.id.equals("self"))
                other.plan = new Plan("park-" + other.id, "sleep", TownPlaces.homeOf(other.id), null, "隔离", DAY, DAY.plusSeconds(90_000));
        ResidentState owner = ResidentSimulation.state(w, "owner");
        owner.plan = null; owner.suspendedAction = null;
        ResidentSimulation.replaceActor(w, "owner", "cafe", "idle", "在店里", DAY);
        CompanionWorld.Project gathering = w.projects.stream().filter(p -> "owner".equals(p.ownerId)).findFirst().orElseThrow();
        assertThat(gathering.progress).as("nothing has been done to it yet").isZero();

        runUntilContribution(w, owner, "own_thing", () -> gathering.progress > 0);
        assertThat(owner.lastHabitAt).as("the habit fired").containsKey("own_thing");
        assertThat(gathering.progress).as("his own thing is finally under way").isPositive();
        assertThat(gathering.contributors).contains("owner");
        assertThat(ResidentSimulation.unexplainedDeeds(w, "owner"))
            .as("a reflex leaves a deed for him to account for later, never a rule-written motive")
            .anyMatch(d -> "create".equals(d.action));
    }

    @Test void theFixerActuallyLendsTheHandHisOwnHabitSaysHeCameToLend() {
        // placeHabitCheckCafe already says, in his own words, that he goes to the shop "看看有没有
        // 需要搭把手的" - and then observes. This is the step it stopped one short of.
        CompanionWorld w = world("lend-a-hand");
        ResidentState fixer = ResidentSimulation.state(w, "fixer");
        CompanionWorld.Project shared = w.projects.stream()
            .filter(p -> "cafe".equals(p.place) && !"fixer".equals(p.ownerId)).findFirst().orElseThrow();
        // He has to know about it before he can walk past it - the ordinary knowledge path.
        ResidentSimulation.state(w, "fixer").knownProjects.put(shared.id,
            new CompanionWorld.ProjectKnowledge(shared.id, shared.place, shared.status, shared.progress, DAY, shared.ownerId));
        ResidentSimulation.memory(w, "fixer", shared.ownerId, "heard", DAY, shared.id,
            "听说店里那件「" + shared.title + "」还没弄完。", List.of(), 5);
        shared.progress = 40; shared.status = "active";
        if (!shared.contributors.contains(shared.ownerId)) shared.contributors.add(shared.ownerId);
        fixer.plan = null; fixer.suspendedAction = null;
        // Already in the room with it: lending a hand acts on what is in front of you, never a trip.
        ResidentSimulation.replaceActor(w, "fixer", "cafe", "idle", "在店里", DAY);

        runUntilContribution(w, fixer, "lend_a_hand", () -> shared.contributors.contains("fixer"));
        assertThat(fixer.lastHabitAt).as("the habit fired").containsKey("lend_a_hand");
        assertThat(shared.contributors).as("a second pair of hands, which is the whole point").contains("fixer");
        assertThat(shared.contributors.size()).isGreaterThanOrEqualTo(2);
    }

    /** The strong form of "两个人一起做同一件事": not two people who each did some of one thing on
     * different afternoons, but two people with their hands on it at the same moment. You lend a
     * hand to someone you can see working - so when there is such a person, that is the thing that
     * gets chosen, ahead of an untouched project nobody is standing near. */
    @Test void aHandIsLentToTheThingSomebodyIsActuallyStandingThereWorkingOn() {
        CompanionWorld w = world("lend-a-hand-visible");
        ResidentState fixer = ResidentSimulation.state(w, "fixer");
        var cafeProjects = w.projects.stream().filter(p -> "cafe".equals(p.place) && !"fixer".equals(p.ownerId)).toList();
        assertThat(cafeProjects).as("this test needs two cafe projects to choose between").hasSizeGreaterThan(1);
        CompanionWorld.Project untouched = cafeProjects.get(0), underway = cafeProjects.get(1);
        for (CompanionWorld.Project p : List.of(untouched, underway)) {
            fixer.knownProjects.put(p.id, new CompanionWorld.ProjectKnowledge(p.id, p.place, p.status, p.progress, DAY, p.ownerId));
            ResidentSimulation.memory(w, "fixer", p.ownerId, "heard", DAY, p.id, "听说「" + p.title + "」还没弄完。", List.of(), 5);
        }
        // Somebody is right there, hands on `underway`, for the rest of the day.
        ResidentState worker = ResidentSimulation.state(w, underway.ownerId);
        worker.plan = new Plan("worker-p", "create", "cafe", underway.id, "接着弄", DAY, DAY.plusSeconds(40_000));
        ResidentSimulation.replaceActor(w, underway.ownerId, "cafe", "create", "接着弄", DAY.plusSeconds(40_000));
        fixer.plan = null; fixer.suspendedAction = null;
        ResidentSimulation.replaceActor(w, "fixer", "cafe", "idle", "在店里", DAY);

        runUntilContribution(w, fixer, "lend_a_hand", () -> fixer.lastHabitAt.containsKey("lend_a_hand"));
        assertThat(fixer.lastHabitAt).containsKey("lend_a_hand");
        assertThat(fixer.plan).isNotNull();
        assertThat(fixer.plan.targetId()).as("he went to the one with somebody standing at it").isEqualTo(underway.id);
    }

    /** The one case where lending a hand is worth a walk. Three measured days ended with two shared
     * things sitting at the solo cap - one short a third person, one short a second - while the only
     * people who could have unstuck them stayed in the cafe, because the man whose own written self is
     * "闲下来往店里走，看看有没有需要搭把手的" was not allowed to walk anywhere to lend one. */
    @Test void aThingStoppedDeadForWantOfOneMorePairOfHandsIsWorthTheWalk() {
        CompanionWorld w = world("lend-a-hand-walk");
        ResidentState fixer = ResidentSimulation.state(w, "fixer");
        CompanionWorld.Project stalled = w.projects.stream()
            .filter(p -> "garden".equals(p.place) && !"fixer".equals(p.ownerId)).findFirst().orElseThrow();
        stalled.status = "active";
        stalled.progress = ResidentSimulation.SOLO_PROGRESS_CAP;
        stalled.contributors.clear(); stalled.contributors.add(stalled.ownerId);
        stalled.needed = 2;
        // Nobody else in the race: with everyone now able to walk to a thing that has stopped, a
        // neighbour can genuinely get there first and finish it - which is the point of the whole
        // change and exactly what this one test must not be measuring.
        parkEveryoneElse(w, "fixer");
        // His own default has already had its turn, which is what lets anything else move him.
        fixer.lastHabitAt.put("check_cafe", DAY);
        fixer.plan = null; fixer.suspendedAction = null;
        ResidentSimulation.replaceActor(w, "fixer", "cafe", "idle", "在店里", DAY);

        runUntilContribution(w, fixer, "lend_a_hand", () -> stalled.contributors.contains("fixer"));
        assertThat(stalled.contributors).as("it had stopped, and he was the one thing it needed").contains("fixer");
    }

    @Test void nothingWalksAnywhereBeforeYourOwnDefaultHasHadItsTurn() {
        CompanionWorld w = world("lend-a-hand-no-walk");
        ResidentState fixer = ResidentSimulation.state(w, "fixer");
        CompanionWorld.Project stalled = w.projects.stream()
            .filter(p -> "garden".equals(p.place) && !"fixer".equals(p.ownerId)).findFirst().orElseThrow();
        stalled.status = "active";
        stalled.progress = ResidentSimulation.SOLO_PROGRESS_CAP;
        stalled.contributors.clear(); stalled.contributors.add(stalled.ownerId);
        stalled.needed = 2;
        fixer.plan = null; fixer.suspendedAction = null;
        ResidentSimulation.replaceActor(w, "fixer", "cafe", "idle", "在店里", DAY);
        assertThat(fixer.lastHabitAt).doesNotContainKey("check_cafe");

        Instant t = DAY;
        for (int minute = 0; minute < 200 && !fixer.lastHabitAt.containsKey("check_cafe"); minute++) {
            t = t.plusSeconds(60);
            ResidentSimulation.step(w, t);
        }
        assertThat(stalled.contributors).as("an ingrained default gets first claim on the day").doesNotContain("fixer");
    }

    @Test void theFixerNeverLendsAHandToHisOwnThingOrToOneAlreadyFinished() {
        CompanionWorld w = world("lend-a-hand-limits");
        ResidentState fixer = ResidentSimulation.state(w, "fixer");
        for (CompanionWorld.Project p : w.projects) { p.status = "ready"; p.progress = 100; }
        fixer.plan = null; fixer.suspendedAction = null;
        ResidentSimulation.replaceActor(w, "fixer", "cafe", "idle", "在店里", DAY);
        assertThat(runUntilHabitFires(w, fixer, "lend_a_hand", DAY, () -> {}))
            .as("there is nothing unfinished to put a hand on").isFalse();
    }

    /** The payoff, which was unreachable. "celebrate" had a completion branch that flips a project to
     * celebrating, writes everyone who helped a high-importance memory of having seen it real, and
     * steadies them all a notch - and it was in no menu, in no DECISION_ACTIONS, and scheduled by
     * nothing anywhere. A thing that got finished could never be shown to anybody. */
    @Test void afterTwoPeopleFinishAThingSomebodyCanActuallyCallTheOthersOverToSeeIt() {
        CompanionWorld w = world("celebrate-reachable");
        CompanionWorld.Project done = w.projects.stream().filter(p -> "cafe".equals(p.place)).findFirst().orElseThrow();
        done.status = "ready"; done.progress = 100; done.completedAt = DAY;
        done.contributors.clear(); done.contributors.add("owner"); done.contributors.add("fixer");
        for (String id : List.of("owner", "fixer")) ResidentSimulation.replaceActor(w, id, "cafe", "observe", "在店里", DAY);

        assertThat(ResidentSimulation.availableActions(w, "owner", DAY))
            .as("whoever helped make it, standing where it is").contains("celebrate");
        assertThat(ResidentSimulation.availableActions(w, "student", DAY))
            .as("not somebody who had no hand in it").doesNotContain("celebrate");
        ResidentSimulation.replaceActor(w, "fixer", "garden", "observe", "在花园", DAY);
        assertThat(ResidentSimulation.availableActions(w, "fixer", DAY))
            .as("you do not call people over to something in another building").doesNotContain("celebrate");

        ResidentState owner = ResidentSimulation.state(w, "owner");
        owner.plan = new Plan("celebrate-p", "celebrate", "cafe", done.id, "想请大家看看", DAY, DAY.plusSeconds(60));
        Instant t = DAY;
        for (int minute = 0; minute < 10 && !"celebrating".equals(done.status); minute++) {
            t = t.plusSeconds(60);
            ResidentSimulation.step(w, t);
        }
        assertThat(done.status).isEqualTo("celebrating");
        assertThat(w.events).anyMatch(e -> "celebration".equals(e.type()) && done.id.equals(e.projectId()));
        assertThat(w.memories).anyMatch(m -> m.ownerId().equals("owner") && m.text().contains("真的做出来了"));
    }

    /** 知夏 owns the colour collection, it lives in the garden, and her one default walks her to the
     * cafe - so across four measured simulated days she never once worked on her own project. Your
     * own unfinished thing is allowed to move you, but only once your ingrained default has had its
     * turn, which is what stops this from killing that default the way it did the first time. */
    @Test void yourOwnUnfinishedThingCanMoveYouOnceYourOwnDefaultHasHadItsTurn() {
        CompanionWorld w = world("own-thing-travel");
        ResidentState artist = ResidentSimulation.state(w, "artist");
        CompanionWorld.Project hers = w.projects.stream()
            .filter(p -> "artist".equals(p.ownerId)).findFirst().orElseThrow();
        assertThat(hers.place).as("her own project is not where her day takes her").isEqualTo("garden");
        artist.plan = null; artist.suspendedAction = null;
        ResidentSimulation.replaceActor(w, "artist", TownPlaces.homeOf("artist"), "idle", "在家里", DAY);

        // Before her signature habit has ever fired, nothing may move her: that is the rule that
        // stops a second pull stealing an ingrained default's first turn.
        assertThat(artist.lastHabitAt).doesNotContainKey("seek_inspiration");
        runUntilContribution(w, artist, "own_thing", () -> artist.lastHabitAt.containsKey("seek_inspiration"));
        assertThat(artist.lastHabitAt).as("her own default went first").containsKey("seek_inspiration");

        artist.plan = null; artist.suspendedAction = null;
        runUntilContribution(w, artist, "own_thing", () -> hers.contributors.contains("artist"));
        assertThat(hers.contributors).as("and then she can finally get to her own thing").contains("artist");
    }

    /** The eighth "compiles clean, feature is dead" path, and the one that stayed dead longest.
     * celebrate had a completion branch, then (after being wired up) a menu entry - and across four
     * measured runs it was offered 1,658 times and chosen essentially never, so every finished thing
     * in town went unseen. Same menu problem as every other social action; so it stops being a menu
     * item and becomes what it is for most people: something you just do. */
    @Test void whoeverHelpedMakeItCallsTheOthersOverWithoutWaitingToBeAsked() {
        CompanionWorld w = world("show-what-we-made");
        CompanionWorld.Project done = w.projects.stream().filter(p -> "cafe".equals(p.place)).findFirst().orElseThrow();
        done.status = "ready"; done.progress = 100;
        done.contributors.clear(); done.contributors.add("owner"); done.contributors.add("fixer");
        for (String id : List.of("owner", "fixer")) {
            ResidentState r = ResidentSimulation.state(w, id);
            r.plan = null; r.suspendedAction = null;
            ResidentSimulation.replaceActor(w, id, "cafe", "idle", "在店里", DAY);
        }
        ResidentState owner = ResidentSimulation.state(w, "owner");

        runUntilContribution(w, owner, "show_what_we_made", () -> "celebrating".equals(done.status));
        assertThat(done.status).as("it finally gets shown to somebody").isEqualTo("celebrating");
        assertThat(w.events).anyMatch(e -> "celebration".equals(e.type()) && done.id.equals(e.projectId()));
        // And somebody is actually standing round it, not each handed a private note about a
        // gathering that occupied nobody's time. A celebration is several people doing one thing.
        // Either contributor may be the one who calls it, and the caller's own plan clears the
        // moment it completes - so the invariant is that a guest was put there, not who.
        assertThat(w.residentStates).anyMatch(r -> r.plan != null
            && "celebrate".equals(r.plan.action()) && done.id.equals(r.plan.targetId()));
        assertThat(w.memories).anyMatch(m -> m.text().contains("真的做出来了"));
    }

    /** 知夏's own actingSelf is "真做完时反而突然怕拿出来", and she has a habit that says exactly
     * that. A rule that made her show her work would be overwriting the person. */
    @Test void theOneWhoHidesHerWorkIsNeverMadeToShowIt() {
        CompanionWorld w = world("show-what-we-made-not-artist");
        CompanionWorld.Project done = w.projects.stream().filter(p -> "garden".equals(p.place)).findFirst().orElseThrow();
        done.status = "ready"; done.progress = 100;
        done.contributors.clear(); done.contributors.add("artist");
        ResidentState artist = ResidentSimulation.state(w, "artist");
        artist.plan = null; artist.suspendedAction = null;
        ResidentSimulation.replaceActor(w, "artist", "garden", "idle", "在花园", DAY);

        Instant t = DAY;
        for (int minute = 0; minute < 900; minute++) { t = t.plusSeconds(60); ResidentSimulation.step(w, t); }
        assertThat(artist.lastHabitAt).doesNotContainKey("show_what_we_made");
        assertThat(done.status).isEqualTo("ready");
    }

    // ---- the other half of item 3: a resident has to be able to NAME the habit ---------------------

    /** The damping rule above could already read a belief filed under "habit:<居民>:<习惯>". Nothing
     * ever told a resident that such a key existed, so no resident could write one and the whole path
     * was unreachable in a real run. habitTraits is that missing half: the exact keys, offered to the
     * resident who owns them, described as what they keep doing rather than as a lever. */
    @Test void everyResidentWithADefaultReflexIsOfferedTheExactKeyThatReachesIt() {
        CompanionWorld w = world("habit-traits");
        for (String id : List.of("owner", "student", "artist", "gardener", "fixer", "weaver")) {
            var traits = ResidentSimulation.habitTraits(id);
            assertThat(traits).as(id).isNotEmpty();
            for (var t : traits) {
                assertThat(t.key()).as(id).startsWith("habit:" + id + ":");
                assertThat(t.description()).as(t.key()).isNotBlank();
                // Offered as an observation about themselves, never as an instruction about what
                // saying it will do - the resident's conclusion has to stay their own.
                assertThat(t.description()).doesNotContain("少").doesNotContain("不要").doesNotContain("应该");
            }
        }
        assertThat(ResidentSimulation.habitTraits("self")).as("the avatar's habits are the user's, not ours to name").isEmpty();
    }

    @Test void aBeliefUnderSomeoneElsesHabitKeyOrAHabitNobodyHasIsRefused() {
        CompanionWorld w = world("habit-key-guard");
        ResidentState owner = ResidentSimulation.state(w, "owner");
        String evidenceId = w.memories.stream().filter(m -> m.ownerId().equals("owner")).findFirst().orElseThrow().id();
        assertThat(ResidentSimulation.applyReflection(w, "owner", owner.revision,
            "小川总是这样。", List.of(evidenceId), "habit:student:quiet", DAY))
            .as("the reserved prefix is only ever about oneself").isFalse();
        assertThat(ResidentSimulation.applyReflection(w, "owner", owner.revision,
            "我老是这样。", List.of(evidenceId), "habit:owner:brooding", DAY))
            .as("a habit nobody has is not quietly filed").isFalse();
        // An ordinary, resident-invented key is untouched by the guard - it only polices the prefix.
        assertThat(ResidentSimulation.applyReflection(w, "owner", owner.revision,
            "小川总坐那个位置。", List.of(evidenceId), "小川-座位", DAY)).isTrue();
    }

    /** The loop end to end, for a habit that actually moves someone: he keeps going to the cafe, he
     * notices that he keeps going, he says so under the key he was offered, and the going gets rarer.
     * This is also the guard that stops the catalogue drifting away from the real habit ids - a
     * mistyped suffix here would leave the belief filed and the habit completely unaffected. */
    @Test void noticingAPlaceHabitOutLoudMakesItRarerThroughTheKeyTheResidentWasOffered() {
        CompanionWorld w = world("habit-loop-closes");
        ResidentState student = ResidentSimulation.state(w, "student");
        student.plan = null; student.suspendedAction = null;
        ResidentSimulation.replaceActor(w, "student", TownPlaces.homeOf("student"), "idle", "在自己房里", DAY);
        assertThat(runUntilHabitFires(w, student, "study_cafe", DAY, () -> {})).isTrue();

        String key = ResidentSimulation.habitTraits("student").stream()
            .filter(t -> t.key().endsWith(":study_cafe")).findFirst().orElseThrow().key();
        String evidenceId = w.memories.stream().filter(m -> m.ownerId().equals("student")).findFirst().orElseThrow().id();
        assertThat(ResidentSimulation.applyReflection(w, "student", student.revision,
            "我好像总是往那个位置去。", List.of(evidenceId), key, DAY)).isTrue();

        // Same setup as the run that just fired, from a clean slate, with the belief now standing.
        student.lastHabitAt.remove("study_cafe");
        student.plan = null; student.suspendedAction = null;
        ResidentSimulation.replaceActor(w, "student", TownPlaces.homeOf("student"), "idle", "在自己房里", DAY);
        assertThat(runUntilHabitFires(w, student, "study_cafe", DAY, () -> {}))
            .as("having noticed it, it is no longer entirely automatic").isFalse();
    }

    // ---- place habits: a default MOVE, not just a default label -----------------------------------

    @Test void theStudentDefaultsToTheCafeWindowAndOrdersTheOrdinaryCupThatGoesWithIt() {
        // The concrete complaint this half of item 1 answers: request_drink offered 32 times, chosen
        // zero, because nothing in his own context ever gave him a reason to want one - and a hidden
        // thirst value is exactly what is banned. This is the habit instead: he already likes to be
        // there, so he goes, and the drink follows through the same CafeService.request path
        // request_drink already uses - never invented separately.
        CompanionWorld w = world("place-habit-student");
        ResidentState student = ResidentSimulation.state(w, "student");
        student.plan = null; student.suspendedAction = null;
        ResidentSimulation.replaceActor(w, "student", TownPlaces.homeOf("student"), "idle", "在自己房里", DAY);
        int requestsBefore = w.serviceRequests.size();

        boolean fired = runUntilHabitFires(w, student, "study_cafe", DAY, () -> {});
        assertThat(fired).isTrue();
        assertThat(student.plan).isNotNull();
        // Either already walking there, or - if the habit's own eligible tick landed after arrival -
        // already studying there; either is the same default, just caught at a different instant.
        assertThat(List.of("travel", "study")).contains(student.plan.action());
        assertThat(ResidentSimulation.unexplainedDeeds(w, "student")).anyMatch(d -> "study".equals(d.action));
        assertThat(w.serviceRequests.size()).as("the habit ordered the ordinary cup through the real request path")
            .isGreaterThan(requestsBefore);
        assertThat(w.serviceRequests.getLast().requesterId).isEqualTo("student");
    }

    @Test void theArtistDefaultsToTheCafeToWatchPeopleWithoutOrderingAnything() {
        // 知夏's own version of the same instinct: watching, not studying - no drink implied.
        CompanionWorld w = world("place-habit-artist");
        ResidentState artist = ResidentSimulation.state(w, "artist");
        artist.plan = null; artist.suspendedAction = null;
        ResidentSimulation.replaceActor(w, "artist", TownPlaces.homeOf("artist"), "idle", "在家里", DAY);
        int requestsBefore = w.serviceRequests.size();

        boolean fired = runUntilHabitFires(w, artist, "seek_inspiration", DAY, () -> {});
        assertThat(fired).isTrue();
        assertThat(ResidentSimulation.unexplainedDeeds(w, "artist")).anyMatch(d -> "observe".equals(d.action));
        assertThat(w.serviceRequests.size()).isEqualTo(requestsBefore); // watching, never a drink
    }

    @Test void theGardenerDefaultsToTheGardenWhenNothingElseIsAlreadyDecided() {
        CompanionWorld w = world("place-habit-gardener");
        ResidentState gardener = ResidentSimulation.state(w, "gardener");
        gardener.plan = null; gardener.suspendedAction = null;
        ResidentSimulation.replaceActor(w, "gardener", TownPlaces.homeOf("gardener"), "idle", "在家里", DAY);

        boolean fired = runUntilHabitFires(w, gardener, "tend_garden", DAY, () -> {});
        assertThat(fired).isTrue();
        assertThat(ResidentSimulation.unexplainedDeeds(w, "gardener")).anyMatch(d -> "work".equals(d.action));
    }

    // ---- the gardener's isolation (see this batch's report): a measured full simulated day found
    // him sharing a place with anyone else exactly once, because his only default (tend_garden, just
    // above) sends him toward the one public place none of the other five residents' own place
    // habits ever visit. The two tests below pin the fix: a second default that actually goes where
    // people are, and proof that the rule-detected encounter mechanism (maybeEncounter) then finds
    // someone there to detect.

    @Test void theGardenerAlsoDeliversASeedlingToTheCafeWhenGardenWorkIsAlreadyCovered() {
        CompanionWorld w = world("place-habit-gardener-cafe");
        ResidentState gardener = ResidentSimulation.state(w, "gardener");
        // Garden work is already covered for hours - exactly the condition under which the old code
        // left him with no other default at all.
        gardener.lastHabitAt.put("tend_garden", DAY);
        gardener.plan = null; gardener.suspendedAction = null;
        ResidentSimulation.replaceActor(w, "gardener", TownPlaces.homeOf("gardener"), "idle", "在家里", DAY);
        int requestsBefore = w.serviceRequests.size();

        boolean fired = runUntilHabitFires(w, gardener, "deliver_seedling", DAY, () -> {});
        assertThat(fired).isTrue();
        assertThat(List.of("travel", "observe")).contains(gardener.plan.action());
        assertThat(gardener.plan.place()).isEqualTo("cafe");
        assertThat(ResidentSimulation.unexplainedDeeds(w, "gardener")).anyMatch(d -> "observe".equals(d.action));
        // An errand, not a drink order - unlike the student's cafe habit, this one never requests one.
        assertThat(w.serviceRequests.size()).isEqualTo(requestsBefore);
    }

    @Test void theGardenerActuallyMeetsSomeoneAtTheCafeOnceTheNewHabitSendsHimThere() {
        // Proof the behaviour, not just a constant, changed: with someone already sitting at the cafe
        // and garden work already covered, the new habit walks him there and the pre-existing,
        // untouched maybeEncounter mechanism registers a real encounter - something the measured day
        // (one encounter pair with the gardener in twenty-four hours) shows essentially never happened
        // through his old, single, garden-only default.
        CompanionWorld w = CompanionRules.join("gardener-reach", "住客", "Asia/Shanghai", DAY, true);
        w.conversations.forEach(c -> c.status = "ended");
        ResidentState gardener = ResidentSimulation.state(w, "gardener");
        ResidentState owner = ResidentSimulation.state(w, "owner");
        gardener.lastHabitAt.put("tend_garden", DAY);
        gardener.plan = null; gardener.suspendedAction = null; gardener.lastSocialAt = null;
        ResidentSimulation.replaceActor(w, "gardener", TownPlaces.homeOf("gardener"), "idle", "在家里", DAY);
        // Someone is already at the cafe - the one place his old habit never sent him toward.
        owner.plan = null; owner.suspendedAction = null; owner.lastSocialAt = null;
        ResidentSimulation.replaceActor(w, "owner", "cafe", "observe", "在店里坐着", DAY.plusSeconds(20 * 3600L));

        Instant t = DAY;
        boolean met = false;
        for (int minute = 0; minute < 400 && !met; minute++) {
            t = t.plusSeconds(60);
            ResidentSimulation.step(w, t);
            met = w.encounterCooldowns.containsKey("gardener:owner") || w.encounterCooldowns.containsKey("owner:gardener");
        }
        assertThat(gardener.lastHabitAt).as("the new cafe-bound habit actually fired").containsKey("deliver_seedling");
        assertThat(met).as("the rules actually put him in front of someone").isTrue();
    }

    @Test void placeHabitsNeverFireWhileSomethingElseIsAlreadyDecidedOrUnderway() {
        // "习惯是默认值不是强制": once the resident (or the model) has already decided something, a
        // place habit must never override it - it only ever fills a genuinely undecided moment.
        CompanionWorld w = world("place-habit-not-a-command");
        ResidentState student = ResidentSimulation.state(w, "student");
        student.plan = new Plan("already-deciding", "work", TownPlaces.homeOf("student"), null, "先把这件事做完", DAY, DAY.plusSeconds(400000));
        ResidentSimulation.replaceActor(w, "student", TownPlaces.homeOf("student"), "work", "先把这件事做完", DAY.plusSeconds(400000));
        Instant t = DAY;
        for (int minute = 0; minute < 600; minute++) {
            t = t.plusSeconds(60);
            ResidentSimulation.step(w, t);
        }
        assertThat(student.lastHabitAt).doesNotContainKey("study_cafe");
        assertThat(student.plan.id()).isEqualTo("already-deciding"); // completely untouched
    }
}
