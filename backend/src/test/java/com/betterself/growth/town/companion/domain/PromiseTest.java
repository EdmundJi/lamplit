package com.betterself.growth.town.companion.domain;

import com.betterself.growth.town.companion.domain.CompanionWorld.*;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The one thing every other mechanism in the town left out: nobody could commit to a future. Residents
 * can act together and can delegate to the counter, but nothing said "I will come do X at T", so there
 * was never a T at which anyone could form a view about whether that happened.
 *
 * <p>The rules only ever record two facts - that a promise was made, and later whether the person who
 * made it was where they said they would be - and go no further than that on purpose (see
 * {@link CompanionWorld.Promise}'s own doc comment). Whether missing it means anything is left entirely
 * to whoever remembers it, which is why every assertion below that touches wording also checks that no
 * evaluative word ever appears.
 */
class PromiseTest {
    private final Instant now = Instant.parse("2026-09-08T06:00:00Z");

    private CompanionWorld threeInTheGarden() {
        CompanionWorld w = CompanionRules.join("promise", "住客", "Asia/Shanghai", now, false);
        w.conversations.forEach(c -> c.status = "ended");
        for (String id : List.of("owner", "student", "gardener")) {
            ResidentState r = ResidentSimulation.state(w, id);
            r.plan = null; r.suspendedAction = null; r.lastSocialAt = now;
            ResidentSimulation.replaceActor(w, id, "garden", "observe", "看看花园", now.plusSeconds(7200));
        }
        // Kept elsewhere on purpose, so "who was actually in the room" has a real negative case too.
        ResidentSimulation.replaceActor(w, "artist", "cafe", "observe", "在咖啡馆", now.plusSeconds(7200));
        w.memories.removeIf(m -> "promise".equals(m.topicId()));
        w.updatedAt = now; w.simulatedAt = now;
        return w;
    }

    private List<Memory> promiseMemoriesOf(CompanionWorld w, String ownerId) {
        return w.memories.stream().filter(m -> ownerId.equals(m.ownerId()) && "promise".equals(m.topicId())).toList();
    }

    private void advanceMinutes(CompanionWorld w, int minutes) {
        for (int i = 1; i <= minutes; i++) CompanionRules.advance(w, now.plusSeconds(i * 60L));
    }

    @Test
    @DisplayName("一条正常的承诺被记下：许诺者、被许诺者、在场见证人，各自拿到自己视角的事实性记忆")
    void recordsAPromiseFromEveryPerspective() {
        CompanionWorld w = threeInTheGarden();
        Instant dueAt = now.plusSeconds(1200);
        boolean ok = ResidentSimulation.promise(w, "owner", "student", "把新到的豆子带给你", "garden", dueAt, now);

        assertThat(ok).isTrue();
        assertThat(w.promises).hasSize(1);
        Promise p = w.promises.getFirst();
        assertThat(p.byId).isEqualTo("owner");
        assertThat(p.toId).isEqualTo("student");
        assertThat(p.what).isEqualTo("把新到的豆子带给你");
        assertThat(p.place).isEqualTo("garden");
        assertThat(p.witnessIds).containsExactly("gardener");
        assertThat(p.settledAt).isNull();
        assertThat(p.outcome).isNull();
        assertThat(p.thoughtAskedIds).isEmpty();

        String ownerName = ResidentSimulation.actor(w, "owner").name();
        String studentName = ResidentSimulation.actor(w, "student").name();

        assertThat(promiseMemoriesOf(w, "owner")).singleElement().satisfies(m -> {
            assertThat(m.sourceType()).isEqualTo("observed");
            assertThat(m.text()).contains("我答应").contains(studentName);
        });
        assertThat(promiseMemoriesOf(w, "student")).singleElement().satisfies(m -> {
            assertThat(m.sourceType()).isEqualTo("heard");
            assertThat(m.text()).contains(ownerName).contains("答应我");
        });
        assertThat(promiseMemoriesOf(w, "gardener")).singleElement().satisfies(m -> {
            assertThat(m.sourceType()).isEqualTo("heard");
            assertThat(m.text()).contains("我听见").contains(ownerName).contains(studentName);
        });
        // 知夏 was in the cafe, not the garden - not a witness, and nothing was written for her.
        assertThat(promiseMemoriesOf(w, "artist")).isEmpty();
    }

    @Test
    @DisplayName("承诺内容超过40个字——整条不写入")
    void refusesWhenWhatIsTooLong() {
        CompanionWorld w = threeInTheGarden();
        String tooLong = "把".repeat(41);
        assertThat(ResidentSimulation.promise(w, "owner", "student", tooLong, "garden", now.plusSeconds(600), now)).isFalse();
        assertThat(w.promises).isEmpty();
        assertThat(promiseMemoriesOf(w, "owner")).isEmpty();
    }

    @Test
    @DisplayName("约定时间超过24小时以后——整条不写入")
    void refusesWhenDueTooFarOut() {
        CompanionWorld w = threeInTheGarden();
        assertThat(ResidentSimulation.promise(w, "owner", "student", "带豆子", "garden", now.plusSeconds(25 * 3600L), now)).isFalse();
        assertThat(w.promises).isEmpty();
    }

    @Test
    @DisplayName("约定时间不在now之后——整条不写入")
    void refusesWhenDueIsNotInTheFuture() {
        CompanionWorld w = threeInTheGarden();
        assertThat(ResidentSimulation.promise(w, "owner", "student", "带豆子", "garden", now.minusSeconds(60), now)).isFalse();
        assertThat(w.promises).isEmpty();
    }

    @Test
    @DisplayName("两人当时不在同一个地方——不是当面说的，整条不写入")
    void refusesWhenNotInTheSamePlace() {
        CompanionWorld w = threeInTheGarden();
        ResidentSimulation.replaceActor(w, "student", "cafe", "observe", "在咖啡馆", now.plusSeconds(7200));
        assertThat(ResidentSimulation.promise(w, "owner", "student", "带豆子", "garden", now.plusSeconds(600), now)).isFalse();
        assertThat(w.promises).isEmpty();
    }

    @Test
    @DisplayName("自己对自己许诺——整条不写入")
    void refusesPromisingToOneself() {
        CompanionWorld w = threeInTheGarden();
        assertThat(ResidentSimulation.promise(w, "owner", "owner", "带豆子", "garden", now.plusSeconds(600), now)).isFalse();
        assertThat(w.promises).isEmpty();
    }

    @Test
    @DisplayName("地点不是合法地点——整条不写入")
    void refusesAnInvalidPlace() {
        CompanionWorld w = threeInTheGarden();
        assertThat(ResidentSimulation.promise(w, "owner", "student", "带豆子", "not-a-real-place", now.plusSeconds(600), now)).isFalse();
        assertThat(w.promises).isEmpty();
    }

    @Test
    @DisplayName("到点，人在约好的地方——事实是「来了」")
    void settlesAsCameWhenTheyAreThere() {
        CompanionWorld w = threeInTheGarden();
        ResidentSimulation.promise(w, "owner", "student", "带豆子过来", "garden", now.plusSeconds(600), now);
        advanceMinutes(w, 30);
        Promise p = w.promises.getFirst();
        assertThat(p.settledAt).isNotNull();
        assertThat(p.outcome).isEqualTo("came");
    }

    @Test
    @DisplayName("到点，人不在约好的地方——事实是「没来」")
    void settlesAsDidNotComeWhenTheyAreNotThere() {
        CompanionWorld w = threeInTheGarden();
        ResidentSimulation.promise(w, "owner", "student", "带豆子过来", "garden", now.plusSeconds(600), now);
        ResidentSimulation.replaceActor(w, "owner", "cafe", "observe", "临时去了咖啡馆", now.plusSeconds(7200));
        advanceMinutes(w, 30);
        Promise p = w.promises.getFirst();
        assertThat(p.settledAt).isNotNull();
        assertThat(p.outcome).isEqualTo("did_not_come");
    }

    @Test
    @DisplayName("结算只发生一次，不会每一拍都重复写记忆")
    void settlesExactlyOnce() {
        CompanionWorld w = threeInTheGarden();
        ResidentSimulation.promise(w, "owner", "student", "带豆子过来", "garden", now.plusSeconds(600), now);
        advanceMinutes(w, 60);
        long outcomeMemoriesForStudent = w.memories.stream()
            .filter(m -> "student".equals(m.ownerId()) && "promise".equals(m.topicId()) && m.text().contains("约好的时间"))
            .count();
        assertThat(outcomeMemoriesForStudent).isEqualTo(1);
        assertThat(w.promises).hasSize(1);
        Instant firstSettledAt = w.promises.getFirst().settledAt;
        advanceMinutes(w, 5);
        assertThat(w.promises.getFirst().settledAt).isEqualTo(firstSettledAt);
    }

    @Test
    @DisplayName("记忆里不带评价——不写失约、辜负、没守信用、背叛")
    void neverWritesAJudgment() {
        CompanionWorld w = threeInTheGarden();
        ResidentSimulation.promise(w, "owner", "student", "带豆子过来", "garden", now.plusSeconds(600), now);
        ResidentSimulation.replaceActor(w, "owner", "cafe", "observe", "临时去了咖啡馆", now.plusSeconds(7200));
        advanceMinutes(w, 30);
        List<String> forbidden = List.of("失约", "辜负", "没守信用", "背叛");
        List<Memory> promiseMemories = w.memories.stream().filter(m -> "promise".equals(m.topicId())).toList();
        assertThat(promiseMemories).isNotEmpty();
        for (Memory m : promiseMemories)
            for (String bad : forbidden)
                assertThat(m.text()).doesNotContain(bad);
    }

    // ---- "you were asked" bookkeeping (never the answer itself) -----------------------------------

    @Test
    @DisplayName("没结算的承诺不会出现在待问列表里")
    void unsettledPromiseNeverAwaitsThought() {
        CompanionWorld w = threeInTheGarden();
        ResidentSimulation.promise(w, "owner", "student", "带豆子过来", "garden", now.plusSeconds(600), now);
        assertThat(ResidentSimulation.promisesAwaitingThought(w, "student", now)).isEmpty();
        assertThat(ResidentSimulation.promisesAwaitingThought(w, "owner", now)).isEmpty();
        assertThat(ResidentSimulation.promisesAwaitingThought(w, "gardener", now)).isEmpty();
    }

    @Test
    @DisplayName("结算之后，被许诺者、许诺者、见证人都在各自的待问列表里")
    void settledPromiseAwaitsThoughtForEveryone() {
        CompanionWorld w = threeInTheGarden();
        ResidentSimulation.promise(w, "owner", "student", "带豆子过来", "garden", now.plusSeconds(600), now);
        advanceMinutes(w, 30);
        Promise p = w.promises.getFirst();
        Instant settledAt = p.settledAt;
        assertThat(ResidentSimulation.promisesAwaitingThought(w, "student", settledAt)).extracting(x -> x.id).containsExactly(p.id);
        assertThat(ResidentSimulation.promisesAwaitingThought(w, "owner", settledAt)).extracting(x -> x.id).containsExactly(p.id);
        assertThat(ResidentSimulation.promisesAwaitingThought(w, "gardener", settledAt)).extracting(x -> x.id).containsExactly(p.id);
        // 知夏 had no part in this at all.
        assertThat(ResidentSimulation.promisesAwaitingThought(w, "artist", settledAt)).isEmpty();
    }

    @Test
    @DisplayName("被问过之后，就不会再出现在这个人的待问列表里——但别人还没被问过")
    void doesNotAwaitThoughtForSomeoneAlreadyAsked() {
        CompanionWorld w = threeInTheGarden();
        ResidentSimulation.promise(w, "owner", "student", "带豆子过来", "garden", now.plusSeconds(600), now);
        advanceMinutes(w, 30);
        Promise p = w.promises.getFirst();
        Instant settledAt = p.settledAt;

        ResidentSimulation.markPromiseThoughtAsked(w, p.id, "student", settledAt);
        assertThat(ResidentSimulation.promisesAwaitingThought(w, "student", settledAt)).isEmpty();
        assertThat(ResidentSimulation.promisesAwaitingThought(w, "owner", settledAt)).extracting(x -> x.id).containsExactly(p.id);

        // Idempotent: asking again does not duplicate the record or blow up.
        ResidentSimulation.markPromiseThoughtAsked(w, p.id, "student", settledAt);
        assertThat(p.thoughtAskedIds.stream().filter("student"::equals).count()).isEqualTo(1);
    }

    @Test
    @DisplayName("结算过去太久，问都不再问——不会永远挂在待问列表里")
    void stopsAwaitingThoughtOnceTheWindowHasPassed() {
        CompanionWorld w = threeInTheGarden();
        ResidentSimulation.promise(w, "owner", "student", "带豆子过来", "garden", now.plusSeconds(600), now);
        advanceMinutes(w, 30);
        Promise p = w.promises.getFirst();
        Instant settledAt = p.settledAt;

        assertThat(ResidentSimulation.promisesAwaitingThought(w, "student", settledAt.plusSeconds(6 * 3600L)))
            .extracting(x -> x.id).containsExactly(p.id);
        assertThat(ResidentSimulation.promisesAwaitingThought(w, "student", settledAt.plusSeconds(6 * 3600L + 1)))
            .isEmpty();
    }
}
