package com.fitpilot.evaluation.application;

import com.fitpilot.evaluation.config.EvaluationProperties;
import com.fitpilot.evaluation.infrastructure.EvaluationRepository;
import com.fitpilot.llm.application.PromptRegistry;
import com.fitpilot.rag.application.RagFeedbackService;
import com.fitpilot.rag.application.RagExperimentCatalog;
import org.junit.jupiter.api.Test;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;

class EvaluationServiceLifecycleTest {
    @Test
    void marksClaimedRunRejectedWhenExecutorQueueIsFull() {
        EvaluationRepository repository = mock(EvaluationRepository.class);
        ThreadPoolTaskExecutor executor = mock(ThreadPoolTaskExecutor.class);
        PromptRegistry prompts = mock(PromptRegistry.class);
        EvaluationProperties properties = new EvaluationProperties();
        when(prompts.version()).thenReturn("test");
        when(repository.reserve(any(), eq(EvaluationRepository.RunType.AGENT), anyString(), anyInt()))
                .thenAnswer(invocation -> Optional.of(new EvaluationRepository.PendingRun(
                        invocation.getArgument(0), EvaluationRepository.RunType.AGENT,
                        "RULE_WORKFLOW", List.of())));
        doThrow(new TaskRejectedException("queue full")).when(executor).execute(any(Runnable.class));
        EvaluationTaskScheduler scheduler = new EvaluationTaskScheduler(repository,
                mock(AgentEvaluationRunner.class), mock(RagEvaluationRunner.class), executor,
                heartbeatScheduler(), properties);
        EvaluationService service = new EvaluationService(mock(EvaluationDatasetLoader.class), repository,
                prompts, mock(RagFeedbackService.class), scheduler, properties,
                mock(RagExperimentCatalog.class));

        var id = service.startAgent("RULE_WORKFLOW");

        verify(repository).reject(eq(id), eq(EvaluationRepository.RunType.AGENT), anyString(),
                eq("evaluation executor queue is full"));
    }

    private TaskScheduler heartbeatScheduler() {
        TaskScheduler scheduler = mock(TaskScheduler.class);
        when(scheduler.scheduleAtFixedRate(any(Runnable.class), any(java.time.Duration.class)))
                .thenReturn(mock(java.util.concurrent.ScheduledFuture.class));
        return scheduler;
    }
}
