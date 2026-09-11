package com.betterself.growth.town.companion.domain;

import com.betterself.growth.town.companion.domain.CompanionWorld.*;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * "谁在用什么东西，别人默认不动，除非物主表态" and "座位谁先占谁用，后来者道歉或让开" are real norms of this
 * town, but until now nothing ever wrote down who actually held which position: {@code
 * TownPlaces.claim}/{@code release} changed {@code Position.occupantIds} and {@code
 * ResidentState.positionId} and left no trace in the shared event stream at all - nothing measuring the
 * town from its own events could ever have found this norm, however real it was.
 *
 * <p>These tests exercise {@link TownPlaces#claim} and {@link TownPlaces#release} directly (the same
 * style {@code TownPlacesTest} already uses for this file) rather than driving the full autonomous
 * decision loop, because the fact under test - a world event appearing exactly when, and only when, a
 * position's occupant actually changes - is owned entirely by TownPlaces itself.
 */
class SeatClaimEventTest {
    private final Instant now = Instant.parse("2026-09-08T06:00:00Z");

    private CompanionWorld world() {
        CompanionWorld w = new CompanionWorld();
        w.id = "seat-claim-test"; w.timezone = "Asia/Shanghai";
        TownPlaces.seed(w);
        for (String id : TownPlaces.RESIDENT_IDS) {
            ResidentState r = new ResidentState();
            r.id = id;
            w.residentStates.add(r);
        }
        // TownPlacesTest never needs an Actor to exist for a resident (it only asserts on Position and
        // ResidentState); recording a seat event does, since the text names who took or left the spot -
        // so, unlike that file's own minimal world(), this one also gives every resident a real Actor
        // (and the avatar its own w.avatar) with the same names the rest of this codebase already uses.
        for (String id : List.of("owner", "student", "artist", "gardener"))
            w.residents.add(new Actor(id, name(id), "resident", TownPlaces.homeOf(id), "idle", "", 0, 0, now.plusSeconds(3600)));
        w.avatar = new Actor("self", name("self"), "self", TownPlaces.homeOf("self"), "idle", "", 0, 0, now.plusSeconds(3600));
        return w;
    }
    private static String name(String id) {
        return switch (id) { case "owner" -> "阿禾"; case "student" -> "小川"; case "artist" -> "知夏"; case "gardener" -> "青叔"; default -> "我"; };
    }

    private List<WorldEvent> seatEvents(CompanionWorld w) {
        return w.events.stream().filter(e -> Set.of("took_spot", "left_spot").contains(e.type())).toList();
    }

    @Test
    @DisplayName("有人坐下——记一条 took_spot，看得出是谁、在哪个位置")
    void sittingDownRecordsTookSpotNamingWhoAndWhichPosition() {
        CompanionWorld w = world();
        TownPlaces.claim(w, "gardener", "garden", "plot", now);

        assertThat(seatEvents(w)).hasSize(1);
        WorldEvent e = seatEvents(w).getFirst();
        assertThat(e.type()).isEqualTo("took_spot");
        assertThat(e.positionId()).isEqualTo("garden-plot");
        assertThat(e.place()).isEqualTo("garden");
        assertThat(e.actorIds()).containsExactly("gardener");
        assertThat(e.text()).contains(ResidentSimulation.actor(w, "gardener").name());
    }

    @Test
    @DisplayName("从 null 变成一个位置，和从一个位置变成另一个位置，都算真的易主")
    void movingStraightFromOnePositionToAnotherAlsoCountsAsTakingASpot() {
        CompanionWorld w = world();
        TownPlaces.claim(w, "gardener", "garden", "plot", now); // null -> garden-plot
        TownPlaces.claim(w, "gardener", "cafe", "table", now.plusSeconds(60)); // garden-plot -> cafe-worktable, in one hop

        List<WorldEvent> events = seatEvents(w);
        assertThat(events).extracting(WorldEvent::type).containsExactly("took_spot", "left_spot", "took_spot");
        assertThat(events.get(2).positionId()).isEqualTo("cafe-worktable");
    }

    @Test
    @DisplayName("同一个人一直坐着不动——不会一遍遍重复记")
    void stayingPutDoesNotRepeatTheEvent() {
        CompanionWorld w = world();
        TownPlaces.claim(w, "gardener", "garden", "plot", now);
        TownPlaces.claim(w, "gardener", "garden", "plot", now.plusSeconds(60));
        TownPlaces.claim(w, "gardener", "garden", "plot", now.plusSeconds(120));

        assertThat(seatEvents(w)).hasSize(1);
        assertThat(seatEvents(w).getFirst().type()).isEqualTo("took_spot");
    }

    @Test
    @DisplayName("他起身走了——记一条 left_spot")
    void standingUpRecordsLeftSpot() {
        CompanionWorld w = world();
        TownPlaces.claim(w, "gardener", "garden", "plot", now);
        TownPlaces.release(w, "gardener", now.plusSeconds(60));

        assertThat(seatEvents(w)).hasSize(2);
        WorldEvent left = seatEvents(w).get(1);
        assertThat(left.type()).isEqualTo("left_spot");
        assertThat(left.positionId()).isEqualTo("garden-plot");
        assertThat(left.place()).isEqualTo("garden");
        assertThat(left.actorIds()).containsExactly("gardener");
    }

