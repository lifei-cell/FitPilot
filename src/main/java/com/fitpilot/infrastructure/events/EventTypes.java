package com.fitpilot.infrastructure.events;

public final class EventTypes {
    public static final String WORKOUT_COMPLETED = "WorkoutCompletedEvent";
    public static final String PERSONAL_RECORD_CREATED = "PersonalRecordCreatedEvent";
    public static final String TRAINING_PLAN_CREATED = "TrainingPlanCreatedEvent";
    public static final String TRAINING_PLAN_UPDATED = "TrainingPlanUpdatedEvent";

    private EventTypes() {}

    public static String topicFor(String eventType) {
        return switch (eventType) {
            case WORKOUT_COMPLETED -> EventTopics.WORKOUT_COMPLETED;
            case PERSONAL_RECORD_CREATED -> EventTopics.PERSONAL_RECORD_CREATED;
            case TRAINING_PLAN_CREATED -> EventTopics.TRAINING_PLAN_CREATED;
            case TRAINING_PLAN_UPDATED -> EventTopics.TRAINING_PLAN_UPDATED;
            default -> throw new IllegalArgumentException("unsupported event type: " + eventType);
        };
    }
}
