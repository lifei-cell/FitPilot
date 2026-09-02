package com.fitpilot.agent.product;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AgentProductMetricsServiceTest {
    private final AgentProductMetricsRepository repository = mock(AgentProductMetricsRepository.class);
    private final AgentProductMetricsService service = new AgentProductMetricsService(repository);

    @Test
    void calculatesFunnelReliabilityAndTrainingOutcomeWithStableDenominators() {
        when(repository.retention(60)).thenReturn(new AgentProductMetricsRepository.RetentionRaw(10, 4));
        when(repository.suggestionFunnel(60)).thenReturn(
                new AgentProductMetricsRepository.FunnelRaw(8, 3, 1, 2, 2));
        when(repository.reliability(60)).thenReturn(new AgentProductMetricsRepository.ReliabilityRaw(
                5, 4, 1, new BigDecimal("0.12000000")));
        when(repository.executionBreakdown(60)).thenReturn(List.of(
                new AgentProductMetricsRepository.BreakdownRaw("PLAN_ADJUSTMENT", "gpt-test", "v1",
                        2, 1, 1, new BigDecimal("0.04000000"))));
        var before = new AgentProductMetricsRepository.PeriodRaw(
                2, 4, 2, 2, 4, 14, new BigDecimal("1000"), 1);
        var after = new AgentProductMetricsRepository.PeriodRaw(
                2, 4, 3, 1, 4, 6, new BigDecimal("1500"), 3);
        when(repository.trainingOutcome(60, 28)).thenReturn(
                new AgentProductMetricsRepository.OutcomeRaw(2, before, after, List.of()));

        var snapshot = service.snapshot(60, 28);

        assertThat(snapshot.sessionRetention().rate()).isEqualTo(0.4);
        assertThat(snapshot.suggestionFunnel().acceptanceRate()).isEqualTo(0.75);
        assertThat(snapshot.suggestionFunnel().rejectionRate()).isEqualTo(0.25);
        assertThat(snapshot.suggestionFunnel().confirmationConversionRate()).isEqualTo(0.375);
        assertThat(snapshot.reliability().ruleFallbackRate()).isEqualTo(0.2);
        assertThat(snapshot.reliability().costPerSuccessfulExecutionUsd())
                .isEqualByComparingTo("0.03000000");
        assertThat(snapshot.executionBreakdown()).singleElement().satisfies(item -> {
            assertThat(item.successRate()).isEqualTo(0.5);
            assertThat(item.ruleFallbackRate()).isEqualTo(0.5);
            assertThat(item.costPerSuccessfulExecutionUsd()).isEqualByComparingTo("0.04000000");
        });
        assertThat(snapshot.trainingOutcome().before().completionRate()).isEqualTo(0.5);
        assertThat(snapshot.trainingOutcome().after().completionRate()).isEqualTo(0.75);
        assertThat(snapshot.trainingOutcome().delta().completionRate()).isEqualTo(0.25);
        assertThat(snapshot.trainingOutcome().delta().averagePain()).isEqualTo(-2);
        assertThat(snapshot.trainingOutcome().delta().trainingVolume()).isEqualByComparingTo("500.0000");
        assertThat(snapshot.trainingOutcome().delta().trainingVolumeRate()).isEqualTo(0.5);
        assertThat(snapshot.trainingOutcome().delta().personalRecords()).isEqualTo(2);
    }

    @Test
    void returnsZeroRatesWhenThereIsNoEligibleSampleOrSuccess() {
        when(repository.retention(28)).thenReturn(new AgentProductMetricsRepository.RetentionRaw(0, 0));
        when(repository.suggestionFunnel(28)).thenReturn(
                new AgentProductMetricsRepository.FunnelRaw(0, 0, 0, 0, 0));
        when(repository.reliability(28)).thenReturn(new AgentProductMetricsRepository.ReliabilityRaw(
                0, 0, 0, BigDecimal.ZERO));
        when(repository.executionBreakdown(28)).thenReturn(List.of());
        var empty = new AgentProductMetricsRepository.PeriodRaw(0, 0, 0, 0, 0, 0, BigDecimal.ZERO, 0);
        when(repository.trainingOutcome(28, 28)).thenReturn(
                new AgentProductMetricsRepository.OutcomeRaw(0, empty, empty, List.of()));

        var snapshot = service.snapshot(28, 28);

        assertThat(snapshot.sessionRetention().rate()).isZero();
        assertThat(snapshot.suggestionFunnel().acceptanceRate()).isZero();
        assertThat(snapshot.suggestionFunnel().rejectionRate()).isZero();
        assertThat(snapshot.suggestionFunnel().confirmationConversionRate()).isZero();
        assertThat(snapshot.reliability().ruleFallbackRate()).isZero();
        assertThat(snapshot.reliability().costPerSuccessfulExecutionUsd()).isEqualByComparingTo("0");
        assertThat(snapshot.trainingOutcome().delta().trainingVolumeRate()).isZero();
    }
}
