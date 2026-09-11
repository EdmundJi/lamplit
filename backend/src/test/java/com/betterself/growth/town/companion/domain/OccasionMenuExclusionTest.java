package com.betterself.growth.town.companion.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The rule this batch exists to make un-forgettable: <b>一个动作要么永远在菜单上，要么挂在它的时机上
 * 单独问 —— 没有第三种</b>, and <b>每一问都必须有一个免费的"不做"</b>.
 *
 * <p>Both halves were learned the expensive way, four separate times. {@code celebrate} 1658 offers /
 * 0 taken, {@code change_work} 839/0, {@code invite} 555/0, {@code lock_door} 470/0, {@code
 * close_cafe} 142/0. Each time the diagnosis was "that action needs a better prompt", so the next
 * action went into the same menu. The measurement that finally settled it was {@code lock_door}: the
 * moment it belongs to - last one in the shop, on the way out - happened <b>7 times</b> in the two
 * days we asked about it 470 times, which makes 463 of those refusals correct answers to a question
 * that should never have been put. The menu was measuring our timing and we were reading it as the
 * residents' indifference.
 *
 * <p>A comment saying so would be forgotten by the fifth action. This test cannot be.
 */
class OccasionMenuExclusionTest {
    private final Instant start = Instant.parse("2026-09-08T00:00:00Z");

    /** Every menu offered to anybody at any point across a simulated day of ordinary rule-only life -
     * which is how an action sneaks back in: not in the base list, but behind one of the dozen
     * conditional branches that only fire in some particular situation. */
    private Set<String> everyActionEverOffered() {
        CompanionWorld w = CompanionRules.join("occasion-menu", "住客", "Asia/Shanghai", start, true);
        Set<String> seen = new LinkedHashSet<>();
        Instant at = start;
        for (int tick = 0; tick < 1440; tick++) { // a full day at one-minute steps
            at = at.plusSeconds(60);
            ResidentSimulation.advance(w, at);
            for (CompanionWorld.ResidentState r : w.residentStates)
                seen.addAll(ResidentSimulation.availableActions(w, r.id, at));
        }
        return seen;
    }

    @Test
    @DisplayName("有时机的动作，一整天的任何一份菜单里都不许出现")
    void noOccasionedActionIsEverOfferedInTheOrdinaryMenu() {
        Set<String> offered = everyActionEverOffered();
        assertThat(offered).as("菜单本身得是活的，否则这条断言什么都没证明").hasSizeGreaterThan(6);
        for (Occasions.Definition d : Occasions.ALL)
            assertThat(offered)
                .as("%s 有自己的时机，就不该再摆在每一次决定的菜单里（它上一次在菜单里的成绩是 0）", d.key())
                .doesNotContain(d.key());
    }

    @Test
    @DisplayName("「没什么特别想做的」永远给得出来")
    void doingNothingInParticularIsAlwaysAvailable() {
        CompanionWorld w = CompanionRules.join("occasion-none", "住客", "Asia/Shanghai", start, true);
        for (CompanionWorld.ResidentState r : w.residentStates)
            assertThat(ResidentSimulation.availableActions(w, r.id, start))
                .as("%s 必须有一个不做任何事的答案，否则它只能伪装成 rest/continue/make", r.id)
                .contains("none");
    }

    @Test
    @DisplayName("每一条时机问句都必须写着「不做」那一侧，而且两侧都写清楚了")
    void everyOccasionSpellsOutBothAnswers() {
        assertThat(Occasions.ALL).isNotEmpty();
        for (Occasions.Definition d : Occasions.ALL) {
            assertThat(d.question()).as("%s 的问题", d.key()).isNotBlank();
            assertThat(d.yes()).as("%s 的「做」是什么意思", d.key()).isNotBlank();
            // The half that keeps getting dropped. A question with only one side written out is the
            // same instrument that produced five zeros, just asked at a better moment.
            assertThat(d.no()).as("%s 的「不做」必须同样写出来，而且不需要理由", d.key()).isNotBlank();
            assertThat(List.of("occasion", "react")).as("%s 得说清楚是谁在问", d.key()).contains(d.askedBy());
            if ("occasion".equals(d.askedBy())) {
                assertThat(d.trigger()).as("%s 自己单独问，就得有认得出那个时刻的规则", d.key()).isNotNull();
                assertThat(d.validity()).as("%s 得能判断答案回来时那个时刻还在不在", d.key()).isNotNull();
                assertThat(d.ttlSeconds()).as("%s 的时机必须会过期", d.key()).isPositive();
            }
        }
    }
}
