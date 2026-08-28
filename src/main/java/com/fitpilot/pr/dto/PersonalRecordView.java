package com.fitpilot.pr.dto;

import com.fitpilot.pr.domain.PersonalRecord;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public record PersonalRecordView(long id, long exerciseId, String recordType, BigDecimal weightKg, Integer reps,
                                 BigDecimal estimated1rm, long workoutId, long workoutSetId, LocalDateTime achievedAt) {
    public static PersonalRecordView from(PersonalRecord record) {
        return new PersonalRecordView(record.id, record.exerciseId, record.recordType, record.weightKg, record.reps,
                record.estimated1rm, record.workoutId, record.workoutSetId, record.achievedAt);
    }
}
