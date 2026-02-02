package com.ig.tfl.resilience;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Random;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * Retry policy with exponential backoff and jitter.
 *
 * Backoff formula: min(cap, base * 2^attempt) ± jitter
 *
 * Example with defaults:
 *   Attempt 1: 1s ± 250ms
 *   Attempt 2: 2s ± 500ms
 *   Attempt 3: 4s ± 1s
 *   Attempt 4+: 30s ± 7.5s (capped)
 */
public class RetryPolicy {
    private static final Logger log = LoggerFactory.getLogger(RetryPolicy.class);

    private final int maxRetries;
    private final Duration baseDelay;
    private final Duration maxDelay;
    private final double jitterFactor;  // 0.25 = ±25%
    private final Predicate<Throwable> retryableException;
    private final Predicate<HttpResponse<?>> retryableResponse;
    private final Random random = new Random();

    private RetryPolicy(Builder builder) {
        this.maxRetries = builder.maxRetries;
        this.baseDelay = builder.baseDelay;
        this.maxDelay = builder.maxDelay;
        this.jitterFactor = builder.jitterFactor;
        this.retryableException = builder.retryableException;
        this.retryableResponse = builder.retryableResponse;
    }

    public static Builder builder() {
        return new Builder();
    }

    /**
     * Default policy: 3 retries, 1s base, 30s cap, 25% jitter.
     * Retries on 5xx and timeouts, not on 4xx (except 429).
     */
    public static RetryPolicy defaults() {
        return builder()
                .maxRetries(3)
                .baseDelay(Duration.ofSeconds(1))
                .maxDelay(Duration.ofSeconds(30))
                .jitterFactor(0.25)
                .retryOnException(RetryPolicy::isRetryableException)
                .retryOnResponse(RetryPolicy::isRetryableResponse)
                .build();
    }

    /**
     * Execute with retry logic (synchronous).
     */
    public <T> T execute(Supplier<T> operation) {
        Exception lastException = null;

        for (int attempt = 0; attempt <= maxRetries; attempt++) {
            try {
                if (attempt > 0) {
                    Duration delay = calculateDelay(attempt);
                    log.info("Retry attempt {}/{} after {}ms", attempt, maxRetries, delay.toMillis());
                    Thread.sleep(delay.toMillis());
                }

                return operation.get();

            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException("Retry interrupted", e);
            } catch (Exception e) {
                lastException = e;
                if (!retryableException.test(e)) {
                    log.warn("Non-retryable exception: {}", e.getMessage());
                    throw e instanceof RuntimeException re ? re : new RuntimeException(e);
                }
                log.warn("Retryable exception on attempt {}: {}", attempt + 1, e.getMessage());
            }
        }

        throw new RetriesExhaustedException(maxRetries, lastException);
    }

    /**
     * Execute with retry logic (async).
     */
    public <T> CompletableFuture<T> executeAsync(Supplier<CompletableFuture<T>> operation) {
        return executeAsyncInternal(operation, 0, null);
    }

    private <T> CompletableFuture<T> executeAsyncInternal(
            Supplier<CompletableFuture<T>> operation,
            int attempt,
            Throwable lastError) {

        if (attempt > maxRetries) {
            return CompletableFuture.failedFuture(
                    new RetriesExhaustedException(maxRetries, lastError));
        }

        CompletableFuture<Void> delayed = attempt == 0
                ? CompletableFuture.completedFuture(null)
                : delayedFuture(calculateDelay(attempt));

        return delayed.thenCompose(ignored -> operation.get())
                .exceptionallyCompose(error -> {
                    Throwable cause = error.getCause() != null ? error.getCause() : error;
                    if (!retryableException.test(cause)) {
                        return CompletableFuture.failedFuture(cause);
                    }
                    log.warn("Async retry attempt {}/{} after error: {}",
                            attempt + 1, maxRetries, cause.getMessage());
                    return executeAsyncInternal(operation, attempt + 1, cause);
                });
    }

    private CompletableFuture<Void> delayedFuture(Duration delay) {
        return CompletableFuture.runAsync(
                () -> {},
                CompletableFuture.delayedExecutor(delay.toMillis(), TimeUnit.MILLISECONDS)
        );
    }

    Duration calculateDelay(int attempt) {
        // Exponential: base * 2^(attempt-1)
        long exponentialMs = baseDelay.toMillis() * (1L << (attempt - 1));

        // Cap at maxDelay
        long cappedMs = Math.min(exponentialMs, maxDelay.toMillis());

        // Add jitter: ±jitterFactor
        double jitter = 1.0 + (random.nextDouble() * 2 - 1) * jitterFactor;
        long finalMs = (long) (cappedMs * jitter);

        return Duration.ofMillis(Math.max(finalMs, 0));
    }

    // Default retry predicates

    private static boolean isRetryableException(Throwable t) {
        // Retry on timeouts and connection issues
        if (t instanceof java.net.http.HttpTimeoutException) return true;
        if (t instanceof java.net.ConnectException) return true;
        if (t instanceof java.io.IOException) return true;

        // Check for wrapped HTTP status in custom exception
        if (t instanceof HttpStatusException hse) {
            return isRetryableStatus(hse.getStatusCode());
        }

        return false;
    }

    private static boolean isRetryableResponse(HttpResponse<?> response) {
        return isRetryableStatus(response.statusCode());
    }

    private static boolean isRetryableStatus(int status) {
        // Retry on 5xx server errors
        if (status >= 500) return true;

        // Retry on 429 (rate limited)
        if (status == 429) return true;

        // Don't retry on other 4xx (client errors)
        return false;
    }

    public static class HttpStatusException extends RuntimeException {
        private final int statusCode;

        public HttpStatusException(int statusCode, String message) {
            super(message);
            this.statusCode = statusCode;
        }

        public int getStatusCode() { return statusCode; }
    }

    public static class RetriesExhaustedException extends RuntimeException {
        public RetriesExhaustedException(int attempts, Throwable lastError) {
            super("All " + attempts + " retry attempts exhausted", lastError);
        }
    }

    public static class Builder {
        private int maxRetries = 3;
        private Duration baseDelay = Duration.ofSeconds(1);
        private Duration maxDelay = Duration.ofSeconds(30);
        private double jitterFactor = 0.25;
        private Predicate<Throwable> retryableException = t -> true;
        private Predicate<HttpResponse<?>> retryableResponse = r -> false;

        public Builder maxRetries(int maxRetries) {
            this.maxRetries = maxRetries;
            return this;
        }

        public Builder baseDelay(Duration baseDelay) {
            this.baseDelay = baseDelay;
            return this;
        }

        public Builder maxDelay(Duration maxDelay) {
            this.maxDelay = maxDelay;
            return this;
        }

        public Builder jitterFactor(double jitterFactor) {
            this.jitterFactor = jitterFactor;
            return this;
        }

        public Builder retryOnException(Predicate<Throwable> predicate) {
            this.retryableException = predicate;
            return this;
        }

        public Builder retryOnResponse(Predicate<HttpResponse<?>> predicate) {
            this.retryableResponse = predicate;
            return this;
        }

        public RetryPolicy build() {
            return new RetryPolicy(this);
        }
    }
}
