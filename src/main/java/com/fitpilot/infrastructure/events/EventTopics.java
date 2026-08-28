package com.fitpilot.infrastructure.events;

public final class EventTopics {
    public static final String WORKOUT_COMPLETED = "fitness.workout.completed";
    public static final String PERSONAL_RECORD_CREATED = "fitness.personal-record.created";
    public static final String TRAINING_PLAN_CREATED = "fitness.training-plan.created";
    public static final String TRAINING_PLAN_UPDATED = "fitness.training-plan.updated";

    private EventTopics() {}
}
