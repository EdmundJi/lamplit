package com.betterself.growth.town.companion.domain;

import com.betterself.growth.town.companion.domain.CompanionWorld.*;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The promise mechanism ({@link ResidentSimulation#promise}) has existed with nothing anywhere ever
 * calling it - a complete, tested feature nobody could reach, because the only place a resident could
 * express wanting to make one was as one item among twenty on the flat decision menu. Measured runs
 * of that menu show exactly why nothing ever got chosen there: {@code create} was offered 342 times
 * and picked 0, {@code invite} 285/0, {@code celebrate} 1658/0 - about two thousand chances and no
 * takers - while the same model, asked a comparably discretionary social question ({@code react}) on
 * its own, alone, said yes 53% of the time. The model was never the problem; sharing a menu with
 * nineteen other options was (see {@link VentureTest}, which found the identical shape for wanting a
 * new project and fixed it the same way: a separate question, asked only when asking could mean
 * something).
 *
 * <p>This class pins only the rules' half of that fix for promises - WHEN it is worth putting the
 * separate question "想不想跟眼前这个人说定一个时候" to a resident. Whether they say yes, to whom, and
 * about what is entirely their own answer and is never decided here - see
 * {@link ResidentSimulation#needsPromiseAsk}'s own doc comment.
 */
class PromiseAskTest {
    private final Instant now = Instant.parse("2026-09-08T06:00:00Z");

    private CompanionWorld twoInTheGarden() {
        CompanionWorld w = CompanionRules.join("promise-ask", "住客", "Asia/Shanghai", now, false);
        w.conversations.forEach(c -> c.status = "ended");
        for (String id : List.of("owner", "student")) {
            ResidentState r = ResidentSimulation.state(w, id);
            r.plan = null; r.suspendedAction = null; r.lastSocialAt = now;
            ResidentSimulation.replaceActor(w, id, "garden", "observe", "看看花园", now.plusSeconds(7200));
        }
        // The gardener is seeded into the garden by default (see CompanionRules.resident) - moved
        // out here so "just owner and student in the garden" is actually just the two of them, the
        // same control PromiseTest.threeInTheGarden already applies to the artist and the cafe.
        ResidentSimulation.replaceActor(w, "gardener", "cafe", "observe", "在咖啡馆", now.plusSeconds(7200));
        w.updatedAt = now; w.simulatedAt = now;
        return w;
    }

    @Test
    @DisplayName("身边有人在场、镇上还有需要不止一个人才做得成且没做完的事、没被问过——值得问")
    void worthAskingWhenSomeoneIsThereAndTownHasSharedWorkLeft() {
        CompanionWorld w = twoInTheGarden();
        assertThat(ResidentSimulation.needsPromiseAsk(w, "owner", now)).isTrue();
    }

    @Test
    @DisplayName("身边没有别人——承诺是当面说的，不问")
    void notAskedWhenNobodyElseIsThere() {
        CompanionWorld w = twoInTheGarden();
        ResidentSimulation.replaceActor(w, "student", "cafe", "observe", "在咖啡馆", now.plusSeconds(7200));
        assertThat(ResidentSimulation.needsPromiseAsk(w, "owner", now)).isFalse();
    }

    @Test
    @DisplayName("身边的人在睡觉或在路上不算在场——还是不问")
    void theOtherPersonMustActuallyBePresentToo() {
        CompanionWorld w = twoInTheGarden();
        ResidentSimulation.replaceActor(w, "student", "garden", "sleep", "睡了", now.plusSeconds(7200));
        assertThat(ResidentSimulation.needsPromiseAsk(w, "owner", now)).isFalse();
    }

    @Test
    @DisplayName("同一天已经问过——不再问；过了一个模拟日——又值得问")
    void askedAtMostOncePerSimulatedDay() {
        CompanionWorld w = twoInTheGarden();
        assertThat(ResidentSimulation.needsPromiseAsk(w, "owner", now)).isTrue();
        ResidentSimulation.markPromiseAsked(w, "owner", now);
        assertThat(ResidentSimulation.needsPromiseAsk(w, "owner", now.plusSeconds(3600)))
            .as("asked an hour ago is not a gap to fill again").isFalse();
        assertThat(ResidentSimulation.needsPromiseAsk(w, "owner", now.plusSeconds(25 * 3600L)))
            .as("a full simulated day has turned over").isTrue();
    }

    @Test
    @DisplayName("名下有一条还没结清的承诺——先不问下一件；结清之后——又值得问")
    void notAskedWhileAnUnsettledPromiseOfTheirOwnIsStillOpen() {
        CompanionWorld w = twoInTheGarden();
        boolean made = ResidentSimulation.promise(w, "owner", "student", "带豆子过来", "garden", now.plusSeconds(600), now);
        assertThat(made).isTrue();
        assertThat(ResidentSimulation.needsPromiseAsk(w, "owner", now))
            .as("owner already promised something not yet due").isFalse();
        // Being the one promised TO, not the one who promised, never counts against the gate.
        assertThat(ResidentSimulation.needsPromiseAsk(w, "student", now)).isTrue();

        Promise p = w.promises.getFirst();
        p.settledAt = now; p.outcome = "came";
        assertThat(ResidentSimulation.needsPromiseAsk(w, "owner", now))
            .as("that promise is now settled - nothing of theirs is still open").isTrue();
    }

    @Test
    @DisplayName("睡着的人、在路上的人——都不问")
    void notAskedWhileAsleepOrTravellingThemselves() {
        CompanionWorld w = twoInTheGarden();
        ResidentSimulation.replaceActor(w, "owner", "garden", "sleep", "睡了", now.plusSeconds(7200));
        assertThat(ResidentSimulation.needsPromiseAsk(w, "owner", now)).isFalse();

        ResidentSimulation.replaceActor(w, "owner", "garden", "travel", "在路上", now.plusSeconds(7200));
        assertThat(ResidentSimulation.needsPromiseAsk(w, "owner", now)).isFalse();
    }

    @Test
    @DisplayName("镇上没有一件需要不止一个人、还没做完的事——不问")
    void notAskedWhenTownHasNoSharedWorkLeftAtAll() {
        CompanionWorld w = twoInTheGarden();
        for (Project p : w.projects) { p.status = "ready"; p.progress = 100; }
        assertThat(ResidentSimulation.needsPromiseAsk(w, "owner", now)).isFalse();
    }

    @Test
    @DisplayName("self（用户的化身）永远不走这条问路——即便旁边站着人、镇上也还有事没做完")
    void avatarIsNeverAsked() {
        CompanionWorld w = twoInTheGarden();
        ResidentSimulation.replaceActor(w, "self", "garden", "observe", "刚搬来", now.plusSeconds(7200));
        assertThat(ResidentSimulation.needsPromiseAsk(w, "self", now)).isFalse();
    }
}
