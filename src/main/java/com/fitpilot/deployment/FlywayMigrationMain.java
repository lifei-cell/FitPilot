package com.fitpilot.deployment;

import org.flywaydb.core.Flyway;

public final class FlywayMigrationMain {
    private FlywayMigrationMain() {
    }

    public static void main(String[] args) {
        String url = required("DB_URL");
        String username = required("DB_USERNAME");
        String password = required("DB_PASSWORD");
        Flyway.configure()
                .dataSource(url, username, password)
                .validateMigrationNaming(true)
                .load()
                .migrate();
    }

    private static String required(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(name + " must be set");
        }
        return value;
    }
}
