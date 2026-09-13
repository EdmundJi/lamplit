package com.betterself.growth.town.companion.domain;

import com.betterself.growth.town.companion.domain.CompanionWorld.*;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Ownership, borrowed and given away, without money - docs/01-requirements.md 第二版「世界」「有所有
 * 权，可借可赠，不引入货币」. The load-bearing edge under test throughout is the one the batch's own
 * brief calls its most important: rules record only what a bystander could see - who, what, to whom,
 * when, whether it came back - and <b>never why</b>, the same red line {@link DeedExplanationTest}
 * already pins for a habitual action. "我欠他一次" may only ever appear in a resident's own
 * self-authored text; nothing here ever writes it.
 */
class LendingTest {
    private final Instant now = Instant.parse("2026-09-13T06:00:00Z");

    private CompanionWorld town() {
        CompanionWorld w = CompanionRules.join("lending", "我", "Asia/Shanghai", now, true);
        w.conversations.stream().filter(c -> "active".equals(c.status)).forEach(c -> ConversationLifecycle.finish(w, c, now, "测试准备"));
        for (String id : List.of("fixer", "weaver", "student", "gardener")) {
            ResidentState r = ResidentSimulation.state(w, id);
            r.plan = null; TownPlaces.release(w, id);
        }
        return w;
    }
    private void at(CompanionWorld w, String id, String place) {
        ResidentSimulation.replaceActor(w, id, place, "idle", "在这儿", now.plusSeconds(6000));
    }

    // ---- the mechanism itself, reachable through a real decision ------------------------------

    @Test void lendingAnOwnedItemMovesItWithTheBorrowerButKeepsOwnershipWithTheLender() {
        CompanionWorld w = town();
        at(w, "fixer", "shop"); at(w, "weaver", "shop");
        ResidentState fixer = ResidentSimulation.state(w, "fixer");
        assertThat(ResidentSimulation.availableActions(w, "fixer", now)).contains("lend", "gift");
        assertThat(ResidentSimulation.applyDecision(w, "fixer", fixer.revision, w.intentRevision,
            "shop", "lend", "shop-toolkit", "他手上那点活儿正好用得上", null, List.of(), now)).isTrue();

        assertThat(Lending.isOnLoan(w, "shop-toolkit")).isTrue();
        Loan loan = w.loans.stream().filter(l -> l.itemId().equals("shop-toolkit")).findFirst().orElseThrow();
        assertThat(loan.lenderId()).isEqualTo("fixer");
        assertThat(loan.borrowerId()).isEqualTo("weaver");
        assertThat(loan.gift()).isFalse();
        assertThat(loan.returnedAt()).isNull();

        WorldObject item = w.objects.stream().filter(o -> o.id().equals("shop-toolkit")).findFirst().orElseThrow();
        assertThat(item.ownerId()).as("still fixer's - only its whereabouts moved").isEqualTo("fixer");
        assertThat(item.place()).isEqualTo("shop"); // weaver never left the shop in this scenario

        // Already out: nobody, not even the owner, can lend it again until it comes back.
        assertThat(Lending.lend(w, "fixer", "gardener", "shop-toolkit", now.plusSeconds(1))).isFalse();
    }

    @Test void returningRequiresBothPartiesTogetherAgainAndClearsTheOpenLoan() {
        CompanionWorld w = town();
        at(w, "fixer", "shop"); at(w, "weaver", "shop");
        assertThat(Lending.lend(w, "fixer", "weaver", "shop-toolkit", now)).isTrue();

        at(w, "weaver", "cafe"); // carried it off elsewhere
        assertThat(ResidentSimulation.availableActions(w, "weaver", now.plusSeconds(10))).as("not together, no return yet").doesNotContain("return_loan");
        assertThat(Lending.returnItem(w, "weaver", "shop-toolkit", now.plusSeconds(10))).isFalse();

        at(w, "weaver", "shop"); // back where fixer is
        ResidentState weaver = ResidentSimulation.state(w, "weaver");
        assertThat(ResidentSimulation.availableActions(w, "weaver", now.plusSeconds(20))).contains("return_loan");
        assertThat(ResidentSimulation.applyDecision(w, "weaver", weaver.revision, w.intentRevision,
            "shop", "return_loan", "shop-toolkit", "东西弄好了，还给他", null, List.of(), now.plusSeconds(20))).isTrue();

        Loan settled = w.loans.stream().filter(l -> l.itemId().equals("shop-toolkit")).findFirst().orElseThrow();
        assertThat(settled.returnedAt()).isEqualTo(now.plusSeconds(20));
        assertThat(Lending.isOnLoan(w, "shop-toolkit")).isFalse();
        // Free to be lent out again now that it is back.
        at(w, "gardener", "shop");
        assertThat(Lending.lend(w, "fixer", "gardener", "shop-toolkit", now.plusSeconds(30))).isTrue();
    }

