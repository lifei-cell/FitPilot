package com.fitpilot.quality;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ZeroSkippedTestsGateTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void acceptsNonEmptySuitesWithoutSkippedTests() throws Exception {
        Path unitReports = reports("surefire", 2, 0);
        Path integrationReports = reports("failsafe", 1, 0);

        assertDoesNotThrow(() -> ZeroSkippedTestsGate.main(new String[] {
                unitReports.toString(), integrationReports.toString()
        }));
    }

    @Test
    void rejectsAnySkippedTest() throws Exception {
        Path reports = reports("surefire", 2, 1);

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> ZeroSkippedTestsGate.main(new String[] {reports.toString()}));

        assertEquals("quality gate requires zero skipped tests, found 1", error.getMessage());
    }

    private Path reports(String name, int tests, int skipped) throws Exception {
        Path directory = Files.createDirectory(temporaryDirectory.resolve(name));
        Files.writeString(directory.resolve("TEST-example.xml"),
                "<testsuite tests=\"%d\" skipped=\"%d\"/>".formatted(tests, skipped));
        return directory;
    }
}
