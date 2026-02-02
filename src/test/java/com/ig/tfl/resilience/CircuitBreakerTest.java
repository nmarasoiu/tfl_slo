package com.ig.tfl.resilience;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.*;

/**
 * Unit tests for CircuitBreaker.
 *
 * Tests state transitions: CLOSED -> OPEN -> HALF_OPEN -> CLOSED/OPEN
 * Uses real objects (no mocks) per Detroit style.
 */
class CircuitBreakerTest {

    @Test
    void startsInClosedState() {
        var cb = new CircuitBreaker("test", 5, Duration.ofSeconds(30));
        assertThat(cb.getState()).isEqualTo(CircuitBreaker.State.CLOSED);
    }

    @Test
    void remainsClosedBelowFailureThreshold() {
        var cb = new CircuitBreaker("test", 5, Duration.ofSeconds(30));

        // 4 failures should not open circuit (threshold is 5)
        for (int i = 0; i < 4; i++) {
            cb.onFailure(new RuntimeException("fail " + i));
        }

        assertThat(cb.getState()).isEqualTo(CircuitBreaker.State.CLOSED);
    }

    @Test
    void opensAfterFailureThresholdReached() {
        var cb = new CircuitBreaker("test", 5, Duration.ofSeconds(30));

        // Exactly 5 failures should open circuit
        IntStream.range(0, 5).forEach(i ->
            cb.onFailure(new RuntimeException("fail " + i)));

        assertThat(cb.getState()).isEqualTo(CircuitBreaker.State.OPEN);
    }

    @Test
    void successResetsFailureCount() {
        var cb = new CircuitBreaker("test", 5, Duration.ofSeconds(30));

        // 4 failures
        IntStream.range(0, 4).forEach(i ->
            cb.onFailure(new RuntimeException("fail")));

        // 1 success resets the counter
        cb.onSuccess();

        // 4 more failures should not open (fresh count)
        IntStream.range(0, 4).forEach(i ->
            cb.onFailure(new RuntimeException("fail")));

        assertThat(cb.getState()).isEqualTo(CircuitBreaker.State.CLOSED);
    }

    @Test
    void executeThrowsWhenOpen() {
        var cb = new CircuitBreaker("test", 2, Duration.ofSeconds(30));

        // Open the circuit
        cb.onFailure(new RuntimeException("fail"));
        cb.onFailure(new RuntimeException("fail"));

        assertThat(cb.getState()).isEqualTo(CircuitBreaker.State.OPEN);

        assertThatThrownBy(() -> cb.execute(() -> "result"))
                .isInstanceOf(CircuitBreaker.CircuitOpenException.class)
                .hasMessageContaining("OPEN");
    }

    @Test
    void executeRecordsSuccessOnResult() {
        var cb = new CircuitBreaker("test", 5, Duration.ofSeconds(30));

        // Add some failures (not enough to open)
        cb.onFailure(new RuntimeException("fail"));
        cb.onFailure(new RuntimeException("fail"));

        // Execute successfully should reset counter
        String result = cb.execute(() -> "success");

        assertThat(result).isEqualTo("success");
        assertThat(cb.getState()).isEqualTo(CircuitBreaker.State.CLOSED);
    }

    @Test
    void executeRecordsFailureOnException() {
        var cb = new CircuitBreaker("test", 2, Duration.ofSeconds(30));

        // First failure via execute
        assertThatThrownBy(() -> cb.execute(() -> {
            throw new RuntimeException("error");
        })).isInstanceOf(RuntimeException.class);

        // Second failure opens circuit
        assertThatThrownBy(() -> cb.execute(() -> {
            throw new RuntimeException("error");
        })).isInstanceOf(RuntimeException.class);

        assertThat(cb.getState()).isEqualTo(CircuitBreaker.State.OPEN);
    }

    @Test
    void transitionsToHalfOpenAfterTimeout() throws InterruptedException {
        // Very short timeout for testing
        var cb = new CircuitBreaker("test", 2, Duration.ofMillis(100));

        // Open the circuit
        cb.onFailure(new RuntimeException("fail"));
        cb.onFailure(new RuntimeException("fail"));
        assertThat(cb.getState()).isEqualTo(CircuitBreaker.State.OPEN);

        // Wait for open duration to pass
        Thread.sleep(150);

        // Should transition to HALF_OPEN on next getState call
        assertThat(cb.getState()).isEqualTo(CircuitBreaker.State.HALF_OPEN);
    }

    @Test
    void halfOpenClosesOnSuccess() throws InterruptedException {
        var cb = new CircuitBreaker("test", 2, Duration.ofMillis(50));

        // Open the circuit
        cb.onFailure(new RuntimeException("fail"));
        cb.onFailure(new RuntimeException("fail"));

        // Wait for HALF_OPEN
        Thread.sleep(60);
        assertThat(cb.getState()).isEqualTo(CircuitBreaker.State.HALF_OPEN);

        // Success in HALF_OPEN should close
        cb.onSuccess();
        assertThat(cb.getState()).isEqualTo(CircuitBreaker.State.CLOSED);
    }

    @Test
    void halfOpenOpensOnFailure() throws InterruptedException {
        var cb = new CircuitBreaker("test", 2, Duration.ofMillis(50));

        // Open the circuit
        cb.onFailure(new RuntimeException("fail"));
        cb.onFailure(new RuntimeException("fail"));

        // Wait for HALF_OPEN
        Thread.sleep(60);
        assertThat(cb.getState()).isEqualTo(CircuitBreaker.State.HALF_OPEN);

        // Any failure in HALF_OPEN should re-open immediately
        cb.onFailure(new RuntimeException("still failing"));
        assertThat(cb.getState()).isEqualTo(CircuitBreaker.State.OPEN);
    }

    @Test
    void circuitOpenExceptionContainsRetryAfter() {
        var cb = new CircuitBreaker("test", 2, Duration.ofSeconds(30));

        cb.onFailure(new RuntimeException("fail"));
        cb.onFailure(new RuntimeException("fail"));

        try {
            cb.execute(() -> "won't run");
            fail("Should have thrown");
        } catch (CircuitBreaker.CircuitOpenException e) {
            assertThat(e.getCircuitName()).isEqualTo("test");
            assertThat(e.getRetryAfter()).isLessThanOrEqualTo(Duration.ofSeconds(30));
            assertThat(e.getRetryAfter()).isPositive();
        }
    }

    @Test
    void withDefaultsUsesStandardConfiguration() {
        var cb = CircuitBreaker.withDefaults("default-test");

        // Default is 5 failures
        IntStream.range(0, 4).forEach(i ->
            cb.onFailure(new RuntimeException("fail")));
        assertThat(cb.getState()).isEqualTo(CircuitBreaker.State.CLOSED);

        cb.onFailure(new RuntimeException("fifth"));
        assertThat(cb.getState()).isEqualTo(CircuitBreaker.State.OPEN);
    }
}
