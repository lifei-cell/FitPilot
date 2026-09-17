package com.fitpilot.comparison;

import com.fitpilot.infrastructure.events.*;
import com.fitpilot.workout.application.WorkoutService;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.TopicPartition;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.time.Duration;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

/** Test-only dispatch adapters. All variants retain the same completion/Outbox/Inbox business code. */
final class EventComparison {
    private final JdbcTemplate jdbc;
    private final WorkoutService workouts;
    private final TransactionTemplate transaction;
    private final OutboxRepository outbox;
    private final WorkoutCompletedEventHandler workoutHandler;
    private final PersonalRecordEventHandler recordHandler;
    private final OutboxRelay relay;
    private final String bootstrap;

    EventComparison(JdbcTemplate jdbc, WorkoutService workouts, PlatformTransactionManager transactions,
                    OutboxRepository outbox, WorkoutCompletedEventHandler workoutHandler,
                    PersonalRecordEventHandler recordHandler, OutboxRelay relay, String bootstrap) {
        this.jdbc = jdbc; this.workouts = workouts; this.transaction = new TransactionTemplate(transactions);
        this.outbox = outbox; this.workoutHandler = workoutHandler; this.recordHandler = recordHandler;
        this.relay = relay; this.bootstrap = bootstrap;
    }

    void run() throws Exception {
        try (var admin = AdminClient.create(Map.of("bootstrap.servers", bootstrap))) {
            admin.createTopics(List.of(new NewTopic(EventTopics.WORKOUT_COMPLETED, 1, (short) 1),
                    new NewTopic(EventTopics.PERSONAL_RECORD_CREATED, 1, (short) 1))).all().get();
        }
        List<Map<String, Object>> results = new ArrayList<>();
        List<String> modes = List.of("SYNC_SAME_TRANSACTION", "PG_TASK_WORKER", "KAFKA_DISPATCH");
        for (int round = 0; round < 3; round++) {
            for (int offset = 0; offset < 3; offset++) {
                String mode = modes.get((round + offset) % 3);
                // This class runs only against freshly-created Testcontainers. Reset fixture size for every variant.
                jdbc.execute("TRUNCATE users, outbox_event, processed_event RESTART IDENTITY CASCADE");
                try (var consumer = consumer()) {
                    // Warm each path before measuring; the fresh consumer begins after previous trials.
                    var warm = seed(); complete(mode, warm); drain(mode, consumer);
                    List<long[]> fixtures = new ArrayList<>();
                    for (int i = 0; i < 20; i++) fixtures.add(seed());
                    List<Double> samples = new ArrayList<>();
                    long allStarted = System.nanoTime();
                    for (long[] fixture : fixtures) {
                        long start = System.nanoTime(); complete(mode, fixture);
                        samples.add(ComparisonEvidence.elapsed(start));
                    }
                    long queued = outbox.pendingCount();
                    long recoveryStarted = System.nanoTime();
                    drain(mode, consumer);
                    double drainMs = ComparisonEvidence.elapsed(recoveryStarted);
                    double burstAndDrainMs = ComparisonEvidence.elapsed(allStarted);
                    for (long[] fixture : fixtures) verify(fixture);
                    var row = new LinkedHashMap<String, Object>(ComparisonEvidence.timings(samples));
                    row.put("mode", mode); row.put("round", round + 1); row.put("pendingAfterBurst", queued);
                    row.put("drainMs", drainMs); row.put("burstAndDrainMs", burstAndDrainMs);
                    row.put("checkedWorkouts", fixtures.size()); results.add(row);
                }
            }
        }
        // A real database projection failure, not an injected delay disguised as benchmark improvement.
        jdbc.execute("""
                CREATE FUNCTION comparison_fail_projection() RETURNS trigger LANGUAGE plpgsql AS $$
                BEGIN RAISE EXCEPTION 'comparison downstream unavailable'; END $$
                """);
        jdbc.execute("CREATE TRIGGER comparison_failure BEFORE INSERT ON workout_analytics_projection "
                + "FOR EACH ROW EXECUTE FUNCTION comparison_fail_projection()");
        var sync = seed(); var async = seed();
        try {
            assertThatThrownBy(() -> complete("SYNC_SAME_TRANSACTION", sync)).isInstanceOf(RuntimeException.class);
            assertThat(state(sync)).isEqualTo("IN_PROGRESS");
            complete("PG_TASK_WORKER", async);
            assertThat(state(async)).isEqualTo("COMPLETED");
            assertThatThrownBy(this::drainPg).isInstanceOf(RuntimeException.class);
            assertThat(state(async)).isEqualTo("COMPLETED");
        } finally { jdbc.execute("DROP TRIGGER comparison_failure ON workout_analytics_projection"); }
        // Reclaim the interrupted task immediately for the bounded failure drill; production waits for its lease.
        jdbc.update("UPDATE outbox_event SET claimed_at=CURRENT_TIMESTAMP-INTERVAL '60 seconds' WHERE status='SENDING'");
        drainPg(); verify(async);
        ComparisonEvidence.write("events", Map.of("rows", results,
                "fault", Map.of("syncStateAfterFailure", state(sync), "asyncStateAfterRecovery", state(async)),
                "scope", "20-workout sequential burst, one dispatcher, one Kafka partition/topic; dispatch adapters reuse real handlers",
                "boundary", "SYNC retains Outbox/Inbox overhead for controlled comparison; PG reuses Outbox as task table; not minimal CRUD or live listener throughput",
                "faultBoundary", "projection failure injected for SYNC and PG; not a Kafka broker outage drill"));
    }

