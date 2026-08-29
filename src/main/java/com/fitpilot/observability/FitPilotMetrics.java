package com.fitpilot.observability;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Duration;

@Component
public class FitPilotMetrics {
    private final MeterRegistry registry;

    public FitPilotMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    public void agent(String status, boolean degraded, long latencyMs, int violations) {
        registry.counter("fitpilot.agent.requests", "status", status,
                "degraded", String.valueOf(degraded)).increment();
        timer("fitpilot.agent.latency", "status", status).record(Duration.ofMillis(latencyMs));
        if (violations > 0) {
            registry.counter("fitpilot.agent.rule.violations").increment(violations);
        }
    }

    public void tool(String name, String status, long latencyMs) {
        registry.counter("fitpilot.agent.tool.calls", "tool", name, "status", status).increment();
        Timer.builder("fitpilot.agent.tool.latency").tag("tool", name).tag("status", status)
                .publishPercentileHistogram().register(registry).record(Duration.ofMillis(latencyMs));
    }

    public void llm(String provider, String model, String status, long latencyMs,
                    int input, int output, BigDecimal cost) {
        registry.counter("fitpilot.llm.invocations", "provider", provider,
                "model", model, "status", status).increment();
        registry.counter("fitpilot.llm.tokens", "provider", provider,
                "model", model, "type", "input").increment(input);
        registry.counter("fitpilot.llm.tokens", "provider", provider,
                "model", model, "type", "output").increment(output);
        registry.counter("fitpilot.llm.cost.usd", "provider", provider,
                "model", model).increment(cost.doubleValue());
        Timer.builder("fitpilot.llm.latency").tag("provider", provider).tag("model", model)
                .tag("status", status).publishPercentileHistogram().register(registry)
                .record(Duration.ofMillis(latencyMs));
    }

    public void rag(String mode, String status, long latencyMs) {
        registry.counter("fitpilot.rag.requests", "mode", mode, "status", status).increment();
        Timer.builder("fitpilot.rag.latency").tag("mode", mode).tag("status", status)
                .publishPercentileHistogram().register(registry).record(Duration.ofMillis(latencyMs));
    }

    public void outbox(String status) {
        registry.counter("fitpilot.outbox.publish", "status", status).increment();
    }

    private Timer timer(String name, String tagName, String tagValue) {
        return Timer.builder(name).tag(tagName, tagValue).publishPercentileHistogram().register(registry);
    }
}
