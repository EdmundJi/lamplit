package com.betterself.growth.town.companion.domain;

import com.betterself.growth.town.companion.domain.CompanionWorld.*;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The four-layer address docs/01-requirements.md 第二版「世界」 calls for - `世界:建筑:房间:物件` -
 * built additively under the existing {@code Location}/{@code Position} pair rather than replacing it
 * (docs/04-decisions.md "地基先行"): the six public buildings, one contested object that is actually
 * reachable through a real resident decision rather than merely declared, and per-resident room
 * subdivision inside private homes. {@code CompanionRules.join} is used throughout (rather than the
 * bare {@code TownPlaces.seed} harness {@link TownPlacesTest} uses) specifically so fixer/weaver and
 * their shop-owned objects (see ResidentSeed) exist for the lending side of this batch too.
 */
class WorldTreeTest {
    private final Instant now = Instant.parse("2026-09-13T06:00:00Z");
    private CompanionWorld town() {
        CompanionWorld w = CompanionRules.join("world-tree", "我", "Asia/Shanghai", now, true);
        w.conversations.stream().filter(c -> "active".equals(c.status)).forEach(c -> ConversationLifecycle.finish(w, c, now, "测试准备"));
        return w;
    }

    @Test void sixPublicBuildingsExistBesidesTheStreet() {
        Set<String> ids = town().locations.stream().map(Location::id).collect(Collectors.toSet());
        assertThat(ids).as("cafe/garden already existed; academy/gym/board/shop are this batch's own")
            .contains("street", "cafe", "garden", "academy", "gym", "board", "shop");
    }

    @Test void theShopsWorkbenchIsARealPositionNotJustAnEntryInADescription() {
        CompanionWorld w = town();
        Position bench = TownPlaces.position(w, "shop-workbench");
        assertThat(bench).isNotNull();
        assertThat(bench.place).isEqualTo("shop");
        assertThat(bench.kind).isEqualTo("workbench"); // docs/01's own example of a contested object
        assertThat(bench.ownerId).isNull(); // public - anyone may queue for it
        assertThat(bench.capacity).isEqualTo(1);
    }

    /** The hard requirement this batch's own brief calls out by name: the contested object must be
     * reachable through an actual resident decision, not merely declared. "work"/"make" are already
     * legal, already-offered actions - no new verb, no adapters change needed for the enum to include
     * "shop" as a destination (KnownPlacesTest/PlaceEnumFollowsTheWorldTest already prove that seam for
     * any new Location). */
    @Test void workingAtTheShopClaimsTheWorkbenchThroughARealDecision() {
        CompanionWorld w = town();
        ResidentState fixer = ResidentSimulation.state(w, "fixer");
        fixer.plan = null; TownPlaces.release(w, "fixer");
        ResidentSimulation.replaceActor(w, "fixer", "shop", "idle", "站在铺子门口", now.plusSeconds(60));
        assertThat(ResidentSimulation.applyDecision(w, "fixer", fixer.revision, w.intentRevision,
            "shop", "work", null, "把那套旧工具收拾一下", null, List.of(), now)).isTrue();
        assertThat(fixer.positionId).isEqualTo("shop-workbench");
        assertThat(TownPlaces.position(w, "shop-workbench").occupantIds).containsExactly("fixer");

        // A real occupancy mutex, not a second person quietly sharing capacity one: somebody else
        // wanting the same workbench at the same moment genuinely waits.
        ResidentState weaver = ResidentSimulation.state(w, "weaver");
        weaver.plan = null; TownPlaces.release(w, "weaver");
        ResidentSimulation.replaceActor(w, "weaver", "shop", "idle", "也在铺子里", now.plusSeconds(60));
        assertThat(ResidentSimulation.applyDecision(w, "weaver", weaver.revision, w.intentRevision,
            "shop", "make", null, "想借张桌子接着做手工", null, List.of(), now.plusSeconds(1))).isTrue();
        assertThat(weaver.plan.action()).isEqualTo("wait");
    }

