package com.betterself.growth.town.companion.domain;

import com.betterself.growth.town.companion.domain.CompanionWorld.*;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 进别人家 (docs/01-requirements.md 第二版「世界」「进别人家由所有权和门决定，不用姓氏字符串匹配——
 * 默认进不去，被邀请或门没锁就进得去」). Three claims under test throughout: a home starts LOCKED
 * (unlike the cafe, which starts open for business), a resident can never be shut out of their own
 * home regardless of that bit, and "invited" / "unlocked" are a genuine OR - either alone is enough.
 *
 * <p>Locking/unlocking a home is deliberately NOT in this file's coverage of the ordinary decision
 * menu: it is occasioned (see {@code Occasions.ALL}'s {@code lock_home} entry and {@code
 * OccasionMenuExclusionTest}, which already fails the whole build if that ever leaks into {@code
 * availableActions}). What belongs here is the door mechanism itself and the two ordinary-menu
 * actions, {@code invite_home}/{@code visit_home}, that ride on it.
 */
class HomeVisitingTest {
    private final Instant now = Instant.parse("2026-09-13T06:00:00Z");

    private static final List<String> ALL = List.of("owner", "student", "artist", "gardener", "fixer", "weaver");

    /** Everybody parked on the street, plan-free - the scenario each test then places precisely two
     * or three residents out of, deliberately, rather than trusting wherever ResidentSeed happened to
     * seed them. Several of this file's first attempts failed not because the mechanism was wrong but
     * because a THIRD resident's default seed placement (e.g. two of the original four both start at
     * "cafe" when it is open) coincidentally also satisfied an anyMatch this test meant to be about
     * exactly one pair - a reminder that "isolated" has to mean everyone, not just the two names in
     * the test's own title. */
    private CompanionWorld town() {
        CompanionWorld w = CompanionRules.join("home-visiting", "我", "Asia/Shanghai", now, true);
        w.conversations.stream().filter(c -> "active".equals(c.status)).forEach(c -> ConversationLifecycle.finish(w, c, now, "测试准备"));
        for (String id : ALL) {
            ResidentState r = ResidentSimulation.state(w, id);
            // null, not "long ago": greetable()'s 15-minute SOCIAL_RECOVERY_SECONDS window is measured
            // from this field, and the seed's own warm start leaves it only a few minutes before `now`
            // - close enough that a real encounter test would silently find nobody greetable and read
            // as "the mechanism doesn't work" for a reason that had nothing to do with the mechanism.
            r.plan = null; r.lastSocialAt = null; TownPlaces.release(w, id);
            ResidentSimulation.replaceActor(w, id, "street", "idle", "在小街上", now.plusSeconds(6000));
        }
        return w;
    }
    /** Places id, and pins a long rest plan so nothing in a multi-tick advanceTo (habits, routine
     * cues, reflexes - all of them real rule-driven behaviour, none of it gated by a model in this
     * pure-domain test) nudges the resident elsewhere before the scenario's own assertion runs. */
    private void at(CompanionWorld w, String id, String place) {
        ResidentSimulation.replaceActor(w, id, place, "idle", "在这儿", now.plusSeconds(3600));
        ResidentState r = ResidentSimulation.state(w, id);
        r.plan = new CompanionWorld.Plan("pin-" + id, "rest", place, null, "留在这儿", now, now.plusSeconds(3600));
    }
    /** docs/05-notes.md: ResidentSimulation.advance moves at most 60 simulated seconds per call - one
     * big jump falls into the offline-recovery branch instead of stepping through arrival, exactly the
     * trap DailyRhythmTest/ResidentLifeTest already document. */
    private void advanceTo(CompanionWorld w, Instant target) {
        while (w.updatedAt.isBefore(target))
            CompanionRules.advance(w, w.updatedAt.plusSeconds(54).isBefore(target) ? w.updatedAt.plusSeconds(54) : target);
    }

    // ---- the door itself -----------------------------------------------------------------------

