package com.fitpilot.pr.domain;

import com.fitpilot.workout.domain.WorkoutSet;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;

/** Pure domain calculation with no Spring, HTTP or persistence dependencies. */
public final class PersonalRecordCalculator {
    public record Candidate(String type, BigDecimal score, BigDecimal estimated1rm) {}

    public BigDecimal estimatedOneRepMax(BigDecimal weightKg, int reps) {
        if (weightKg == null || weightKg.signum() <= 0 || reps <= 0) return BigDecimal.ZERO.setScale(2);
        return weightKg.multiply(BigDecimal.ONE.add(BigDecimal.valueOf(reps)
                        .divide(BigDecimal.valueOf(30), 10, RoundingMode.HALF_UP)))
                .setScale(2, RoundingMode.HALF_UP);
    }

    public List<Candidate> candidates(WorkoutSet set) {
        if (Boolean.TRUE.equals(set.isWarmup) || set.completedAt == null || set.weightKg == null
                || set.weightKg.signum() < 0 || set.reps == null || set.reps <= 0) return List.of();
        List<Candidate> result = new ArrayList<>();
        BigDecimal e1rm = estimatedOneRepMax(set.weightKg, set.reps);
        result.add(new Candidate("MAX_WEIGHT", set.weightKg, e1rm));
        result.add(new Candidate("ESTIMATED_1RM", e1rm, e1rm));
        result.add(new Candidate("MAX_VOLUME", set.weightKg.multiply(BigDecimal.valueOf(set.reps)), e1rm));
        String repType = switch (set.reps) {
            case 3 -> "THREE_RM";
            case 5 -> "FIVE_RM";
            case 8 -> "EIGHT_RM";
            case 10 -> "TEN_RM";
            default -> null;
        };
        if (repType != null) result.add(new Candidate(repType, set.weightKg, e1rm));
        return result;
    }
}
