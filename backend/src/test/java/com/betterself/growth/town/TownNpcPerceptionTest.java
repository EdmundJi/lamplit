package com.betterself.growth.town;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class TownNpcPerceptionTest {

    @Test
    void levelBucketsNeverLeakTheRawNumber() {
        assertThat(TownNpcPerception.levelBucket(1)).isEqualTo("刚起步不久");
        assertThat(TownNpcPerception.levelBucket(5)).isEqualTo("已经小有火候");
        assertThat(TownNpcPerception.levelBucket(10)).isEqualTo("相当扎实");
        assertThat(TownNpcPerception.levelBucket(18)).isEqualTo("算是镇上的老资格了");
    }

    @Test
    void frequencyBucketsCoarsenTheSevenDayCount() {
        assertThat(TownNpcPerception.frequencyBucket(6)).isEqualTo("这周几乎天天见你出来走动");
        assertThat(TownNpcPerception.frequencyBucket(3)).isEqualTo("这周偶尔见你出来走动");
        assertThat(TownNpcPerception.frequencyBucket(0)).isEqualTo("有些日子没见你出来走动了");
    }

    @Test
    void guidePresenceLineNamesNoTaskJustWhetherTheyShowedUp() {
        assertThat(TownNpcPerception.guidePresenceLine(true)).isEqualTo("今天你在学院附近露过面。");
        assertThat(TownNpcPerception.guidePresenceLine(false)).isEqualTo("今天还没在学院这边看到你。");
    }

    @Test
    void postmanSignalLineOnlyCountsMessagesNeverTaskContent() {
        assertThat(TownNpcPerception.postmanSignalLine(0)).isEqualTo("朋友那边这两天挺安静，没什么新消息。");
        assertThat(TownNpcPerception.postmanSignalLine(3)).isEqualTo("路上听说你和朋友之间还有 3 条没读的消息。");
    }

    @Test
    void dimensionFocusLineMapsEachKnownDimensionToAPlace() {
        assertThat(TownNpcPerception.dimensionFocusLine("KNOWLEDGE")).isEqualTo("好像总往学院那边跑");
        assertThat(TownNpcPerception.dimensionFocusLine("HEALTH")).isEqualTo("好像总往健身房那边跑");
        assertThat(TownNpcPerception.dimensionFocusLine("CAREER")).isEqualTo("好像总在忙工作上的事");
        assertThat(TownNpcPerception.dimensionFocusLine("RELATIONSHIP")).isEqualTo("好像总在张罗朋友之间的事");
        assertThat(TownNpcPerception.dimensionFocusLine("WELLBEING")).isEqualTo("好像总爱一个人在公园待着");
    }

    @Test
    void dimensionFocusLineReturnsNullForUnknownOrMissingDimension() {
        assertThat(TownNpcPerception.dimensionFocusLine(null)).isNull();
        assertThat(TownNpcPerception.dimensionFocusLine("NOT_A_DIMENSION")).isNull();
    }

    @Test
    void streakHintLineBucketsTheStreakInsteadOfNamingTheCount() {
        assertThat(TownNpcPerception.streakHintLine(0)).isNull();
        assertThat(TownNpcPerception.streakHintLine(1)).isEqualTo("偶尔来一下");
        assertThat(TownNpcPerception.streakHintLine(2)).isEqualTo("偶尔来一下");
        assertThat(TownNpcPerception.streakHintLine(3)).isEqualTo("连着好些天了");
        assertThat(TownNpcPerception.streakHintLine(6)).isEqualTo("连着好些天了");
        assertThat(TownNpcPerception.streakHintLine(7)).isEqualTo("好久没断过了");
        assertThat(TownNpcPerception.streakHintLine(30)).isEqualTo("好久没断过了");
    }

    @Test
    void noPerceptionLineEverLeaksAnArabicNumeral() {
        // 隐私铁律回归测试：dimensionFocusLine / streakHintLine 的输出绝不允许出现数字——
        // levelBucket/guidePresenceLine 同理；postmanSignalLine 是唯一一个允许数字的例外
        // （它只报未读消息条数，不涉及任务或时刻），所以不在此扫描范围内。
        List<String> lines = Arrays.asList(
            TownNpcPerception.levelBucket(1),
            TownNpcPerception.levelBucket(18),
            TownNpcPerception.frequencyBucket(6),
            TownNpcPerception.guidePresenceLine(true),
            TownNpcPerception.guidePresenceLine(false),
            TownNpcPerception.dimensionFocusLine("KNOWLEDGE"),
            TownNpcPerception.dimensionFocusLine("HEALTH"),
            TownNpcPerception.dimensionFocusLine("CAREER"),
            TownNpcPerception.dimensionFocusLine("RELATIONSHIP"),
            TownNpcPerception.dimensionFocusLine("WELLBEING"),
            TownNpcPerception.streakHintLine(1),
            TownNpcPerception.streakHintLine(4),
            TownNpcPerception.streakHintLine(10)
        );

        for (String line : lines) {
            assertThat(line).isNotNull().doesNotMatch(".*\\d.*");
        }
    }
}
