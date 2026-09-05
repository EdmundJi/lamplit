package com.betterself.growth.town;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

class TownReflectionServiceTest {

    private final TownReflectionService service =
        new TownReflectionService(null, null, null, null, null, Clock.systemUTC().withZone(ZoneOffset.UTC), new ObjectMapper());

    @Test
    void parsesAWellFormedReflection() {
        String json = """
            {"greeting":"晚上好，今天也认真生活了一天。","insights":["完成了一次专注练习。","有一件事推迟了，但你调整了计划。"]}
            """;
        TownReflectionService.Parsed parsed = service.parse(json);

        assertThat(parsed.greeting()).isEqualTo("晚上好，今天也认真生活了一天。");
        assertThat(parsed.insights()).containsExactly("完成了一次专注练习。", "有一件事推迟了，但你调整了计划。");
    }

    @Test
    void fallsBackToADefaultChineseGreetingOnMalformedJson() {
        TownReflectionService.Parsed parsed = service.parse("not even json");

        assertThat(parsed.greeting()).isEqualTo("晚上好，今天辛苦了。");
        assertThat(parsed.insights()).containsExactly("今天也在慢慢往前走。");
    }

    @Test
    void fallsBackToADefaultInsightWhenTheModelReturnsNone() {
        String json = "{\"greeting\":\"晚上好。\",\"insights\":[]}";
        TownReflectionService.Parsed parsed = service.parse(json);

        assertThat(parsed.greeting()).isEqualTo("晚上好。");
        assertThat(parsed.insights()).containsExactly("今天也在慢慢往前走。");
    }

    @Test
    void fallsBackToADefaultGreetingWhenTheModelReturnsABlankOne() {
        String json = "{\"greeting\":\"   \",\"insights\":[\"完成了一次练习。\"]}";
        TownReflectionService.Parsed parsed = service.parse(json);

        assertThat(parsed.greeting()).isEqualTo("晚上好，今天辛苦了。");
    }

    @Test
    void clampsAnOverlongGreetingToEightyCharacters() {
        String longGreeting = "晚".repeat(100);
        String json = "{\"greeting\":\"" + longGreeting + "\",\"insights\":[\"完成了一次练习。\"]}";
        TownReflectionService.Parsed parsed = service.parse(json);

        assertThat(parsed.greeting()).hasSize(80);
    }

    @Test
    void capsInsightsAtThreeItemsAndSixtyCharactersEach() {
        String longInsight = "观".repeat(100);
        String json = """
            {"greeting":"晚上好。","insights":["%s","一","二","三"]}
            """.formatted(longInsight);
        TownReflectionService.Parsed parsed = service.parse(json);

        assertThat(parsed.insights()).hasSize(3);
        assertThat(parsed.insights().get(0)).hasSize(60);
    }
}
