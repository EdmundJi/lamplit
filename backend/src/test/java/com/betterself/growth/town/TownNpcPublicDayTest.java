package com.betterself.growth.town;

import org.junit.jupiter.api.Test;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import static org.assertj.core.api.Assertions.assertThat;

class TownNpcPublicDayTest {
    @Test void usesTheSavedPlanAndInterestWithoutClaimingFutureWorkAlreadyHappened() {
        var plan = new TownDayPlan.DayPlan(LocalDate.of(2026,9,6), List.of(
            new TownDayPlan.Errand("home","idle",0,600,1,"RHYTHM"),
            new TownDayPlan.Errand("park","sit",600,700,1,"RHYTHM"),
            new TownDayPlan.Errand("academy","reading",800,900,1,"RHYTHM")),List.of());
        String text = TownNpcPublicDay.line("柯云",null,Map.of("KNOWLEDGE",0.9,"HEALTH",0.1),plan);
        assertThat(text).contains("今天打算去公园","书").doesNotContain("去学院","已经","600","900");
        assertThat(TownNpcPublicDay.line("柯云",null,Map.of("HEALTH",0.1,"KNOWLEDGE",0.9),plan)).isEqualTo(text);
    }
    @Test void aStayHomeDayDoesNotInventAnOuting() {
        var plan = new TownDayPlan.DayPlan(LocalDate.of(2026,9,6), List.of(
            new TownDayPlan.Errand("home","idle",0,1440,1,"RHYTHM")),List.of());
        assertThat(TownNpcPublicDay.line("陆夏","HEALTH",Map.of("HEALTH",1.0),plan))
            .contains("在家慢慢待着","肩膀").doesNotContain("健身房");
    }
}
