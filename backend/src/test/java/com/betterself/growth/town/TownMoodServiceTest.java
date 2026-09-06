package com.betterself.growth.town;
import org.junit.jupiter.api.Test;
import java.time.LocalDate;
import static org.assertj.core.api.Assertions.assertThat;
class TownMoodServiceTest {
    @Test void stableBoundedDailyMoodIncludesLowDaysAndChangesAcrossDates() {
        var date=LocalDate.of(2026,9,6);
        var first=TownMoodService.generate(1,"KE_YUN",date,0.5,0.5);
        assertThat(TownMoodService.generate(1,"KE_YUN",date,0.5,0.5)).isEqualTo(first);
        var moods=java.util.stream.IntStream.range(0,60).mapToObj(i -> TownMoodService.generate(1,"KE_YUN",date.plusDays(i),0.5,0.5)).toList();
        assertThat(moods.stream().distinct().count()).isEqualTo(60);
        assertThat(moods).anySatisfy(m -> assertThat(m.valence()).isLessThan(-0.6));
        assertThat(moods).allSatisfy(m -> {assertThat(m.valence()).isBetween(-1.0,1.0);assertThat(m.energy()).isBetween(0.0,1.0);});
    }
}
