package com.betterself.growth.town;

import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class TownPromiseDetectorTest {

    @Test
    void catchesAFirstPersonNearTermCommitment() {
        assertThat(TownPromiseDetector.detect("我等下看五分钟")).contains("我等下看五分钟");
        assertThat(TownPromiseDetector.detect("我待会就去背单词")).isPresent();
    }

    @Test
    void ignoresQuestions() {
        assertThat(TownPromiseDetector.detect("你等下要看书吗")).isEmpty();
    }

    @Test
    void ignoresStatementsWithoutATimeWord() {
        assertThat(TownPromiseDetector.detect("我今天很开心")).isEmpty();
    }

    @Test
    void ignoresStatementsWithoutACommitWord() {
        assertThat(TownPromiseDetector.detect("我等下心情不错")).isEmpty();
    }

    @Test
    void ignoresThirdPersonStatements() {
        assertThat(TownPromiseDetector.detect("他等下要去看书")).isEmpty();
    }

    @Test
    void ignoresBlankOrNullInput() {
        assertThat(TownPromiseDetector.detect(null)).isEmpty();
        assertThat(TownPromiseDetector.detect("   ")).isEmpty();
    }

    @Test
    void truncatesAnOverlongMessage() {
        String longMessage = "我等下就去" + "看".repeat(200);
        Optional<String> detected = TownPromiseDetector.detect(longMessage);

        assertThat(detected).isPresent();
        assertThat(detected.get()).hasSize(120);
    }
}
