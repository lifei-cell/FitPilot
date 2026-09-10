package com.fitpilot.llm.infrastructure;

import com.fitpilot.llm.config.LlmProperties;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

@Component
public class LlmResiliencePolicy {
    private final LlmProperties properties;
    private final Map<String, Circuit> circuits = new ConcurrentHashMap<>();
    private final Semaphore bulkhead;

    public LlmResiliencePolicy(LlmProperties properties) {
        this.properties = properties;
        this.bulkhead = new Semaphore(properties.getMaxConcurrentRequests(), true);
    }

    public long deadline() {
        return System.nanoTime() + TimeUnit.SECONDS.toNanos(properties.getTotalTimeoutSeconds());
    }

    public void acquireBulkhead() {
        try {
            if (!bulkhead.tryAcquire(properties.getBulkheadAcquireTimeoutMs(), TimeUnit.MILLISECONDS)) {
                throw new OpenAiCompatibleClient.LlmUnavailableException("LLM bulkhead is full");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new OpenAiCompatibleClient.LlmUnavailableException("LLM request cancelled", exception);
        }
    }

    public void releaseBulkhead() { bulkhead.release(); }

    public CircuitPermit acquireCircuit(String endpointName) {
        return circuits.computeIfAbsent(endpointName, ignored -> new Circuit()).acquire();
    }

    public void success(String endpointName, CircuitPermit permit) {
        circuits.computeIfAbsent(endpointName, ignored -> new Circuit()).success(permit);
    }

    public boolean failure(String endpointName, CircuitPermit permit) {
        return circuits.computeIfAbsent(endpointName, ignored -> new Circuit())
                .failure(permit, properties.getCircuitFailureThreshold(), properties.getCircuitOpenSeconds());
    }

    public void ensureActive(long deadline) {
        if (Thread.currentThread().isInterrupted()) {
            throw new OpenAiCompatibleClient.LlmUnavailableException("LLM request cancelled");
        }
        if (System.nanoTime() >= deadline) {
            throw new OpenAiCompatibleClient.LlmUnavailableException("LLM deadline exceeded");
        }
    }

    public void sleepBeforeRetry(long retryAfterMs, int attempt, long deadline) {
        long jitteredBackoff = (long) (100 * Math.pow(2, attempt))
                + ThreadLocalRandom.current().nextLong(100);
        long delay = retryAfterMs >= 0 ? retryAfterMs : jitteredBackoff;
        long remainingMs = TimeUnit.NANOSECONDS.toMillis(Math.max(0, deadline - System.nanoTime()));
        if (remainingMs <= 0 || delay >= remainingMs) {
            throw new OpenAiCompatibleClient.LlmUnavailableException("LLM deadline exceeded");
        }
        try {
            Thread.sleep(delay);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new OpenAiCompatibleClient.LlmUnavailableException("LLM request cancelled", exception);
        }
    }

    public String circuitState(String endpointName) {
        Circuit circuit = circuits.get(endpointName);
        return circuit == null ? CircuitState.CLOSED.name() : circuit.state();
    }

    public int availablePermits() { return bulkhead.availablePermits(); }

    public enum CircuitPermit { CLOSED, HALF_OPEN, DENIED }

    private enum CircuitState { CLOSED, OPEN, HALF_OPEN }

    private static final class Circuit {
        private int failures;
        private long openUntil;
        private CircuitState state = CircuitState.CLOSED;
        private boolean halfOpenProbe;

        private synchronized CircuitPermit acquire() {
            if (state == CircuitState.CLOSED) return CircuitPermit.CLOSED;
            if (state == CircuitState.OPEN && System.currentTimeMillis() >= openUntil) {
                state = CircuitState.HALF_OPEN;
                halfOpenProbe = false;
            }
            if (state == CircuitState.HALF_OPEN && !halfOpenProbe) {
                halfOpenProbe = true;
                return CircuitPermit.HALF_OPEN;
            }
            return CircuitPermit.DENIED;
        }

        private synchronized void success(CircuitPermit permit) {
            failures = 0;
            openUntil = 0;
            halfOpenProbe = false;
            state = CircuitState.CLOSED;
        }

        private synchronized boolean failure(CircuitPermit permit, int threshold, int openSeconds) {
            if (permit == CircuitPermit.HALF_OPEN || ++failures >= threshold) {
                state = CircuitState.OPEN;
                openUntil = System.currentTimeMillis() + openSeconds * 1000L;
                halfOpenProbe = false;
                failures = 0;
                return true;
            }
            return false;
        }

        private synchronized String state() {
            if (state == CircuitState.OPEN && System.currentTimeMillis() >= openUntil) {
                return CircuitState.HALF_OPEN.name();
            }
            return state.name();
        }
    }
}
