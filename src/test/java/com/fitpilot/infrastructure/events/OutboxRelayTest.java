package com.fitpilot.infrastructure.events;

import com.fitpilot.observability.FitPilotMetrics;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class OutboxRelayTest {
    private final OutboxRepository repository = mock(OutboxRepository.class);
    @SuppressWarnings("unchecked")
    private final KafkaTemplate<String, String> kafka = mock(KafkaTemplate.class);
    private final FitPilotMetrics metrics = mock(FitPilotMetrics.class);
    private final OutboxRelay relay = new OutboxRelay(repository, kafka, new EventProperties(), metrics);
    private final OutboxEvent event = new OutboxEvent(1, "test", "1", "{}", 0, UUID.randomUUID());

    @Test
    void skipsQueuedEventThatWasReclaimedBeforeSending() {
        when(repository.claimBatch(1, 30, 20)).thenReturn(List.of(event), List.of());
        when(repository.refreshClaim(event)).thenReturn(false);
        relay.relay();
        verifyNoInteractions(kafka);
        verify(metrics).outbox("CLAIM_LOST");
        verify(repository, never()).markSent(any());
        verify(repository, never()).markFailed(any(), any(), anyInt());
    }

    @Test
    @SuppressWarnings("unchecked")
    void interruptionPreservesInterruptAndStopsSendingRemainingBatch() throws Exception {
        var next = new OutboxEvent(2, "test", "2", "{}", 0, UUID.randomUUID());
        when(repository.claimBatch(1, 30, 20)).thenReturn(List.of(event), List.of(next));
        when(repository.refreshClaim(event)).thenReturn(true);
        var future = (CompletableFuture<SendResult<String, String>>) mock(CompletableFuture.class);
        when(kafka.send(event.topic(), event.eventKey(), event.payload())).thenReturn(future);
        when(future.get(5, TimeUnit.SECONDS)).thenThrow(new InterruptedException("shutdown"));
        when(repository.markFailed(eq(event), any(), eq(20))).thenReturn(true);
        try {
            relay.relay();
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
            verify(repository, never()).refreshClaim(next);
            verify(metrics).outbox("FAILED");
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void claimsNextEventOnlyAfterCurrentSendIsCommitted() throws Exception {
        var next = new OutboxEvent(2, "test", "2", "{}", 0, UUID.randomUUID());
        when(repository.claimBatch(1, 30, 20)).thenReturn(List.of(event), List.of(next), List.of());
        when(repository.refreshClaim(any())).thenReturn(true);
        when(kafka.send(anyString(), anyString(), anyString()))
                .thenReturn(CompletableFuture.completedFuture(null));
        when(repository.markSent(any())).thenReturn(true);

        relay.relay();

        var order = inOrder(repository, kafka);
        order.verify(repository).claimBatch(1, 30, 20);
        order.verify(kafka).send(event.topic(), event.eventKey(), event.payload());
        order.verify(repository).markSent(event);
        order.verify(repository).claimBatch(1, 30, 20);
        order.verify(kafka).send(next.topic(), next.eventKey(), next.payload());
        order.verify(repository).markSent(next);
        order.verify(repository).claimBatch(1, 30, 20);
    }
}
