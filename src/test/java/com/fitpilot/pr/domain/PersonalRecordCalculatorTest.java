package com.fitpilot.pr.domain;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

class PersonalRecordCalculatorTest {
    private final PersonalRecordCalculator calculator = new PersonalRecordCalculator();

    @Test
    void calculatesEpleyOneRepMax() {
        assertThat(calculator.estimatedOneRepMax(new BigDecimal("80"), 5))
                .isEqualByComparingTo("93.33");
    }

    @Test
    void candidateDoesNotBeatHigherPreviousRecord() {
        var set = new PersonalRecordCalculator.SetPerformance(
                new BigDecimal("80"), 5, false, LocalDateTime.now());

        BigDecimal newOneRm = calculator.candidates(set).stream()
                .filter(candidate -> candidate.type().equals("ESTIMATED_1RM"))
                .findFirst().orElseThrow().score();
        assertThat(newOneRm).isLessThan(new BigDecimal("95"));
    }

    @Test
    void warmupSetProducesNoRecords() {
        var set = new PersonalRecordCalculator.SetPerformance(
                new BigDecimal("100"), 5, true, LocalDateTime.now());
        assertThat(calculator.candidates(set)).isEmpty();
    }
}
