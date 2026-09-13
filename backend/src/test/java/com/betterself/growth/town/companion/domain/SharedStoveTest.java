package com.betterself.growth.town.companion.domain;

import com.betterself.growth.town.companion.domain.CompanionWorld.*;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 合住争用物件 (docs/01-requirements.md 第二版「世界」「物件按会不会被争分两类」「炉子；浴室；工具台；
 * 床」). The world-tree batch built the common room but left it structural on purpose, naming
 * exactly this gap in its own report rather than faking a stove nobody could reach - this fills it
 * in: a real occupancy mutex, reached through an ordinary, already-legal-feeling verb, only for
 * households that actually share a kitchen.
 */
class SharedStoveTest {
    private final Instant now = Instant.parse("2026-09-13T06:00:00Z");

    private CompanionWorld town() {
        CompanionWorld w = CompanionRules.join("shared-stove", "我", "Asia/Shanghai", now, true);
        w.conversations.stream().filter(c -> "active".equals(c.status)).forEach(c -> ConversationLifecycle.finish(w, c, now, "测试准备"));
        return w;
    }

    @Test void aSharedFlatHasExactlyOneStoveInItsCommonRoom() {
        CompanionWorld w = town();
        // 阿满/知夏 (weaver/artist) are this town's one existing shared flat.
        String home = TownPlaces.homeOf("artist");
        List<Position> stoves = w.positions.stream().filter(p -> "stove".equals(p.kind) && home.equals(p.place)).toList();
        assertThat(stoves).hasSize(1);
        assertThat(stoves.getFirst().ownerId).as("nobody owns the shared stove").isNull();
        assertThat(stoves.getFirst().capacity).isEqualTo(1);
    }

    @Test void aSoloResidentsHomeHasNoStoveAtAll() {
        CompanionWorld w = town();
        assertThat(w.positions.stream().anyMatch(p -> "stove".equals(p.kind) && TownPlaces.homeOf("owner").equals(p.place)))
            .as("living alone is not a contested kitchen").isFalse();
    }

    @Test void cookIsOfferedOnlyToResidentsOfASharedHome() {
        CompanionWorld w = town();
        assertThat(ResidentSimulation.availableActions(w, "artist", now)).as("知夏 shares a kitchen").contains("cook");
        assertThat(ResidentSimulation.availableActions(w, "weaver", now)).as("阿满 shares the same one").contains("cook");
        assertThat(ResidentSimulation.availableActions(w, "owner", now)).as("阿禾 lives alone").doesNotContain("cook");
    }

    @Test void oneFlatmateCookingLeavesTheOtherWaitingNotBlocked() {
        CompanionWorld w = town();
        String home = TownPlaces.homeOf("artist");
        // Both already home, or "cook" schedules a travel plan first and the actual stove claim only
        // happens once that completes - which this test, deliberately not ticking advance() at all,
        // never gives it the chance to do.
        ResidentSimulation.replaceActor(w, "artist", home, "idle", "在家", now.plusSeconds(3600));
        ResidentSimulation.replaceActor(w, "weaver", home, "idle", "在家", now.plusSeconds(3600));
        ResidentState artist = ResidentSimulation.state(w, "artist"), weaver = ResidentSimulation.state(w, "weaver");
        artist.plan = null; weaver.plan = null;
        assertThat(ResidentSimulation.applyDecision(w, "artist", artist.revision, w.intentRevision,
            "home", "cook", null, "做点吃的", null, List.of(), now)).isTrue();
        assertThat(ResidentSimulation.actor(w, "artist").activity()).isEqualTo("cook");

        assertThat(ResidentSimulation.applyDecision(w, "weaver", weaver.revision, w.intentRevision,
            "home", "cook", null, "也想做点吃的", null, List.of(), now)).isTrue();
        // A real occupancy mutex, not a second stove conjured for whoever asks second - the same
        // Outcome.WAITING shape the shop's workbench and the cafe's coffee machine already use.
        assertThat(ResidentSimulation.actor(w, "weaver").activity()).as("站在旁边等一等，不是也占了炉子").isEqualTo("wait");

        Position stove = w.positions.stream().filter(p -> "stove".equals(p.kind) && home.equals(p.place)).findFirst().orElseThrow();
        assertThat(stove.occupantIds).containsExactly("artist");
    }

    /** The exact bug this file caught before it caught anything else: {@code TownPlaces.claim}'s
     * general fallback substitutes ANY other free position at the place when the requested kind is
     * full ("能站的地方都能去") - correct for a chair, wrong for the one shared stove, because a home
     * always has other free furniture nearby (every flat-mate's own bed and desk). Without {@code
     * claimExact}, weaver's "cook" would have silently seated her at her own desk while the plan still
     * read "cook" and the stove showed only one occupant regardless. Pinned directly on the position
     * state, not just the activity label, so a regression that fixes the activity but still moves
     * occupancy elsewhere would still be caught. */
    @Test void aBusyStoveNeverSilentlySubstitutesAFlatmatesOwnFurniture() {
        CompanionWorld w = town();
        String home = TownPlaces.homeOf("artist");
        ResidentSimulation.replaceActor(w, "artist", home, "idle", "在家", now.plusSeconds(3600));
        ResidentSimulation.replaceActor(w, "weaver", home, "idle", "在家", now.plusSeconds(3600));
        ResidentState artist = ResidentSimulation.state(w, "artist"), weaver = ResidentSimulation.state(w, "weaver");
        artist.plan = null; weaver.plan = null;
        ResidentSimulation.applyDecision(w, "artist", artist.revision, w.intentRevision,
            "home", "cook", null, "做点吃的", null, List.of(), now);
        ResidentSimulation.applyDecision(w, "weaver", weaver.revision, w.intentRevision,
            "home", "cook", null, "也想做点吃的", null, List.of(), now);

        Position weaverDesk = w.positions.stream().filter(p -> p.id.equals("home-weaver-desk")).findFirst().orElseThrow();
        Position weaverBed = w.positions.stream().filter(p -> p.id.equals("home-weaver-bed")).findFirst().orElseThrow();
        assertThat(weaverDesk.occupantIds).as("被挤到自己桌子上就是这个 bug 本身").isEmpty();
        assertThat(weaverBed.occupantIds).isEmpty();
        assertThat(weaver.positionId).as("既没占到炉子，也没被塞去别的地方").isNull();
    }

    @Test void theStoveIsFreedWhenItsCookMovesOnToSomethingElse() {
        CompanionWorld w = town();
        ResidentSimulation.replaceActor(w, "artist", TownPlaces.homeOf("artist"), "idle", "在家", now.plusSeconds(3600));
        ResidentState artist = ResidentSimulation.state(w, "artist");
        artist.plan = null;
        ResidentSimulation.applyDecision(w, "artist", artist.revision, w.intentRevision,
            "home", "cook", null, "做点吃的", null, List.of(), now);
        ResidentSimulation.applyDecision(w, "artist", artist.revision, w.intentRevision,
            "home", "rest", null, "吃完歇一会儿", null, List.of(), now);

        String home = TownPlaces.homeOf("artist");
        Position stove = w.positions.stream().filter(p -> "stove".equals(p.kind) && home.equals(p.place)).findFirst().orElseThrow();
        assertThat(stove.occupantIds).as("空出来了，不是永久占用").isEmpty();
    }
}
