package com.ig.tfl.resilience;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

/**
 * Circuit breaker implementation for TfL API calls.
 *
 * State machine:
 *   CLOSED -> OPEN (after failureThreshold consecutive failures)
 *   OPEN -> HALF_OPEN (after openDuration)
 *   HALF_OPEN -> CLOSED (on success) or OPEN (on failure)
 */
public class CircuitBreaker {
    private static final Logger log = LoggerFactory.getLogger(CircuitBreaker.class);

    public enum State { CLOSED, OPEN, HALF_OPEN }

    private final String name;
    private final int failureThreshold;
    private final Duration openDuration;

    private final AtomicReference<State> state = new AtomicReference<>(State.CLOSED);
    private final AtomicInteger consecutiveFailures = new AtomicInteger(0);
    private volatile Instant openedAt = null;

    public CircuitBreaker(String name, int failureThreshold, Duration openDuration) {
        this.name = name;
        this.failureThreshold = failureThreshold;
        this.openDuration = openDuration;
    }

    /**
     * Default configuration: 5 failures, 30s open duration.
     */
    public static CircuitBreaker withDefaults(String name) {
        return new CircuitBreaker(name, 5, Duration.ofSeconds(30));
    }

    public State getState() {
        maybeTransitionToHalfOpen();
        return state.get();
    }

    /**
     * Execute a supplier within the circuit breaker.
     *
     * @throws CircuitOpenException if circuit is open
     */
    public <T> T execute(Supplier<T> supplier) throws CircuitOpenException {
        maybeTransitionToHalfOpen();

        State currentState = state.get();

        if (currentState == State.OPEN) {
            throw new CircuitOpenException(name, remainingOpenTime());
        }

        try {
            T result = supplier.get();
            onSuccess();
            return result;
        } catch (Exception e) {
            onFailure(e);
            throw e;
        }
    }

    /**
     * Record a successful call.
     */
    public void onSuccess() {
        State previous = state.getAndSet(State.CLOSED);
        consecutiveFailures.set(0);
        openedAt = null;

        if (previous != State.CLOSED) {
            log.info("Circuit breaker '{}' CLOSED after successful call", name);
        }
    }

    /**
     * Record a failed call.
     */
    public void onFailure(Throwable t) {
        int failures = consecutiveFailures.incrementAndGet();
        log.warn("Circuit breaker '{}' recorded failure {}/{}: {}",
                name, failures, failureThreshold, t.getMessage());

        if (state.get() == State.HALF_OPEN) {
            // Any failure in half-open goes back to open
            transitionToOpen();
        } else if (failures >= failureThreshold) {
            transitionToOpen();
        }
    }

    private void transitionToOpen() {
        State previous = state.getAndSet(State.OPEN);
        openedAt = Instant.now();
        if (previous != State.OPEN) {
            log.warn("Circuit breaker '{}' OPEN - will retry in {}", name, openDuration);
        }
    }

    private void maybeTransitionToHalfOpen() {
        if (state.get() == State.OPEN && openedAt != null) {
            if (Instant.now().isAfter(openedAt.plus(openDuration))) {
                if (state.compareAndSet(State.OPEN, State.HALF_OPEN)) {
                    log.info("Circuit breaker '{}' HALF_OPEN - testing recovery", name);
                }
            }
        }
    }

    private Duration remainingOpenTime() {
        if (openedAt == null) return Duration.ZERO;
        Instant reopenAt = openedAt.plus(openDuration);
        Duration remaining = Duration.between(Instant.now(), reopenAt);
        return remaining.isNegative() ? Duration.ZERO : remaining;
    }

    public static class CircuitOpenException extends RuntimeException {
        private final String circuitName;
        private final Duration retryAfter;

        public CircuitOpenException(String circuitName, Duration retryAfter) {
            super("Circuit breaker '" + circuitName + "' is OPEN, retry after " + retryAfter);
            this.circuitName = circuitName;
            this.retryAfter = retryAfter;
        }

        public String getCircuitName() { return circuitName; }
        public Duration getRetryAfter() { return retryAfter; }
    }
}
