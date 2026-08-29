package com.fitpilot.observability;

import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.stereotype.Component;

@Aspect
@Component
public class RepositoryObservationAspect {
    private final ObservationRegistry registry;

    public RepositoryObservationAspect(ObservationRegistry registry) {
        this.registry = registry;
    }

    @Around("@within(org.springframework.stereotype.Repository)")
    public Object observeRepositoryCall(ProceedingJoinPoint joinPoint) throws Throwable {
        String operation = joinPoint.getSignature().getDeclaringType().getSimpleName()
                + "." + joinPoint.getSignature().getName();
        Observation observation = Observation.start("fitpilot.database", registry)
                .contextualName(operation)
                .lowCardinalityKeyValue("db.system", "postgresql")
                .lowCardinalityKeyValue("db.operation", joinPoint.getSignature().getName());
        try (Observation.Scope ignored = observation.openScope()) {
            return joinPoint.proceed();
        } catch (Throwable failure) {
            observation.error(failure);
            throw failure;
        } finally {
            observation.stop();
        }
    }
}