    @Test void studyingAtTheAcademyClaimsARealSeat() {
        CompanionWorld w = town();
        ResidentState student = ResidentSimulation.state(w, "student");
        student.plan = null; TownPlaces.release(w, "student");
        ResidentSimulation.replaceActor(w, "student", "academy", "idle", "在学院门口", now.plusSeconds(60));
        assertThat(ResidentSimulation.applyDecision(w, "student", student.revision, w.intentRevision,
            "academy", "study", null, "换个地方看书", null, List.of(), now)).isTrue();
        Position seat = TownPlaces.position(w, student.positionId);
        assertThat(seat.place).isEqualTo("academy");
        assertThat(seat.kind).isEqualTo("seat");
    }

    // ---- the "房间" layer inside private homes -----------------------------------------------

    @Test void everyResidentsHomeHasItsOwnRoomAndTheirBedAndDeskPointIntoIt() {
        CompanionWorld w = town();
        for (String id : List.of("owner", "student", "artist", "gardener", "self", "fixer")) {
            String home = TownPlaces.homeOf(id);
            String roomId = home + "-room-" + id;
            Room room = TownPlaces.room(w, roomId);
            assertThat(room).as(id + "'s own bedroom").isNotNull();
            assertThat(room.buildingId()).isEqualTo(home);
            assertThat(room.residentIds()).containsExactly(id);
            assertThat(TownPlaces.position(w, home + "-bed").roomId).as(id + "'s bed").isEqualTo(roomId);
            assertThat(TownPlaces.position(w, home + "-desk").roomId).as(id + "'s desk").isEqualTo(roomId);
        }
    }

    @Test void flatmatesGetSeparateBedroomsPlusOneSharedCommonRoom() {
        CompanionWorld w = town();
        String home = TownPlaces.homeOf("artist");
        assertThat(home).isEqualTo(TownPlaces.homeOf("weaver"));
        Room artistRoom = TownPlaces.room(w, home + "-room-artist");
        Room weaverRoom = TownPlaces.room(w, home + "-room-weaver");
        assertThat(artistRoom.residentIds()).containsExactly("artist");
        assertThat(weaverRoom.residentIds()).containsExactly("weaver");
        assertThat(artistRoom.id()).isNotEqualTo(weaverRoom.id());
        // Docs/01's own point: sharing a flat is a free source of the repeated interaction norms need.
        Room common = TownPlaces.room(w, home + "-common");
        assertThat(common).isNotNull();
        assertThat(common.kind()).isEqualTo("common");
        assertThat(common.residentIds()).containsExactlyInAnyOrder("artist", "weaver");
        assertThat(TownPlaces.position(w, "home-weaver-bed").roomId).isEqualTo(weaverRoom.id());
        assertThat(TownPlaces.roomsAt(w, home)).hasSize(3); // artist's room, weaver's room, the shared common room
    }

    @Test void anOlderSaveWithNoRoomsAtAllSelfHealsOnTheNextSeedCall() {
        CompanionWorld w = town();
        // Simulate a save written before Room existed: strip every room and every roomId stamp.
        w.rooms.clear();
        for (Position p : w.positions) p.roomId = null;
        TownPlaces.seed(w);
        assertThat(w.rooms).isNotEmpty();
        assertThat(TownPlaces.position(w, TownPlaces.homeOf("owner") + "-bed").roomId).isNotNull();
        String artistHome = TownPlaces.homeOf("artist");
        assertThat(TownPlaces.room(w, artistHome + "-common")).isNotNull();
    }

    // ---- the bug this batch's brief called out by name --------------------------------------

    @Test void placeNameNoLongerSilentlyMislabelsAnUnknownPlaceAsTheStreet() {
        assertThat(ResidentSimulation.placeName("shop")).isEqualTo("商店");
        assertThat(ResidentSimulation.placeName("academy")).isEqualTo("学院");
        assertThat(ResidentSimulation.placeName("gym")).isEqualTo("健身房");
        assertThat(ResidentSimulation.placeName("board")).isEqualTo("公告板广场");
        assertThat(ResidentSimulation.placeName("street")).isEqualTo("小街");
        // A place this switch still has not been taught a name for echoes its own id rather than
        // pretending to be the street - see this batch's report for why that default was a real bug.
        assertThat(ResidentSimulation.placeName("bathhouse")).isEqualTo("bathhouse");
    }
}
