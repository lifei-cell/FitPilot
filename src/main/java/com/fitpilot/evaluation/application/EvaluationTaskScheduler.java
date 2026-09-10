package com.fitpilot.evaluation.application;

import com.fitpilot.evaluation.config.EvaluationProperties;
import com.fitpilot.evaluation.infrastructure.EvaluationRepository;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;
import java.util.concurrent.ScheduledFuture;

@Service
public class EvaluationTaskScheduler {
    private final EvaluationRepository repository;
    private final AgentEvaluationRunner agentRunner;
    private final RagEvaluationRunner ragRunner;
    private final ThreadPoolTaskExecutor executor;
    private final TaskScheduler heartbeatScheduler;
    private final EvaluationProperties properties;
    private final Map<UUID, ActiveRun> activeRuns = new ConcurrentHashMap<>();
    private final String workerId = UUID.randomUUID().toString();

    public EvaluationTaskScheduler(EvaluationRepository repository, AgentEvaluationRunner agentRunner,
                                   RagEvaluationRunner ragRunner,
                                   @Qualifier("evaluationExecutor") ThreadPoolTaskExecutor executor,
                                   @Qualifier("evaluationHeartbeatScheduler") TaskScheduler heartbeatScheduler,
                                   EvaluationProperties properties) {
        this.repository = repository;
        this.agentRunner = agentRunner;
        this.ragRunner = ragRunner;
        this.executor = executor;
        this.heartbeatScheduler = heartbeatScheduler;
        this.properties = properties;
    }

    public void dispatch(EvaluationRepository.PendingRun requested) {
        repository.reserve(requested.id(), requested.type(), workerId, properties.getLeaseSeconds())
                .ifPresent(reserved -> {
                    ScheduledFuture<?> heartbeat = heartbeatScheduler.scheduleAtFixedRate(
                            () -> repository.renewLease(reserved.id(), reserved.type(), workerId,
                                    properties.getLeaseSeconds()),
                            Duration.ofSeconds(properties.getHeartbeatSeconds()));
                    try {
                        FutureTask<Void> task = new FutureTask<>(() -> {
                            execute(reserved, heartbeat);
                            return null;
                        });
                        activeRuns.put(reserved.id(), new ActiveRun(task, heartbeat));
                        executor.execute(task);
                    } catch (TaskRejectedException rejected) {
                        activeRuns.remove(reserved.id());
                        heartbeat.cancel(false);
                        repository.reject(reserved.id(), reserved.type(), workerId,
                                "evaluation executor queue is full");
                    }
                });
    }

    @EventListener(ApplicationReadyEvent.class)
    public void recoverOnStartup() { recover(); }

    @Scheduled(fixedDelayString = "${fitpilot.evaluation.recovery-delay-ms:5000}",
            scheduler = "evaluationHeartbeatScheduler")
    public void recover() {
        repository.timeoutExpired().forEach(id -> {
            ActiveRun active = activeRuns.remove(id);
            if (active != null) active.cancel();
        });
        repository.requeueExpiredLeases();
        repository.queued(properties.getRecoveryBatchSize()).forEach(this::dispatch);
    }

    private void execute(EvaluationRepository.PendingRun run, ScheduledFuture<?> heartbeat) {
        try {
            if (!repository.start(run.id(), run.type(), workerId)) return;
            ensureLease(run);
            Runnable leaseGuard = () -> ensureLease(run);
            if (run.type() == EvaluationRepository.RunType.AGENT) {
                agentRunner.run(run.id(), run.mode(), workerId, leaseGuard);
            } else {
                ragRunner.run(run.id(), run.ragCases(), run.ragExperiment(), workerId, leaseGuard);
            }
        } catch (Exception failure) {
            if (!Thread.currentThread().isInterrupted()) {
                repository.fail(run.id(), run.type(), workerId, failure.getMessage());
            }
        } finally {
            heartbeat.cancel(false);
            activeRuns.remove(run.id());
        }
    }

    private void ensureLease(EvaluationRepository.PendingRun run) {
        if (Thread.currentThread().isInterrupted()
                || !repository.renewLease(run.id(), run.type(), workerId, properties.getLeaseSeconds())) {
            throw new IllegalStateException("evaluation lease lost or deadline exceeded");
        }
    }

    private record ActiveRun(Future<?> future, ScheduledFuture<?> heartbeat) {
        private void cancel() {
            heartbeat.cancel(false);
            future.cancel(true);
        }
    }
}