    @Test void aGiftTransfersOwnershipPermanentlyAndLeavesNothingOutstanding() {
        CompanionWorld w = town();
        at(w, "weaver", "shop"); at(w, "student", "shop");
        ResidentState weaver = ResidentSimulation.state(w, "weaver");
        assertThat(ResidentSimulation.applyDecision(w, "weaver", weaver.revision, w.intentRevision,
            "shop", "gift", "shop-thread-box", "反正她比我用得勤", null, List.of(), now)).isTrue();

        WorldObject item = w.objects.stream().filter(o -> o.id().equals("shop-thread-box")).findFirst().orElseThrow();
        assertThat(item.ownerId()).isEqualTo("student");
        assertThat(Lending.isOnLoan(w, "shop-thread-box")).as("a gift owes nothing back").isFalse();
        Loan gift = w.loans.stream().filter(l -> l.itemId().equals("shop-thread-box")).findFirst().orElseThrow();
        assertThat(gift.gift()).isTrue();
        // Student can now lend her own gifted box onward - ownership genuinely moved.
        at(w, "gardener", "shop");
        assertThat(Lending.lend(w, "student", "gardener", "shop-thread-box", now.plusSeconds(5))).isTrue();
    }

    // ---- the red line: no reason, ever ---------------------------------------------------------

    @Test void theWitnessMemoryNeverCarriesAReasonEvenThoughOneWasGiven() {
        CompanionWorld w = town();
        at(w, "fixer", "shop"); at(w, "weaver", "shop");
        ResidentState fixer = ResidentSimulation.state(w, "fixer");
        String reason = "他这两天正在赶一件急活，工具箱放着也是放着";
        assertThat(ResidentSimulation.applyDecision(w, "fixer", fixer.revision, w.intentRevision,
            "shop", "lend", "shop-toolkit", reason, null, List.of(), now)).isTrue();

        var lendingMemories = w.memories.stream().filter(m -> "lending".equals(m.topicId())).toList();
        assertThat(lendingMemories).isNotEmpty();
        for (Memory m : lendingMemories) {
            assertThat(m.text()).as("only what a bystander could see, never why")
                .isEqualTo("周野把一套用了很久的工具箱借给了阿满。")
                .doesNotContain(reason).doesNotContain("因为").doesNotContain("反正");
        }
    }

    // ---- the witness asymmetry, same shape as the cafe door's -----------------------------------

    @Test void onlyWhoeverWasActuallyThereLearnsAnythingAtAll() {
        CompanionWorld w = town();
        // Called through Lending directly, not applyDecision: a witness standing right there is, by
        // definition, a second candidate recipient (see soleOtherResidentHere), which is exactly the
        // ambiguity aRecipientIsNeverGuessedWhenMoreThanOneOtherResidentIsPresent below pins - the two
        // concerns (who receives it, who merely witnesses it) are deliberately kept separate here.
        at(w, "fixer", "shop"); at(w, "weaver", "shop"); at(w, "student", "shop"); at(w, "gardener", "garden");
        assertThat(Lending.lend(w, "fixer", "weaver", "shop-toolkit", now)).isTrue();

        assertThat(w.memories.stream().filter(m -> m.ownerId().equals("student") && "lending".equals(m.topicId())))
            .as("present bystander sees it happen").isNotEmpty();
        assertThat(w.memories.stream().filter(m -> m.ownerId().equals("gardener") && "lending".equals(m.topicId())))
            .as("elsewhere at the time - learns nothing at all, not even that it happened").isEmpty();
    }

    // ---- validation: ownership and an unambiguous recipient are the rules' job, not the model's ---

    @Test void onlyTheActualOwnerMayLendOrGiveAnItemAway() {
        CompanionWorld w = town();
        at(w, "student", "shop"); at(w, "weaver", "shop");
        ResidentState student = ResidentSimulation.state(w, "student");
        assertThat(ResidentSimulation.applyDecision(w, "student", student.revision, w.intentRevision,
            "shop", "lend", "shop-toolkit", "借来试试", null, List.of(), now)).isFalse();
        assertThat(Lending.isOnLoan(w, "shop-toolkit")).isFalse();
    }

    @Test void aRecipientIsNeverGuessedWhenMoreThanOneOtherResidentIsPresent() {
        CompanionWorld w = town();
        at(w, "fixer", "shop"); at(w, "weaver", "shop"); at(w, "student", "shop");
        assertThat(Lending.soleOtherResidentHere(w, "fixer", "shop")).as("two candidates - the rules decline rather than pick one").isNull();
        ResidentState fixer = ResidentSimulation.state(w, "fixer");
        assertThat(ResidentSimulation.availableActions(w, "fixer", now)).as("not offered while ambiguous").doesNotContain("lend", "gift");
        assertThat(ResidentSimulation.applyDecision(w, "fixer", fixer.revision, w.intentRevision,
            "shop", "lend", "shop-toolkit", "随便谁都行", null, List.of(), now)).isFalse();
    }

    @Test void nobodyMayLendOrGiveAnythingWhenStandingAlone() {
        CompanionWorld w = town();
        at(w, "fixer", "shop");
        assertThat(ResidentSimulation.availableActions(w, "fixer", now)).doesNotContain("lend", "gift");
    }
}
