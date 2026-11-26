package com.fitpilot.deployment;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationState;
import org.flywaydb.core.api.MigrationVersion;

import java.util.Arrays;

public final class FlywayMigrationMain {
    private static final MigrationVersion V22 = MigrationVersion.fromVersion("22");

    private FlywayMigrationMain() {
    }

    public static void main(String[] args) {
        Flyway flyway = configuredFlyway();
        if (!v22Applied(flyway) && !Arrays.asList(args).contains("--quiesced-for-v22")) {
            throw new IllegalStateException("V22 requires the delivery gate to quiesce the backend first");
        }
        flyway.migrate();
        if (!v22Applied(flyway)) {
            throw new IllegalStateException("Flyway completed without applying V22");
        }
    }

    static Flyway configuredFlyway() {
        if (FlywayMigrationMain.class.getResource("/db/migration/V22__isolate_rag_evaluation_corpora.sql") == null) {
            throw new IllegalStateException("Candidate image does not contain the V22 migration");
        }
        return Flyway.configure()
                .dataSource(required("DB_URL"), required("DB_USERNAME"), required("DB_PASSWORD"))
                .validateMigrationNaming(true)
                .load();
    }

    static boolean v22Applied(Flyway flyway) {
        return Arrays.stream(flyway.info().applied())
                .anyMatch(migration -> V22.equals(migration.getVersion()) &&
                        (migration.getState() == MigrationState.SUCCESS || migration.getState() == MigrationState.OUT_OF_ORDER));
    }

    private static String required(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(name + " must be set");
        }
        return value;
    }
}
