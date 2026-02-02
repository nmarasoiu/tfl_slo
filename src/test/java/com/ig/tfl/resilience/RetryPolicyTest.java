package com.ig.tfl.resilience;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.ConnectException;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.*;

/**
 * Unit tests for RetryPolicy.
 *
 * Tests exponential backoff calculation and retry behavior.
 * Uses real objects (no mocks) per Detroit style.
 */
class RetryPolicyTest {

    @Test
    void calculatesExponentialBackoffWithoutJitter() {
        var policy = RetryPolicy.builder()
                .baseDelay(Duration.ofSeconds(1))
                .maxDelay(Duration.ofSeconds(30))
                .jitterFactor(0)  // No jitter for deterministic test
                .build();

        // Attempt 1: 1s * 2^0 = 1s
        assertThat(policy.calculateDelay(1)).isEqualTo(Duration.ofSeconds(1));

        // Attempt 2: 1s * 2^1 = 2s
        assertThat(policy.calculateDelay(2)).isEqualTo(Duration.ofSeconds(2));

        // Attempt 3: 1s * 2^2 = 4s
        assertThat(policy.calculateDelay(3)).isEqualTo(Duration.ofSeconds(4));

        // Attempt 4: 1s * 2^3 = 8s
        assertThat(policy.calculateDelay(4)).isEqualTo(Duration.ofSeconds(8));
    }

    @Test
    void capsAtMaxDelay() {
        var policy = RetryPolicy.builder()
                .baseDelay(Duration.ofSeconds(1))
                .maxDelay(Duration.ofSeconds(30))
                .jitterFactor(0)
                .build();

        // Attempt 10: 1s * 2^9 = 512s, but capped at 30s
        assertThat(policy.calculateDelay(10)).isEqualTo(Duration.ofSeconds(30));

        // Even higher attempts stay capped
        assertThat(policy.calculateDelay(20)).isEqualTo(Duration.ofSeconds(30));
    }

    @Test
    void addsJitterToDelay() {
        var policy = RetryPolicy.builder()
                .baseDelay(Duration.ofSeconds(10))
                .maxDelay(Duration.ofSeconds(100))
                .jitterFactor(0.25)  // ±25%
                .build();

        // Run multiple times to verify jitter produces variation
        long baseMs = 10_000;  // 10 seconds
        boolean sawAbove = false;
        boolean sawBelow = false;

        for (int i = 0; i < 100; i++) {
            Duration delay = policy.calculateDelay(1);
            long ms = delay.toMillis();

            // Should be within ±25% of base
            assertThat(ms).isBetween(7_500L, 12_500L);

            if (ms > baseMs) sawAbove = true;
            if (ms < baseMs) sawBelow = true;
        }

        // With 25% jitter and 100 samples, we should see variation
        assertThat(sawAbove).isTrue();
        assertThat(sawBelow).isTrue();
    }

    @Test
    void executeSynchronouslySucceedsOnFirstAttempt() {
        var policy = RetryPolicy.builder()
                .maxRetries(3)
                .retryOnException(t -> true)
                .build();

        var counter = new AtomicInteger(0);

        String result = policy.execute(() -> {
            counter.incrementAndGet();
            return "success";
        });

        assertThat(result).isEqualTo("success");
        assertThat(counter.get()).isEqualTo(1);
    }

    @Test
    void executeSynchronouslyRetriesOnFailure() {
        var policy = RetryPolicy.builder()
                .maxRetries(3)
                .baseDelay(Duration.ofMillis(10))
                .jitterFactor(0)
                .retryOnException(t -> true)  // Retry all exceptions
                .build();

        var counter = new AtomicInteger(0);

        String result = policy.execute(() -> {
            int attempt = counter.incrementAndGet();
            if (attempt < 3) {
                throw new RuntimeException("transient error");
            }
            return "success after retries";
        });

        assertThat(result).isEqualTo("success after retries");
        assertThat(counter.get()).isEqualTo(3);
    }

    @Test
    void executeSynchronouslyThrowsAfterMaxRetries() {
        var policy = RetryPolicy.builder()
                .maxRetries(2)
                .baseDelay(Duration.ofMillis(10))
                .jitterFactor(0)
                .retryOnException(t -> true)
                .build();

        var counter = new AtomicInteger(0);

        assertThatThrownBy(() -> policy.execute(() -> {
            counter.incrementAndGet();
            throw new RuntimeException("always fails");
        }))
                .isInstanceOf(RetryPolicy.RetriesExhaustedException.class)
                .hasMessageContaining("2 retry attempts exhausted");

        // Initial attempt + 2 retries = 3 total
        assertThat(counter.get()).isEqualTo(3);
    }

    @Test
    void doesNotRetryNonRetryableExceptions() {
        var policy = RetryPolicy.builder()
                .maxRetries(3)
                .retryOnException(t -> t instanceof IOException)
                .build();

        var counter = new AtomicInteger(0);

        assertThatThrownBy(() -> policy.execute(() -> {
            counter.incrementAndGet();
            throw new IllegalArgumentException("non-retryable");
        }))
                .isInstanceOf(IllegalArgumentException.class);

        // Should not retry - only 1 attempt
        assertThat(counter.get()).isEqualTo(1);
    }