    @Test
    @DisplayName("release 的两个重载：不带时间的那个不记事件，带时间的才记")
    void theTimelessReleaseOverloadRecordsNothing() {
        CompanionWorld w = world();
        TownPlaces.claim(w, "gardener", "garden", "plot", now);
        TownPlaces.release(w, "gardener"); // no Instant - the overload every pre-existing call site used

        assertThat(seatEvents(w)).hasSize(1); // only the original took_spot
        assertThat(ResidentSimulation.state(w, "gardener").positionId).isNull();
    }

    @Test
    @DisplayName("物主回来收回位置：借用的人留下 left_spot，物主留下 took_spot")
    void ownerReclaimingTheirSeatRecordsBothTheVisitorLeavingAndTheOwnerTakingIt() {
        CompanionWorld w = world();
        // Force every other cafe seat full so "owner" (not the window seat's owner) has to borrow
        // the student's owned window seat - exactly the setup TownPlacesTest itself uses.
        TownPlaces.position(w, "cafe-worktable").capacity = 0;
        for (int i = 2; i <= 6; i++) TownPlaces.position(w, "cafe-window-" + i).capacity = 0;

        TownPlaces.claim(w, "owner", "cafe", "seat", now); // borrows the student's window seat
        TownPlaces.claim(w, "student", "cafe", "seat", now.plusSeconds(30)); // the owner comes back for it

        List<WorldEvent> onThatSeat = w.events.stream().filter(e -> "cafe-window-seat".equals(e.positionId())).toList();
        assertThat(onThatSeat).extracting(WorldEvent::type).containsExactly("took_spot", "left_spot", "took_spot");
        assertThat(onThatSeat.get(0).actorIds()).containsExactly("owner");
        assertThat(onThatSeat.get(1).actorIds()).containsExactly("owner"); // the visitor, displaced
        assertThat(onThatSeat.get(2).actorIds()).containsExactly("student"); // the seat's actual owner
    }

    @Test
    @DisplayName("有主的三个位置——窗边座、苗圃、吧台——再快也不会被频率阈值挡掉")
    void priorityPositionsAreNeverThrottledEvenInRapidSuccession() {
        CompanionWorld w = world();
        TownPlaces.claim(w, "gardener", "garden", "plot", now);
        TownPlaces.release(w, "gardener", now.plusSeconds(5));
        TownPlaces.claim(w, "gardener", "garden", "plot", now.plusSeconds(10));
        TownPlaces.release(w, "gardener", now.plusSeconds(15));

        List<WorldEvent> onThePlot = w.events.stream().filter(e -> "garden-plot".equals(e.positionId())).toList();
        assertThat(onThePlot).extracting(WorldEvent::type)
            .containsExactly("took_spot", "left_spot", "took_spot", "left_spot");
    }

    @Test
    @DisplayName("无主位置之间快速换位——同一个人，三十分钟阈值以内只记第一条")
    void nonPriorityPositionChangesAreThrottledWithinTheTunedWindow() {
        CompanionWorld w = world();
        // cafe-worktable and the garden bench are both unowned - neither is one of the three
        // priority positions - so this exercises the frequency cap this batch measured and tuned,
        // not the always-record path the priority positions get above.
        TownPlaces.claim(w, "artist", "cafe", "table", now);
        TownPlaces.claim(w, "artist", "garden", null, now.plusSeconds(60));
        TownPlaces.claim(w, "artist", "cafe", "table",
            now.plusSeconds(TownPlaces.NON_PRIORITY_SEAT_EVENT_COOLDOWN_SECONDS - 1));

        assertThat(w.events.stream().filter(e -> e.actorIds().contains("artist"))).hasSize(1);

        // Once the tuned window has actually passed, the whole hop is recorded again - both halves
        // together (the left_spot for wherever they were, and the took_spot for wherever they land),
        // never just one: see recordSeatTransition's own note on why the pair is one atomic decision.
        TownPlaces.claim(w, "artist", "garden", null,
            now.plusSeconds(TownPlaces.NON_PRIORITY_SEAT_EVENT_COOLDOWN_SECONDS + 1));
        assertThat(w.events.stream().filter(e -> e.actorIds().contains("artist"))).extracting(WorldEvent::type)
            .containsExactly("took_spot", "left_spot", "took_spot");
    }

    @Test
    @DisplayName("事件文本里只有事实——不写理由，也不写评价")
    void textIsFactOnlyNeverAReasonOrJudgment() {
        CompanionWorld w = world();
        TownPlaces.position(w, "cafe-worktable").capacity = 0;
        for (int i = 2; i <= 6; i++) TownPlaces.position(w, "cafe-window-" + i).capacity = 0;
        TownPlaces.claim(w, "owner", "cafe", "seat", now);
        TownPlaces.claim(w, "student", "cafe", "seat", now.plusSeconds(30));
        TownPlaces.release(w, "student", now.plusSeconds(90));

        List<String> forbidden = List.of(
            "因为", "抱歉", "对不起", "应该", "不该", "理应", "本该",
            "礼貌", "谦让", "让给", "居然", "竟然", "打扰", "占用了别人");
        assertThat(seatEvents(w)).isNotEmpty();
        for (WorldEvent e : seatEvents(w))
            for (String bad : forbidden)
                assertThat(e.text()).doesNotContain(bad);
    }
}
