package com.ig.tfl.resilience;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Token bucket rate limiter.
 *
 * Each client (identified by key, typically IP) gets a bucket that:
 * - Starts with 'bucketSize' tokens
 * - Refills at 'refillRate' tokens per 'refillPeriod'
 * - Requests consume 1 token
 * - When empty, requests are rejected with a retry-after duration
 */
public class RateLimiter {
    private static final Logger log = LoggerFactory.getLogger(RateLimiter.class);

    private final int bucketSize;
    private final int refillRate;
    private final Duration refillPeriod;
    private final Map<String, TokenBucket> buckets = new ConcurrentHashMap<>();

    public RateLimiter(int bucketSize, int refillRate, Duration refillPeriod) {
        this.bucketSize = bucketSize;
        this.refillRate = refillRate;
        this.refillPeriod = refillPeriod;
    }

    /**
     * Default: 100 requests per minute per client.
     */
    public static RateLimiter perMinute(int requestsPerMinute) {
        return new RateLimiter(requestsPerMinute, requestsPerMinute, Duration.ofMinutes(1));
    }

    /**
     * Try to acquire a permit for the given client key.
     *
     * @return RateLimitResult indicating success or failure with retry-after
     */
    public RateLimitResult tryAcquire(String clientKey) {
        TokenBucket bucket = buckets.computeIfAbsent(clientKey,
                k -> new TokenBucket(bucketSize, refillRate, refillPeriod));
        return bucket.tryAcquire();
    }

    /**
     * Acquire a permit, throwing if rate limited.
     */
    public void acquire(String clientKey) throws RateLimitExceededException {
        RateLimitResult result = tryAcquire(clientKey);
        if (!result.allowed()) {
            throw new RateLimitExceededException(clientKey, result.retryAfter());
        }
    }

    /**
     * Get current state for a client (for debugging/metrics).
     */
    public BucketState getState(String clientKey) {
        TokenBucket bucket = buckets.get(clientKey);
        return bucket != null ? bucket.getState() : new BucketState(bucketSize, bucketSize);
    }

    /**
     * Clean up old buckets (call periodically to prevent memory leak).
     */
    public void cleanup(Duration maxAge) {
        Instant cutoff = Instant.now().minus(maxAge);
        buckets.entrySet().removeIf(entry ->
                entry.getValue().getLastAccess().isBefore(cutoff));
    }

    public record RateLimitResult(boolean allowed, Duration retryAfter, int remainingTokens) {
        public static RateLimitResult allowed(int remaining) {
            return new RateLimitResult(true, Duration.ZERO, remaining);
        }

        public static RateLimitResult denied(Duration retryAfter) {
            return new RateLimitResult(false, retryAfter, 0);
        }
    }

    public record BucketState(int currentTokens, int maxTokens) {}

    public static class RateLimitExceededException extends RuntimeException {
        private final String clientKey;
        private final Duration retryAfter;

        public RateLimitExceededException(String clientKey, Duration retryAfter) {
            super("Rate limit exceeded for client: " + clientKey);
            this.clientKey = clientKey;
            this.retryAfter = retryAfter;
        }

        public String getClientKey() {
            return clientKey;
        }

        public Duration getRetryAfter() {
            return retryAfter;
        }
    }

    /**
     * Token bucket implementation with atomic updates.
     */
    private static class TokenBucket {
        private final int bucketSize;
        private final int refillRate;
        private final Duration refillPeriod;
        private final AtomicReference<BucketSnapshot> snapshot;

        TokenBucket(int bucketSize, int refillRate, Duration refillPeriod) {
            this.bucketSize = bucketSize;
            this.refillRate = refillRate;
            this.refillPeriod = refillPeriod;
            this.snapshot = new AtomicReference<>(
                    new BucketSnapshot(bucketSize, Instant.now()));
        }

        RateLimitResult tryAcquire() {
            while (true) {
                BucketSnapshot current = snapshot.get();
                BucketSnapshot refilled = refill(current);

                if (refilled.tokens < 1) {
                    // Calculate when next token will be available
                    Duration retryAfter = calculateRetryAfter(refilled);
                    return RateLimitResult.denied(retryAfter);
                }

                BucketSnapshot consumed = new BucketSnapshot(
                        refilled.tokens - 1,
                        Instant.now());

                if (snapshot.compareAndSet(current, consumed)) {
                    return RateLimitResult.allowed(consumed.tokens);
                }
                // CAS failed, retry
            }
        }

        private BucketSnapshot refill(BucketSnapshot current) {
            Instant now = Instant.now();
            Duration elapsed = Duration.between(current.lastUpdate, now);

            // How many tokens to add based on elapsed time
            double periodsElapsed = (double) elapsed.toMillis() / refillPeriod.toMillis();
            int tokensToAdd = (int) (periodsElapsed * refillRate);

            int newTokens = Math.min(bucketSize, current.tokens + tokensToAdd);
            return new BucketSnapshot(newTokens, now);
        }

        private Duration calculateRetryAfter(BucketSnapshot current) {
            // Time until one token is available
            double tokensNeeded = 1 - current.tokens;
            double periodsNeeded = tokensNeeded / refillRate;
            long msNeeded = (long) (periodsNeeded * refillPeriod.toMillis());
            return Duration.ofMillis(Math.max(msNeeded, 1000)); // At least 1s
        }

        BucketState getState() {
            BucketSnapshot current = refill(snapshot.get());
            return new BucketState(current.tokens, bucketSize);
        }

        Instant getLastAccess() {
            return snapshot.get().lastUpdate;
        }

        private record BucketSnapshot(int tokens, Instant lastUpdate) {}
    }
}
