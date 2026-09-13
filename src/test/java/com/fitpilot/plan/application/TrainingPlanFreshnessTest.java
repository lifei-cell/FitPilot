package com.fitpilot.plan.application;

import com.fitpilot.exercise.application.ExerciseService;
import com.fitpilot.infrastructure.events.EventOutboxService;
import com.fitpilot.infrastructure.performance.TwoLevelCache;
import com.fitpilot.plan.domain.TrainingPlan;
import com.fitpilot.plan.repository.TrainingPlanRepository;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class TrainingPlanFreshnessTest {
    @Test
    void confirmationChecksCurrentOwnedDatabaseVersionWithoutConsultingDisplayCache() {
        var repository = mock(TrainingPlanRepository.class);
        var cache = mock(TwoLevelCache.class);
        var service = new TrainingPlanService(repository, mock(ExerciseService.class), cache,
                mock(EventOutboxService.class), mock(TrainingPlanValidationService.class));
        var plan = new TrainingPlan();
        plan.id = 42L;
        plan.version = 2;
        when(repository.findActive(7)).thenReturn(Optional.of(plan));
        when(repository.findActive(8)).thenReturn(Optional.empty());

        assertThat(service.isCurrentActiveVersion(7, 42, 1)).isFalse();
        assertThat(service.isCurrentActiveVersion(7, 41, 2)).isFalse();
        assertThat(service.isCurrentActiveVersion(7, 42, 2)).isTrue();
        assertThat(service.isCurrentActiveVersion(8, 42, 2)).isFalse();
        verifyNoInteractions(cache);
    }
}
