package com.ig.tfl.observability;

import com.ig.tfl.resilience.CircuitBreaker;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.prometheus.PrometheusConfig;
import io.micrometer.prometheus.PrometheusMeterRegistry;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

/**
 * Centralized metrics registry for Prometheus exposition.
 *
 * Exposes the 4 key metrics from SLO_DEFINITION.md:
 * - http_requests_total (counter)
 * - http_request_duration_seconds (histogram)
 * - data_freshness_seconds (gauge)
 * - circuit_breaker_state (gauge)
 */
public class Metrics {

    private final PrometheusMeterRegistry registry;
    private final AtomicLong dataFreshnessMs = new AtomicLong(0);

    public Metrics() {
        this.registry = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
    }

    /**
     * Register circuit breaker state gauge.
     * Called once at startup with reference to the circuit breaker.
     */
    public void registerCircuitBreaker(String name, Supplier<CircuitBreaker.State> stateSupplier) {
        Gauge.builder("circuit_breaker_state", stateSupplier, state -> stateToNumber(state.get()))
                .tag("name", name)
                .description("Circuit breaker state: 0=CLOSED, 1=HALF_OPEN, 2=OPEN")
                .register(registry);
    }

    /**
     * Register data freshness gauge.
     * Updated on each response via updateDataFreshness().
     */
    public void registerDataFreshness(String nodeId) {
        Gauge.builder("data_freshness_seconds", dataFreshnessMs, ms -> ms.get() / 1000.0)
                .tag("node", nodeId)
                .description("Age of served data in seconds")
                .register(registry);
    }

    /**
     * Record an HTTP request.
     */
    public void recordRequest(String method, String path, int status, Duration duration) {
        // Counter for request count
        Counter.builder("http_requests_total")
                .tag("method", method)
                .tag("path", normalizePath(path))
                .tag("status", String.valueOf(status))
                .register(registry)
                .increment();

        // Timer for duration histogram
        Timer.builder("http_request_duration_seconds")
                .tag("method", method)
                .tag("path", normalizePath(path))
                .publishPercentileHistogram()
                .register(registry)
                .record(duration);
    }

    /**
     * Update the data freshness gauge.
     */
    public void updateDataFreshness(long ageMs) {
        dataFreshnessMs.set(ageMs);
    }

    /**
     * Get Prometheus text format output for /metrics endpoint.
     */
    public String scrape() {
        return registry.scrape();
    }

    /**
     * Get the underlying registry (for testing).
     */
    public MeterRegistry getRegistry() {
        return registry;
    }

    private double stateToNumber(CircuitBreaker.State state) {
        return switch (state) {
            case CLOSED -> 0;
            case HALF_OPEN -> 1;
            case OPEN -> 2;
        };
    }

    /**
     * Normalize path to avoid high-cardinality labels.
     * /api/v1/tube/central/status -> /api/v1/tube/{lineId}/status
     */
    private String normalizePath(String path) {
        if (path == null) return "unknown";

        // Replace line IDs with placeholder
        return path.replaceAll("/tube/[^/]+/status", "/tube/{lineId}/status")
                   .replaceAll("/status/[^/]+/to/[^/]+", "/status/{from}/to/{to}");
    }
}
