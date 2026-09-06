package com.betterself.growth.town;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class TownSocietyJobTest {
    @Test
    void nightlyProductionConsumesCompletedDaysAndFinishesEveryTownBeforeCrossTownSwaps() {
        var jdbc=mock(JdbcTemplate.class);
        var society=mock(TownSocietyService.class);
        var events=mock(TownEventService.class);
        var confidant=mock(TownConfidantService.class);
        var migration=mock(TownMigrationService.class);
        var notes=mock(TownNoteService.class);
        var daily=mock(TownDailyProduction.class);
        var clock=Clock.fixed(Instant.parse("2026-09-06T03:45:00Z"),ZoneOffset.UTC);
        var date=LocalDate.of(2026,9,6);
        when(jdbc.queryForList(anyString(),eq(Long.class),any(Timestamp.class),any(Timestamp.class),any(Timestamp.class)))
            .thenReturn(List.of(1L,2L));
        when(daily.today(1L)).thenReturn(date);
        when(daily.today(2L)).thenReturn(date.minusDays(1));
        new TownSocietyJob(jdbc,society,events,confidant,migration,clock,notes,daily).run();
        var order=inOrder(events,society,notes,confidant,migration);
        order.verify(events).runNightly(1L,date);
        order.verify(society).runNightly(1L,date.minusDays(1));
        order.verify(notes).runNightly(1L,date);
        order.verify(confidant).runNightly(1L,date);
        order.verify(events).runNightly(2L,date.minusDays(1));
        order.verify(society).runNightly(2L,date.minusDays(2));
        order.verify(notes).runNightly(2L,date.minusDays(1));
        order.verify(confidant).runNightly(2L,date.minusDays(1));
        order.verify(migration).runNightly(1L,date);
        order.verify(migration).runNightly(2L,date.minusDays(1));
    }
}
