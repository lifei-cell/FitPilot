package com.fitpilot.infrastructure.events;

import com.fitpilot.observability.FitPilotMetrics;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.DependsOn;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import javax.sql.DataSource;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

/** Real PostgreSQL transactions; expiry is advanced in SQL instead of timing-dependent sleeps. */
@SpringJUnitConfig(OutboxLeaseIT.Config.class)
@Testcontainers(disabledWithoutDocker = true)
class OutboxLeaseIT {
    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg17").asCompatibleSubstituteFor("postgres"));

    @Autowired OutboxRepository repository;
    @Autowired JdbcTemplate jdbc;
    @Autowired DataSourceTransactionManager transactions;

    @BeforeEach
    void clearOutbox() {
        jdbc.update("DELETE FROM outbox_event");
    }

    @Test
    void staleOwnerCannotRefreshOrOverwriteNewOwnerOrSentState() {
        insert();
        var old = claim();
        expire(old.id());
        var current = claim();
        assertThat(current.claimToken()).isNotEqualTo(old.claimToken());
        var claimedState = state(current.id());

        assertThat(repository.refreshClaim(old)).isFalse();
        assertThat(repository.markSent(old)).isFalse();
        assertThat(repository.markFailed(old, new Exception("late failure"), 20)).isFalse();
        assertThat(state(current.id())).isEqualTo(claimedState);
        assertThat(repository.refreshClaim(current)).isTrue();
        assertThat(repository.markSent(current)).isTrue();
        var sentState = state(current.id());
        assertThat(sentState.get("status")).isEqualTo("SENT");
        assertThat(sentState.get("claim_token")).isNull();

        assertThat(repository.markFailed(old, new Exception("later failure"), 20)).isFalse();
        assertThat(repository.markSent(old)).isFalse();
        assertThat(repository.markSent(current)).isFalse();
        assertThat(state(current.id())).isEqualTo(sentState);
    }

    @Test
    void retriesAndManualReplayCreateNewOwnershipWithoutOldFailureOverwritingThem() {
        UUID eventId = insert();
        var first = claim();
        assertThat(repository.markFailed(first, new Exception("offline"), 2)).isTrue();
        assertThat(state(first.id())).containsEntry("status", "PENDING").containsEntry("retry_count", 1);
        assertThat(repository.claimBatch(1, 30, 2)).isEmpty();
        jdbc.update("UPDATE outbox_event SET next_attempt_at=CURRENT_TIMESTAMP - INTERVAL '1 second'");
        var retry = claim();
        assertThat(retry.claimToken()).isNotEqualTo(first.claimToken());
        assertThat(repository.markFailed(retry, new Exception("still offline"), 2)).isTrue();
        assertThat(state(first.id())).containsEntry("status", "FAILED").containsEntry("retry_count", 2);
        assertThat(repository.replayFailed(eventId)).isEqualTo(1);
        var replay = claim();
        var replayState = state(replay.id());
        assertThat(repository.markSent(retry)).isFalse();
        assertThat(repository.markFailed(first, new Exception("late"), 2)).isFalse();
        assertThat(state(replay.id())).isEqualTo(replayState);
        assertThat(repository.markSent(replay)).isTrue();
    }

    @Test
    void expiredLegacyClaimWithoutTokenCanBeRecovered() {
        insert();
        jdbc.update("""
                UPDATE outbox_event SET status='SENDING', claimed_at=CURRENT_TIMESTAMP - INTERVAL '60 seconds'
                """);
        var recovered = claim();
        assertThat(recovered.claimToken()).isNotNull();
        assertThat(repository.markSent(recovered)).isTrue();
    }

    @Test
    void claimantSkipsRowLockedByAnotherTransaction() throws Exception {
        insert();
        insert();
        long firstId = jdbc.queryForObject("SELECT MIN(id) FROM outbox_event", Long.class);
        try (var executor = Executors.newSingleThreadExecutor()) {
            new TransactionTemplate(transactions).executeWithoutResult(status -> {
                jdbc.queryForObject("SELECT id FROM outbox_event WHERE id=? FOR UPDATE", Long.class, firstId);
                var other = executor.submit(() -> repository.claimBatch(2, 30, 20));
                try {
                    var rows = other.get(5, TimeUnit.SECONDS);
                    assertThat(rows).hasSize(1);
                    assertThat(rows.getFirst().id()).isNotEqualTo(firstId);
                } catch (Exception failure) {
                    throw new AssertionError("claim should skip the locked row without waiting", failure);
                }
            });
        }
        assertThat(claim().id()).isEqualTo(firstId);
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    @SuppressWarnings("unchecked")
    void blockedSendReturningAfterReclaimCannotChangeNewOwner(boolean succeeds) throws Exception {
        insert();
        var kafka = (KafkaTemplate<String, String>) mock(KafkaTemplate.class);
        var metrics = mock(FitPilotMetrics.class);
        var properties = new EventProperties();
        properties.getRelay().setSendTimeoutSeconds(15);
        var send = new CompletableFuture<SendResult<String, String>>();
        var enteredSend = new CountDownLatch(1);
        when(kafka.send(anyString(), anyString(), anyString())).thenAnswer(invocation -> {
            enteredSend.countDown();
            return send;
        });
        var relay = new OutboxRelay(repository, kafka, properties, metrics);
        try (var executor = Executors.newSingleThreadExecutor()) {
            var running = executor.submit(relay::relay);
            try {
                assertThat(enteredSend.await(5, TimeUnit.SECONDS)).isTrue();
                long id = jdbc.queryForObject("SELECT id FROM outbox_event", Long.class);
                expire(id);
                var newOwner = claim();
                var expected = state(id);
                if (succeeds) send.complete(null);
                else send.completeExceptionally(new IllegalStateException("late broker failure"));
                running.get(5, TimeUnit.SECONDS);

                assertThat(state(id)).isEqualTo(expected);
                verify(metrics).outbox("CLAIM_LOST");
                verify(metrics, never()).outbox("SENT");
                verify(metrics, never()).outbox("FAILED");
                assertThat(repository.markSent(newOwner)).isTrue();
            } finally {
                send.completeExceptionally(new IllegalStateException("test cleanup"));
            }
        }
    }

    private UUID insert() {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO outbox_event(event_id, aggregate_type, aggregate_id, event_type, topic, event_key, payload)
                VALUES (?, 'Workout', '1', 'WorkoutCompletedEvent', 'test', '1', '{}')
                """, id);
        return id;
    }

    private OutboxEvent claim() {
        var rows = repository.claimBatch(1, 30, 20);
        assertThat(rows).hasSize(1);
        return rows.getFirst();
    }

    private void expire(long id) {
        jdbc.update("UPDATE outbox_event SET claimed_at=CURRENT_TIMESTAMP - INTERVAL '60 seconds' WHERE id=?", id);
    }

    private Map<String, Object> state(long id) {
        return jdbc.queryForMap("SELECT * FROM outbox_event WHERE id=?", id);
    }

    @Configuration
    @EnableTransactionManagement
    static class Config {
        @Bean DataSource dataSource() {
            return new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        }
        @Bean(initMethod = "migrate") Flyway flyway(DataSource source) {
            return Flyway.configure().dataSource(source).load();
        }
        @Bean JdbcTemplate jdbcTemplate(DataSource source) { return new JdbcTemplate(source); }
        @Bean DataSourceTransactionManager transactionManager(DataSource source) {
            return new DataSourceTransactionManager(source);
        }
        @Bean @DependsOn("flyway") OutboxRepository outboxRepository(JdbcTemplate jdbc) {
            return new OutboxRepository(jdbc);
        }
    }
}
