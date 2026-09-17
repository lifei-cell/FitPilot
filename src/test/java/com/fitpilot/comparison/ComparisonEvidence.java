package com.fitpilot.comparison;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Explicit experiment output, never a production metric or performance gate. */
final class ComparisonEvidence {
    private ComparisonEvidence() {}

    static Map<String, Object> timings(List<Double> milliseconds) {
        double[] sorted = milliseconds.stream().mapToDouble(Double::doubleValue).sorted().toArray();
        if (sorted.length == 0) throw new IllegalArgumentException("empty sample");
        var result = new LinkedHashMap<String, Object>();
        result.put("samples", sorted.length);
        result.put("p50Ms", percentile(sorted, .5));
        result.put("p95Ms", percentile(sorted, .95));
        result.put("p99Ms", percentile(sorted, .99));
        result.put("totalMs", Arrays.stream(sorted).sum());
        result.put("rawMs", milliseconds);
        return result;
    }

    static double elapsed(long start) { return (System.nanoTime() - start) / 1_000_000.0; }

    static void write(String name, Object result) throws Exception {
        String configured = System.getenv("FITPILOT_COMPARISON_OUTPUT");
        Path directory = Path.of(configured == null ? "target/architecture-comparison" : configured);
        Files.createDirectories(directory);
        new ObjectMapper().writerWithDefaultPrettyPrinter().writeValue(directory.resolve(name + ".json").toFile(), result);
    }

    private static double percentile(double[] sorted, double quantile) {
        return sorted[Math.max(0, (int) Math.ceil(sorted.length * quantile) - 1)];
    }
}
