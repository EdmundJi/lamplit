package com.betterself.growth.town;

import org.junit.jupiter.api.Test;

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
}
