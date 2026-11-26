package com.fitpilot.deployment;

/** Read-only status entry point. Images without this class cannot run the preflight job. */
public final class FlywaySchemaStatusMain {
    private FlywaySchemaStatusMain() {
    }

    public static void main(String[] args) {
        boolean applied = FlywayMigrationMain.v22Applied(FlywayMigrationMain.configuredFlyway());
        System.out.println("FITPILOT_V22_APPLIED=" + applied);
    }
}
