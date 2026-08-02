package com.betterself.growth.execution;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

class ExperienceCalculatorTest {

    private final ExperienceCalculator calculator = new ExperienceCalculator();

    @ParameterizedTest
    @CsvSource({
        "COMPLETED,30,3,1.0,9",
        "PARTIAL,30,3,0.5,5",
        "SKIPPED,30,3,0.0,0",
        "DEFERRED,30,3,0.0,0"
    })
    void calculatesDocumentedExperience(
        TaskEventType type,
        int minutes,
        int difficulty,
        double ratio,
        int expected
    ) {
        assertThat(calculator.earned(type, minutes, difficulty, ratio, 100)).isEqualTo(expected);
    }
}
