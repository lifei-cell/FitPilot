package com.fitpilot.workout.application;

import com.fitpilot.exercise.repository.ExerciseRepository;
import com.fitpilot.plan.repository.TrainingPlanRepository;
import com.fitpilot.pr.application.PersonalRecordService;
import com.fitpilot.pr.repository.PersonalRecordRepository;
import com.fitpilot.workout.domain.Workout;
import com.fitpilot.workout.repository.WorkoutRepository;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class WorkoutServiceTest {
    @Test
    void repeatingCompleteIsIdempotentAndDoesNotRecalculateRecords() {
        WorkoutRepository workouts = mock(WorkoutRepository.class);
        PersonalRecordService recordService = mock(PersonalRecordService.class);
        PersonalRecordRepository records = mock(PersonalRecordRepository.class);
        WorkoutService service = new WorkoutService(workouts, mock(TrainingPlanRepository.class),
                mock(ExerciseRepository.class), recordService, records);
        Workout workout = new Workout();
        workout.id = 7L;
        workout.userId = 3L;
        workout.status = "COMPLETED";
        workout.startedAt = LocalDateTime.now().minusHours(1);
        workout.completedAt = LocalDateTime.now();
        workout.durationSeconds = 3600;
        when(workouts.findOwned(3L, 7L)).thenReturn(Optional.of(workout));
        when(workouts.findExercises(7L)).thenReturn(List.of());
        when(workouts.findSets(anyCollection())).thenReturn(List.of());
        when(records.countByWorkout(3L, 7L)).thenReturn(4);

        var result = service.complete(3L, 7L);

        assertThat(result.newPersonalRecords()).isEqualTo(4);
        verify(recordService, never()).calculateAndPersist(any(), anyList(), anyList());
        verify(workouts, never()).update((Workout) any());
    }
}
