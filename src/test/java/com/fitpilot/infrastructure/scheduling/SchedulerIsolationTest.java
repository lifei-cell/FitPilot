package com.fitpilot.infrastructure.scheduling;

import com.fitpilot.evaluation.config.EvaluationConfig;
import com.fitpilot.evaluation.config.EvaluationProperties;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class SchedulerIsolationTest {
    @Test
    void evaluationHeartbeatsContinueWhileBothBackgroundThreadsAreBlocked() throws InterruptedException {
        var jobs = new BlockingBackgroundJobs();
        try (var context = new AnnotationConfigApplicationContext()) {
            context.register(BackgroundSchedulingConfig.class, EvaluationConfig.class, EvaluationProperties.class);
            context.registerBean(BlockingBackgroundJobs.class, () -> jobs);
            context.refresh();
            try {
                assertThat(jobs.started.await(5, TimeUnit.SECONDS)).as("both background jobs started").isTrue();

                var heartbeats = new CountDownLatch(2);
                var scheduler = context.getBean("evaluationHeartbeatScheduler", TaskScheduler.class);
                var heartbeat = scheduler.scheduleAtFixedRate(heartbeats::countDown, Duration.ofSeconds(1));
                try {
                    assertThat(heartbeats.await(4, TimeUnit.SECONDS))
                            .as("evaluation heartbeats ran while both background workers were occupied").isTrue();
                } finally {
                    heartbeat.cancel(false);
                }
            } finally {
                jobs.release.countDown();
            }
        }
    }

    @EnableScheduling
    static class BlockingBackgroundJobs {
        final CountDownLatch started = new CountDownLatch(2);
        final CountDownLatch release = new CountDownLatch(1);

        @Scheduled(fixedDelay = 60_000)
        void relay() throws InterruptedException { block(); }

        @Scheduled(fixedDelay = 60_000)
        void retryIndexing() throws InterruptedException { block(); }

        private void block() throws InterruptedException {
            started.countDown();
            release.await();
        }
    }
}
