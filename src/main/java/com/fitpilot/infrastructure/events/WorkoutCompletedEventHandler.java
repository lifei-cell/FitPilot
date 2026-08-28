package com.fitpilot.infrastructure.events;

import com.fitpilot.analytics.infrastructure.WorkoutAnalyticsProjectionRepository;
import com.fitpilot.pr.application.PersonalRecordService;
import com.fitpilot.workout.domain.Workout;
import com.fitpilot.workout.repository.WorkoutRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class WorkoutCompletedEventHandler {
    static final String PR_CONSUMER = "fitpilot-pr-projector-v1";
    static final String ANALYTICS_CONSUMER = "fitpilot-analytics-projector-v1";

    private final EventEnvelopeReader reader;
    private final ProcessedEventRepository processedEvents;
    private final WorkoutRepository workouts;
    private final PersonalRecordService personalRecords;
    private final WorkoutAnalyticsProjectionRepository analytics;

    public WorkoutCompletedEventHandler(EventEnvelopeReader reader, ProcessedEventRepository processedEvents,
                                        WorkoutRepository workouts, PersonalRecordService personalRecords,
                                        WorkoutAnalyticsProjectionRepository analytics) {
        this.reader = reader;
        this.processedEvents = processedEvents;
        this.workouts = workouts;
        this.personalRecords = personalRecords;
        this.analytics = analytics;
    }

    @Transactional
    public void projectPersonalRecords(String raw) {
        var event = reader.read(raw, EventTypes.WORKOUT_COMPLETED, EventPayloads.WorkoutCompleted.class);
        if (!processedEvents.claim(event.envelope().eventId(), PR_CONSUMER)) return;
        Workout workout = completedWorkout(event.payload());
        var exerciseList = workouts.findExercises(workout.id);
        personalRecords.calculateAndPersist(workout, exerciseList,
                workouts.findSets(exerciseList.stream().map(exercise -> exercise.id).toList()));
    }

    @Transactional
    public void projectAnalytics(String raw) {
        var event = reader.read(raw, EventTypes.WORKOUT_COMPLETED, EventPayloads.WorkoutCompleted.class);
        if (!processedEvents.claim(event.envelope().eventId(), ANALYTICS_CONSUMER)) return;
        completedWorkout(event.payload());
        analytics.project(event.payload().workoutId());
    }

    private Workout completedWorkout(EventPayloads.WorkoutCompleted payload) {
        Workout workout = workouts.findById(payload.workoutId())
                .orElseThrow(() -> new IllegalStateException("workout does not exist: " + payload.workoutId()));
        if (!"COMPLETED".equals(workout.status) || workout.userId != payload.userId()) {
            throw new IllegalStateException("event does not match a completed workout: " + payload.workoutId());
        }
        return workout;
    }
}