    @Test void aHomeStartsLockedUnlikeTheCafe() {
        CompanionWorld w = town();
        assertThat(DoorService.homeDoor(w, TownPlaces.homeOf("owner")).locked)
            .as("默认进不去 - a home is not a business waiting for customers").isTrue();
        assertThat(DoorService.cafeDoor(w).locked).as("对照组：咖啡馆默认开门迎客").isFalse();
    }

    @Test void aResidentIsNeverShutOutOfTheirOwnHomeRegardlessOfTheLock() {
        CompanionWorld w = town();
        String home = TownPlaces.homeOf("owner");
        assertThat(DoorService.canEnterHome(w, "owner", home, now)).isTrue(); // default locked, still true
        DoorService.homeDoor(w, home).locked = true; DoorService.homeDoor(w, home).lockedBy = "somebody-else-entirely";
        assertThat(DoorService.canEnterHome(w, "owner", home, now)).as("locked by someone else, still their own home").isTrue();
    }

    @Test void anUnlockedHomeLetsAnyoneInWithoutAnInvitation() {
        CompanionWorld w = town();
        String home = TownPlaces.homeOf("owner");
        DoorService.homeDoor(w, home).locked = false;
        assertThat(DoorService.canEnterHome(w, "student", home, now)).isTrue();
    }

    @Test void aLockedHomeKeepsOutAnyoneWithoutALiveInvitation() {
        CompanionWorld w = town();
        assertThat(DoorService.canEnterHome(w, "student", TownPlaces.homeOf("owner"), now)).isFalse();
    }

    @Test void aLiveInvitationGetsItsOneGuestInEvenThroughALockedDoor() {
        CompanionWorld w = town();
        String home = TownPlaces.homeOf("owner");
        DoorService.invite(w, "owner", "student", now);
        assertThat(DoorService.canEnterHome(w, "student", home, now)).isTrue();
        assertThat(DoorService.canEnterHome(w, "artist", home, now)).as("nobody else's pass").isFalse();
    }

    @Test void anInvitationExpires() {
        CompanionWorld w = town();
        String home = TownPlaces.homeOf("owner");
        DoorService.invite(w, "owner", "student", now);
        assertThat(DoorService.canEnterHome(w, "student", home, now.plusSeconds(3 * 3600 + 1)))
            .as("三小时之后这张票已经过期").isFalse();
    }

    // ---- invite_home, reachable through a real decision -----------------------------------------

    @Test void invitingSomeoneHomeIsOfferedOnlyFaceToFaceAndNotToAFlatmate() {
        CompanionWorld w = town();
        at(w, "owner", "cafe"); at(w, "student", "cafe");
        assertThat(ResidentSimulation.availableActions(w, "owner", now)).contains("invite_home");
        // 阿满 already lives with 知夏 (TownPlaces flat-mate) - inviting her home makes no sense.
        at(w, "artist", "garden"); at(w, "weaver", "garden");
        assertThat(ResidentSimulation.availableActions(w, "artist", now)).doesNotContain("invite_home");
    }

    @Test void invitingSomeoneHomeGrantsThemAUsableInvitation() {
        CompanionWorld w = town();
        at(w, "owner", "cafe"); at(w, "student", "cafe");
        ResidentState owner = ResidentSimulation.state(w, "owner");
        assertThat(ResidentSimulation.applyDecision(w, "owner", owner.revision, w.intentRevision,
            "cafe", "invite_home", "student", "有空来家里坐坐", null, List.of(), now)).isTrue();

        assertThat(DoorService.isInvited(w, "student", TownPlaces.homeOf("owner"), now)).isTrue();
        assertThat(ResidentSimulation.availableActions(w, "student", now)).contains("visit_home");
    }

    @Test void invitingTheSamePersonAgainRightAwayIsNotOffered() {
        CompanionWorld w = town();
        at(w, "owner", "cafe"); at(w, "student", "cafe");
        DoorService.invite(w, "owner", "student", now);
        assertThat(ResidentSimulation.availableActions(w, "owner", now))
            .as("已经请过，不必立刻再请一次").doesNotContain("invite_home");
    }

    // ---- visit_home, reachable through a real decision --------------------------------------------