    @Test
    void executeAsyncSucceedsOnFirstAttempt() {
        var policy = RetryPolicy.builder()
                .maxRetries(3)
                .build();

        var counter = new AtomicInteger(0);

        String result = policy.executeAsync(() -> {
            counter.incrementAndGet();
            return CompletableFuture.completedFuture("async success");
        }).toCompletableFuture().join();

        assertThat(result).isEqualTo("async success");
        assertThat(counter.get()).isEqualTo(1);
    }

    @Test
    void executeAsyncRetriesOnFailure() {
        var policy = RetryPolicy.builder()
                .maxRetries(3)
                .baseDelay(Duration.ofMillis(10))
                .jitterFactor(0)
                .retryOnException(t -> t instanceof IOException)
                .build();

        var counter = new AtomicInteger(0);

        String result = policy.executeAsync(() -> {
            int attempt = counter.incrementAndGet();
            if (attempt < 2) {
                return CompletableFuture.failedFuture(new IOException("transient"));
            }
            return CompletableFuture.completedFuture("recovered");
        }).toCompletableFuture().join();

        assertThat(result).isEqualTo("recovered");
        assertThat(counter.get()).isEqualTo(2);
    }

    @Test
    void executeAsyncThrowsAfterMaxRetries() {
        var policy = RetryPolicy.builder()
                .maxRetries(2)
                .baseDelay(Duration.ofMillis(10))
                .jitterFactor(0)
                .retryOnException(t -> true)
                .build();

        var counter = new AtomicInteger(0);

        assertThatThrownBy(() -> policy.executeAsync(() -> {
            counter.incrementAndGet();
            return CompletableFuture.failedFuture(new RuntimeException("always fails"));
        }).toCompletableFuture().join())
                .hasCauseInstanceOf(RetryPolicy.RetriesExhaustedException.class);

        assertThat(counter.get()).isEqualTo(3);
    }

    @Test
    void httpStatusExceptionIsRetryableFor5xx() {
        var policy = RetryPolicy.defaults();

        var counter = new AtomicInteger(0);

        String result = policy.executeAsync(() -> {
            int attempt = counter.incrementAndGet();
            if (attempt == 1) {
                return CompletableFuture.failedFuture(
                        new RetryPolicy.HttpStatusException(503, "Service Unavailable"));
            }
            return CompletableFuture.completedFuture("recovered");
        }).toCompletableFuture().join();

        assertThat(result).isEqualTo("recovered");
        assertThat(counter.get()).isEqualTo(2);
    }

    @Test
    void httpStatusExceptionIsRetryableFor429() {
        var policy = RetryPolicy.defaults();

        var counter = new AtomicInteger(0);

        String result = policy.executeAsync(() -> {
            int attempt = counter.incrementAndGet();
            if (attempt == 1) {
                return CompletableFuture.failedFuture(
                        new RetryPolicy.HttpStatusException(429, "Too Many Requests"));
            }
            return CompletableFuture.completedFuture("recovered");
        }).toCompletableFuture().join();

        assertThat(result).isEqualTo("recovered");
        assertThat(counter.get()).isEqualTo(2);
    }

    @Test
    void httpStatusExceptionIsNotRetryableFor4xx() {
        var policy = RetryPolicy.defaults();

        var counter = new AtomicInteger(0);

        assertThatThrownBy(() -> policy.executeAsync(() -> {
            counter.incrementAndGet();
            return CompletableFuture.failedFuture(
                    new RetryPolicy.HttpStatusException(404, "Not Found"));
        }).toCompletableFuture().join())
                .hasCauseInstanceOf(RetryPolicy.HttpStatusException.class);

        // Should not retry 4xx
        assertThat(counter.get()).isEqualTo(1);
    }

    @Test
    void ioExceptionsAreRetryable() {
        var policy = RetryPolicy.defaults();

        var counter = new AtomicInteger(0);

        String result = policy.executeAsync(() -> {
            int attempt = counter.incrementAndGet();
            if (attempt == 1) {
                return CompletableFuture.failedFuture(new ConnectException("Connection refused"));
            }
            return CompletableFuture.completedFuture("connected");
        }).toCompletableFuture().join();

        assertThat(result).isEqualTo("connected");
        assertThat(counter.get()).isEqualTo(2);
    }

    @Test
    void defaultsHasExpectedConfiguration() {
        var policy = RetryPolicy.defaults();

        // Default is 3 retries
        var counter = new AtomicInteger(0);

        assertThatThrownBy(() -> policy.executeAsync(() -> {
            counter.incrementAndGet();
            return CompletableFuture.failedFuture(new IOException("always fails"));
        }).toCompletableFuture().join())
                .hasCauseInstanceOf(RetryPolicy.RetriesExhaustedException.class);

        // Initial + 3 retries = 4 total
        assertThat(counter.get()).isEqualTo(4);
    }
}
