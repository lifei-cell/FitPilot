package com.fitpilot.agent.application;

import com.fitpilot.analytics.application.AnalyticsService;
import com.fitpilot.plan.application.TrainingPlanService;
import com.fitpilot.pr.dto.PersonalRecordView;
import com.fitpilot.pr.repository.PersonalRecordRepository;
import com.fitpilot.rag.application.HybridRetrievalService;
import com.fitpilot.user.application.UserService;
import com.fitpilot.workout.application.WorkoutService;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.util.Map;

@Component
public class AgentToolExecutor {
    private final UserService users; private final WorkoutService workouts; private final PersonalRecordRepository records;
    private final TrainingPlanService plans; private final AnalyticsService analytics;
    private final ObjectProvider<HybridRetrievalService> retrieval;
    public AgentToolExecutor(UserService users, WorkoutService workouts, PersonalRecordRepository records,
                             TrainingPlanService plans, AnalyticsService analytics, ObjectProvider<HybridRetrievalService> retrieval) {
        this.users=users; this.workouts=workouts; this.records=records; this.plans=plans; this.analytics=analytics; this.retrieval=retrieval;
    }
    public Object execute(String tool, long currentUserId, String query) {
        return switch (tool) {
            case "get_user_profile" -> Map.of("profile", users.getProfile(currentUserId), "latestBodyMetric", safeMetric(currentUserId));
            case "get_workout_history" -> workouts.list(currentUserId, null, null, "COMPLETED", 1, 20);
            case "get_personal_records" -> records.findCurrent(currentUserId).stream().map(PersonalRecordView::from).toList();
            case "get_training_plan" -> safePlan(currentUserId);
            case "get_training_volume" -> analytics.overview(currentUserId);
            case "search_knowledge" -> search(query);
            default -> throw new IllegalArgumentException("unknown or non-readable tool: " + tool);
        };
    }
    private Object safeMetric(long userId) { try { return users.latestMetric(userId); } catch (RuntimeException e) { return Map.of(); } }
    private Object safePlan(long userId) { try { return plans.active(userId); } catch (RuntimeException e) { return Map.of(); } }
    private Object search(String query) {
        HybridRetrievalService service = retrieval.getIfAvailable();
        return service == null ? Map.of("available", false) : service.search(query, 5, null);
    }
}