    private void complete(String mode, long[] fixture) {
        if (mode.equals("SYNC_SAME_TRANSACTION")) transaction.executeWithoutResult(status -> {
            workouts.complete(fixture[0], fixture[1]); drainSync();
        });
        else workouts.complete(fixture[0], fixture[1]);
    }

    private void drainSync() {
        while (true) {
            var rows = jdbc.queryForList("SELECT id,topic,payload::text AS payload FROM outbox_event WHERE status='PENDING' ORDER BY id");
            if (rows.isEmpty()) return;
            for (var row : rows) {
                dispatch((String) row.get("topic"), (String) row.get("payload"));
                jdbc.update("UPDATE outbox_event SET status='SENT',sent_at=CURRENT_TIMESTAMP WHERE id=?", row.get("id"));
            }
        }
    }

    private void drainPg() {
        for (int batch = 0; batch < 100; batch++) {
            var claimed = outbox.claimBatch(100, 30, 20);
            if (claimed.isEmpty()) return;
            for (var event : claimed) {
                assertThat(outbox.refreshClaim(event)).isTrue();
                dispatch(event.topic(), event.payload());
                assertThat(outbox.markSent(event)).isTrue();
            }
        }
        throw new AssertionError("PG tasks did not converge");
    }

    private void drain(String mode, KafkaConsumer<String, String> consumer) {
        if (!mode.equals("KAFKA_DISPATCH")) { drainPg(); return; }
        long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
        while (System.nanoTime() < deadline) {
            relay.relay();
            for (var event : consumer.poll(Duration.ofMillis(20))) dispatch(event.topic(), event.value());
            consumer.commitSync();
            // Both the transport and all notification projections must converge.
            long records = jdbc.queryForObject("SELECT count(*) FROM personal_record", Long.class);
            long notifications = jdbc.queryForObject("SELECT count(*) FROM user_notification", Long.class);
            long completed = jdbc.queryForObject("SELECT count(*) FROM workout WHERE status='COMPLETED'", Long.class);
            long projected = jdbc.queryForObject("SELECT count(*) FROM workout_analytics_projection", Long.class);
            if (outbox.pendingCount() == 0 && records == notifications && completed == projected) return;
        }
        throw new AssertionError("Kafka projections did not converge");
    }

    private KafkaConsumer<String, String> consumer() {
        var consumer = new KafkaConsumer<String, String>(Map.of("bootstrap.servers", bootstrap,
                "group.id", "comparison-" + UUID.randomUUID(), "enable.auto.commit", "false",
                "key.deserializer", "org.apache.kafka.common.serialization.StringDeserializer",
                "value.deserializer", "org.apache.kafka.common.serialization.StringDeserializer"));
        consumer.assign(List.of(new TopicPartition(EventTopics.WORKOUT_COMPLETED, 0),
                new TopicPartition(EventTopics.PERSONAL_RECORD_CREATED, 0)));
        consumer.seekToEnd(consumer.assignment());
        for (var partition : consumer.assignment()) consumer.position(partition);
        return consumer;
    }

    private void dispatch(String topic, String raw) {
        if (topic.equals(EventTopics.WORKOUT_COMPLETED)) {
            workoutHandler.projectPersonalRecords(raw); workoutHandler.projectAnalytics(raw);
        } else if (topic.equals(EventTopics.PERSONAL_RECORD_CREATED)) recordHandler.notifyUser(raw);
        else throw new AssertionError("unexpected topic " + topic);
    }

    private long[] seed() {
        long user = jdbc.queryForObject("INSERT INTO users(username,password_hash) VALUES (?,'test') RETURNING id",
                Long.class, UUID.randomUUID().toString());
        long workout = jdbc.queryForObject("INSERT INTO workout(user_id,status,started_at) VALUES (?,'IN_PROGRESS',CURRENT_TIMESTAMP) RETURNING id",
                Long.class, user);
        long exercise = jdbc.queryForObject("INSERT INTO workout_exercise(workout_id,exercise_id,sequence,exercise_name) VALUES (?,1,1,'bench') RETURNING id",
                Long.class, workout);
        jdbc.update("INSERT INTO workout_set(workout_exercise_id,set_number,weight_kg,reps,completed_at) VALUES (?,1,80,5,CURRENT_TIMESTAMP)", exercise);
        return new long[]{user, workout};
    }

    private String state(long[] fixture) {
        return jdbc.queryForObject("SELECT status FROM workout WHERE id=?", String.class, fixture[1]);
    }

    private void verify(long[] fixture) {
        assertThat(state(fixture)).isEqualTo("COMPLETED");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM personal_record WHERE workout_id=?", Integer.class, fixture[1])).isEqualTo(4);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM user_notification WHERE user_id=?", Integer.class, fixture[0])).isEqualTo(4);
        assertThat(jdbc.queryForObject("SELECT training_volume FROM workout_analytics_projection WHERE workout_id=?",
                java.math.BigDecimal.class, fixture[1])).isEqualByComparingTo("400");
    }
}
