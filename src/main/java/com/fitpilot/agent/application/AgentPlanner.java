package com.fitpilot.agent.application;

import org.springframework.stereotype.Component;

import java.util.*;

@Component
public class AgentPlanner {
    public Decision decide(String input) {
        String q = input.toLowerCase(Locale.ROOT);
        if (contains(q, "制定", "生成", "创建", "新计划", "make a plan", "create plan"))
            return new Decision("CREATE_PLAN", List.of("get_user_profile", "get_workout_history", "get_personal_records", "get_training_plan", "get_training_volume", "search_knowledge", "create_training_plan"));
        if (contains(q, "调整计划", "修改计划", "update plan"))
            return new Decision("ADJUST_PLAN", List.of("get_training_adjustment_context", "search_knowledge", "adjust_training_plan"));
        if (contains(q, "pr", "个人纪录", "个人记录", "最好成绩"))
            return new Decision("PERSONAL_RECORD", List.of("get_personal_records"));
        if (contains(q, "训练量", "容量", "volume"))
            return new Decision("TRAINING_VOLUME", List.of("get_training_volume"));
        if (contains(q, "历史", "最近训练", "表现", "workout"))
            return new Decision("WORKOUT_HISTORY", List.of("get_workout_history", "get_personal_records", "get_training_volume"));
        if (contains(q, "计划", "plan"))
            return new Decision("VIEW_PLAN", List.of("get_training_plan"));
        if (contains(q, "动作", "知识", "怎么练", "姿势", "exercise"))
            return new Decision("KNOWLEDGE", List.of("search_knowledge"));
        return new Decision("PROFILE", List.of("get_user_profile"));
    }
    private boolean contains(String input, String... terms) { return Arrays.stream(terms).anyMatch(input::contains); }
    public record Decision(String intent, List<String> tools) {}
}