    @Test void visitingWithoutAnInvitationIsNeitherOfferedNorLegal() {
        CompanionWorld w = town();
        assertThat(ResidentSimulation.availableActions(w, "student", now)).doesNotContain("visit_home");
        ResidentState student = ResidentSimulation.state(w, "student");
        assertThat(ResidentSimulation.applyDecision(w, "student", student.revision, w.intentRevision,
            "home", "visit_home", "owner", "去看看阿禾", null, List.of(), now)).isFalse();
    }

    @Test void visitingLandsTheGuestAtTheHostsHomeAndSpendsTheInvitation() {
        CompanionWorld w = town();
        DoorService.invite(w, "owner", "student", now);
        ResidentState student = ResidentSimulation.state(w, "student");
        assertThat(ResidentSimulation.applyDecision(w, "student", student.revision, w.intentRevision,
            "home", "visit_home", "owner", "去看看阿禾", null, List.of(), now)).isTrue();

        String home = TownPlaces.homeOf("owner");
        // A travel plan was scheduled (student was not already there) - arrival, and the door check
        // that comes with it, happens once that travel completes.
        assertThat(student.plan).isNotNull();
        advanceTo(w, now.plusSeconds(travelSecondsSafeMargin()));
        assertThat(ResidentSimulation.actor(w, "student").place()).as("到了阿禾家").isEqualTo(home);
        assertThat(DoorService.isInvited(w, "student", home, w.simulatedAt))
            .as("这张票用掉了，不是留着以后再用").isFalse();
    }

    @Test void beingTurnedAwayAtAStrangersLockedDoorIsACleanFailureNotARetryLoop() {
        CompanionWorld w = town();
        DoorService.invite(w, "owner", "student", now);
        ResidentState student = ResidentSimulation.state(w, "student");
        assertThat(ResidentSimulation.applyDecision(w, "student", student.revision, w.intentRevision,
            "home", "visit_home", "owner", "去看看阿禾", null, List.of(), now)).isTrue();
        // The invitation is spent only on actual arrival (see DoorService.consumeInvitation), not at
        // decision time - so it can genuinely lapse mid-walk (an owner having second thoughts, or
        // simply the three-hour window running out), which every other model-driven decision in this
        // town already has to tolerate as "the world moved on before the reply landed". The home
        // defaults locked (aHomeStartsLockedUnlikeTheCafe) and nobody unlocked it, so clearing the
        // invitation alone reproduces a genuine, no-longer-avoidable lockout.
        w.homeInvitations.clear();
        advanceTo(w, now.plusSeconds(travelSecondsSafeMargin()));
        assertThat(student.plan).as("door check failed cleanly; nothing left to retry automatically").isNull();
    }

    @Test void anArrivingGuestGivesThePresentHostOneChanceToReact() {
        CompanionWorld w = town();
        at(w, "owner", TownPlaces.homeOf("owner")); // host already home
        DoorService.invite(w, "owner", "student", now);
        w.pendingEncounters.clear();
        ResidentState student = ResidentSimulation.state(w, "student");
        ResidentSimulation.applyDecision(w, "student", student.revision, w.intentRevision,
            "home", "visit_home", "owner", "去看看阿禾", null, List.of(), now);

        // The pending encounter this produces is exactly as short-lived as any other
        // (PENDING_ENCOUNTER_TTL_SECONDS = 90s) - checked the instant arrival happens, not after the
        // full travelSecondsSafeMargin() window, which is long enough to let it expire and be swept
        // away again before this assertion ever looked at it.
        String home = TownPlaces.homeOf("owner");
        Instant deadline = now.plusSeconds(travelSecondsSafeMargin());
        while (w.updatedAt.isBefore(deadline) && !home.equals(ResidentSimulation.actor(w, "student").place()))
            CompanionRules.advance(w, w.updatedAt.plusSeconds(6));
        assertThat(ResidentSimulation.actor(w, "student").place()).as("到了才谈得上反应").isEqualTo(home);

        assertThat(w.pendingEncounters).as("阿禾在家，应该有机会对来客做出反应")
            .anyMatch(p -> p.residentId.equals("owner") && p.otherId.equals("student"));
    }

    private static long travelSecondsSafeMargin() { return 900; }
}
