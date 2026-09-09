package com.fitpilot.plan.application;

import com.fitpilot.plan.domain.TrainingPlanValidator;
import com.fitpilot.plan.dto.TrainingPlanDtos;
import org.springframework.stereotype.Service;

@Service
public class TrainingPlanValidationService {
    public void validate(TrainingPlanDtos.CreateRequest request) {
        TrainingPlanValidator.validate(request);
    }

    public void validate(TrainingPlanDtos.UpdateRequest request) {
        TrainingPlanValidator.validate(request);
    }
}
