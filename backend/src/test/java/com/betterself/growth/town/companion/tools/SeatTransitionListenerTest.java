package com.betterself.growth.town.companion.tools;

import com.betterself.growth.town.companion.domain.CompanionWorld;
import com.betterself.growth.town.companion.domain.CompanionWorld.Actor;
import com.betterself.growth.town.companion.domain.CompanionWorld.ResidentState;
import com.betterself.growth.town.companion.domain.ResidentSimulation;
import com.betterself.growth.town.companion.domain.TownPlaces;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end wiring for docs/06-society.md "座位事件拆成两股": {@link TimelineCollector} registers
 * itself with {@link TownPlaces} the moment it first sees a world, and from then on every seat change
 * - even the ones {@code TownPlaces}' own 30-minute cooldown keeps out of the narrative
 * {@code took_spot}/{@code left_spot} event stream entirely - lands in the exported timeline as a
 * complete, gap-free {@code seat_state} record. {@link NormDetectorTest} already covers the reading
 * end of that record in isolation; this covers the writing end actually connecting to it.
 */
class SeatTransitionListenerTest {
    private final Instant now = Instant.parse("2026-09-08T06:00:00Z");

    private CompanionWorld world() {
        CompanionWorld w = new CompanionWorld();
        w.id = "seat-listener-test"; w.timezone = "Asia/Shanghai";
        TownPlaces.seed(w);
        for (String id : TownPlaces.RESIDENT_IDS) {
            ResidentState r = new ResidentState();
            r.id = id;
            w.residentStates.add(r);
        }
        for (String id : List.of("owner", "student", "artist", "gardener"))
            w.residents.add(new Actor(id, id, "resident", TownPlaces.homeOf(id), "idle", "", 0, 0, now.plusSeconds(3600)));
        w.avatar = new Actor("self", "我", "self", TownPlaces.homeOf("self"), "idle", "", 0, 0, now.plusSeconds(3600));
        return w;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> seatStates(TimelineCollector collector) {
        return collector.entries().stream().filter(e -> "seat_state".equals(e.get("kind"))).toList();
    }

    @Test
    @DisplayName("挂上监听之后，一次落座就产生一条带 from 的完整记录")
    @SuppressWarnings("unchecked")
    void oneClaimProducesOneCompleteSeatStateRow() {
        CompanionWorld w = world();
        TimelineCollector collector = new TimelineCollector();
        collector.capture(w); // registers the listener

        TownPlaces.claim(w, "gardener", "garden", "plot", now);

        List<Map<String, Object>> rows = seatStates(collector);
        assertThat(rows).hasSize(1);
        Map<String, Object> extra = (Map<String, Object>) rows.get(0).get("extra");
        assertThat(extra).containsEntry("positionId", "garden-plot");
        assertThat(extra).containsEntry("from", null);
    }

    @Test
    @DisplayName("非优先座位快速换位——narrative 事件被冷却挡下，完整记录一条不少")
    void theCompleteRecordSurvivesTheNarrativeCooldownThatThrottlesNonPriorityChurn() {
        CompanionWorld w = world();
        TimelineCollector collector = new TimelineCollector();
        collector.capture(w);

        Instant t = now;
        // Three rapid, unowned-spot hops, all within the 30-minute non-priority cooldown window - the
        // narrative stream (see SeatClaimEventTest's own cooldown test) only ever records the first.
        TownPlaces.claim(w, "artist", "cafe", "table", t);
        t = t.plusSeconds(60);
        TownPlaces.claim(w, "artist", "garden", null, t);
        t = t.plusSeconds(60);
        TownPlaces.claim(w, "artist", "cafe", "table", t);
        collector.capture(w);

        long narrativeSeatEvents = w.events.stream()
                .filter(e -> e.actorIds().contains("artist"))
                .filter(e -> "took_spot".equals(e.type()) || "left_spot".equals(e.type()))
                .count();
        assertThat(narrativeSeatEvents).as("narrative stream is throttled, same as SeatClaimEventTest pins").isEqualTo(1);

        List<Map<String, Object>> rows = seatStates(collector);
        long artistRows = rows.stream().filter(r -> "artist".equals(r.get("actorId"))).count();
        // One row per hop (null->table, table->garden, garden->table) - each already carries both its
        // "from" and "to", unlike the narrative stream's separate left_spot/took_spot pair.
        assertThat(artistRows).as("complete record is never throttled").isEqualTo(3);
    }

    @Test
    @DisplayName("完整记录喂给 NormDetector，缺口是 0——这条维度活了")
    void theCompleteRecordFeedsNormDetectorWithZeroGaps() {
        CompanionWorld w = world();
        TimelineCollector collector = new TimelineCollector();
        collector.capture(w);

        Instant t = now;
        for (int day = 0; day < 3; day++) {
            for (String who : List.of("owner", "artist", "gardener")) {
                TownPlaces.claim(w, who, "cafe", "seat", t);
                t = t.plusSeconds(1800);
                TownPlaces.release(w, who, t);
                t = t.plusSeconds(1800);
            }
        }
        collector.capture(w);

        List<Map<String, Object>> positions = new java.util.ArrayList<>();
        for (var p : w.positions) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", p.id); row.put("place", p.place); row.put("ownerId", p.ownerId);
            row.put("capacity", p.capacity);
            positions.add(row);
        }

        NormDetector.Report report = NormDetector.detect("run", collector.entries(), List.of(), positions, w.timezone);
        assertThat(report.counts()).containsEntry("seatRecordGaps", 0);
    }

    @Test
    @DisplayName("跑完之后就撒手——否则下一个同名世界的座位变化会悄悄混进这一跑的记录")
    void aFinishedCollectorStopsReceivingTransitions() {
        // The registry is keyed by world id and lives as long as the JVM. Surefire runs many tests in
        // one JVM and world ids here are short fixed strings, so "the next world reuses this id" is
        // ordinary. A collector that registers and never hands the listener back goes on appending
        // another run's seat changes to a list it already considers complete - and nothing anywhere
        // would look wrong.
        CompanionWorld first = world();
        TimelineCollector collector = new TimelineCollector();
        collector.capture(first);
        TownPlaces.claim(first, "artist", "cafe", "seat", now);
        int afterOwnRun = seatStates(collector).size();
        assertThat(afterOwnRun).as("自己这一跑的座位变化要记下来").isPositive();

        collector.detach();

        // A second world with the same id - exactly what a later test in the same JVM produces.
        CompanionWorld second = world();
        TownPlaces.claim(second, "gardener", "cafe", "seat", now.plusSeconds(60));
        assertThat(seatStates(collector)).as("撒手之后，别人世界的座位变化不该再进来")
            .hasSize(afterOwnRun);
    }
}
