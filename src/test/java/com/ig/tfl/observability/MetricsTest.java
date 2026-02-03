package com.ig.tfl.observability;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for Prometheus metrics instrumentation.
 */
class MetricsTest {

    private Metrics metrics;

    @BeforeEach
    void setUp() {
        metrics = new Metrics();
    }

    @Test
    void recordsHttpRequestCounter() {
        metrics.recordRequest("GET", "/api/v1/tube/status", 200, Duration.ofMillis(50));
        metrics.recordRequest("GET", "/api/v1/tube/status", 200, Duration.ofMillis(30));
        metrics.recordRequest("GET", "/api/v1/tube/status", 500, Duration.ofMillis(100));

        String scrape = metrics.scrape();

        // Check counter exists with correct labels
        assertThat(scrape).contains("http_requests_total");
        assertThat(scrape).contains("method=\"GET\"");
        assertThat(scrape).contains("path=\"/api/v1/tube/status\"");
        assertThat(scrape).contains("status=\"200\"");
        assertThat(scrape).contains("status=\"500\"");
    }

    @Test
    void recordsHttpRequestDurationHistogram() {
        metrics.recordRequest("GET", "/api/v1/tube/status", 200, Duration.ofMillis(50));

        String scrape = metrics.scrape();

        // Check histogram buckets exist
        assertThat(scrape).contains("http_request_duration_seconds_bucket");
        assertThat(scrape).contains("http_request_duration_seconds_count");
        assertThat(scrape).contains("http_request_duration_seconds_sum");
    }

    @Test
    void recordsDataFreshnessGauge() {
        metrics.registerDataFreshness("test-node");
        metrics.updateDataFreshness(15000); // 15 seconds

        String scrape = metrics.scrape();

        assertThat(scrape).contains("data_freshness_seconds");
        assertThat(scrape).contains("node=\"test-node\"");
        // 15000ms = 15.0 seconds
        assertThat(scrape).contains("15.0");
    }

    @Test
    void recordsCircuitBreakerStateGauge() {
        // Use a simple String supplier for testing
        AtomicReference<String> state = new AtomicReference<>("CLOSED");
        metrics.registerCircuitBreaker("test-cb", state::get);

        String scrape = metrics.scrape();

        // Should be CLOSED (0) initially
        assertThat(scrape).contains("circuit_breaker_state");
        assertThat(scrape).contains("name=\"test-cb\"");
        assertThat(scrape).contains("0.0"); // CLOSED state
    }

    @Test
    void circuitBreakerGaugeReflectsStateChanges() {
        // Use a mutable state holder
        AtomicReference<String> state = new AtomicReference<>("CLOSED");
        metrics.registerCircuitBreaker("test-cb", state::get);

        // Change state to OPEN
        state.set("OPEN");

        String scrape = metrics.scrape();

        // Should now be OPEN (2)
        assertThat(scrape).contains("circuit_breaker_state{name=\"test-cb\"");
        assertThat(scrape).contains("2.0"); // OPEN state
    }

    @Test
    void circuitBreakerGaugeSupportsHalfOpenState() {
        AtomicReference<String> state = new AtomicReference<>("HALF_OPEN");
        metrics.registerCircuitBreaker("test-cb", state::get);

        String scrape = metrics.scrape();

        // Should be HALF_OPEN (1)
        assertThat(scrape).contains("circuit_breaker_state{name=\"test-cb\"");
        assertThat(scrape).contains("1.0"); // HALF_OPEN state
    }

    @Test
    void normalizesPathsToAvoidHighCardinality() {
        // Paths with line IDs should be normalized
        metrics.recordRequest("GET", "/api/v1/tube/central/status", 200, Duration.ofMillis(10));
        metrics.recordRequest("GET", "/api/v1/tube/northern/status", 200, Duration.ofMillis(10));

        String scrape = metrics.scrape();

        // Both should map to the same normalized path
        assertThat(scrape).contains("path=\"/api/v1/tube/{lineId}/status\"");
        assertThat(scrape).doesNotContain("path=\"/api/v1/tube/central/status\"");
        assertThat(scrape).doesNotContain("path=\"/api/v1/tube/northern/status\"");
    }

    @Test
    void scrapeReturnsPrometheusFormat() {
        metrics.recordRequest("GET", "/api/v1/tube/status", 200, Duration.ofMillis(50));

        String scrape = metrics.scrape();

        // Should be valid Prometheus text format
        assertThat(scrape).contains("# HELP");
        assertThat(scrape).contains("# TYPE");
    }
}
