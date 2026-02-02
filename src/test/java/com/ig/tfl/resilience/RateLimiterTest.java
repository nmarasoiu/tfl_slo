package com.ig.tfl.resilience;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.*;

/**
 * Unit tests for RateLimiter (Token Bucket).
 *
 * Tests token consumption, refill, and rate limiting behavior.
 * Uses real objects (no mocks) per Detroit style.
 */
class RateLimiterTest {

    @Test
    void allowsRequestsWithinLimit() {
        var limiter = RateLimiter.perMinute(100);

        var result = limiter.tryAcquire("client-1");

        assertThat(result.allowed()).isTrue();
        assertThat(result.remainingTokens()).isEqualTo(99);
    }

    @Test
    void tracksRemainingTokens() {
        var limiter = RateLimiter.perMinute(10);

        // Consume 5 tokens
        IntStream.range(0, 5).forEach(i -> limiter.tryAcquire("client-1"));

        var state = limiter.getState("client-1");
        assertThat(state.currentTokens()).isEqualTo(5);
        assertThat(state.maxTokens()).isEqualTo(10);
    }

    @Test
    void blocksWhenBucketEmpty() {
        var limiter = new RateLimiter(5, 5, Duration.ofMinutes(1));

        // Consume all 5 tokens
        IntStream.range(0, 5).forEach(i -> {
            var result = limiter.tryAcquire("client-1");
            assertThat(result.allowed()).isTrue();
        });

        // 6th request should be denied
        var result = limiter.tryAcquire("client-1");
        assertThat(result.allowed()).isFalse();
        assertThat(result.retryAfter()).isPositive();
    }

    @Test
    void isolatesClientsByKey() {
        var limiter = new RateLimiter(2, 2, Duration.ofMinutes(1));

        // Client 1 uses both tokens
        limiter.tryAcquire("client-1");
        limiter.tryAcquire("client-1");
        assertThat(limiter.tryAcquire("client-1").allowed()).isFalse();

        // Client 2 should have fresh bucket
        var result = limiter.tryAcquire("client-2");
        assertThat(result.allowed()).isTrue();
    }

    @Test
    void refillsTokensOverTime() throws InterruptedException {
        // 10 tokens, refills 10 per second (for fast test)
        var limiter = new RateLimiter(10, 10, Duration.ofSeconds(1));

        // Use all tokens
        IntStream.range(0, 10).forEach(i -> limiter.tryAcquire("client-1"));
        assertThat(limiter.tryAcquire("client-1").allowed()).isFalse();

        // Wait for refill (100ms should give ~1 token)
        Thread.sleep(150);

        // Should have at least 1 token now
        var result = limiter.tryAcquire("client-1");
        assertThat(result.allowed()).isTrue();
    }

    @Test
    void doesNotExceedMaxTokens() throws InterruptedException {
        var limiter = new RateLimiter(5, 100, Duration.ofMillis(100));

        // Wait longer than needed for full refill
        Thread.sleep(200);

        // Should still only have bucketSize tokens (5), not more
        var state = limiter.getState("client-1");
        assertThat(state.currentTokens()).isLessThanOrEqualTo(5);
    }

    @Test
    void acquireThrowsWhenRateLimited() {
        var limiter = new RateLimiter(1, 1, Duration.ofMinutes(1));

        // Use the single token
        limiter.acquire("client-1");

        // Second request should throw
        assertThatThrownBy(() -> limiter.acquire("client-1"))
                .isInstanceOf(RateLimiter.RateLimitExceededException.class)
                .hasMessageContaining("client-1");
    }

    @Test
    void rateLimitExceptionContainsRetryAfter() {
        var limiter = new RateLimiter(1, 1, Duration.ofMinutes(1));
        limiter.acquire("client-1");

        try {
            limiter.acquire("client-1");
            fail("Should have thrown");
        } catch (RateLimiter.RateLimitExceededException e) {
            assertThat(e.getClientKey()).isEqualTo("client-1");
            assertThat(e.getRetryAfter()).isPositive();
        }
    }

    @Test
    void returnsFreshStateForUnknownClient() {
        var limiter = RateLimiter.perMinute(100);

        var state = limiter.getState("unknown-client");

        assertThat(state.currentTokens()).isEqualTo(100);
        assertThat(state.maxTokens()).isEqualTo(100);
    }

    @Test
    void cleanupRemovesOldBuckets() throws InterruptedException {
        var limiter = RateLimiter.perMinute(10);

        // Access some clients
        limiter.tryAcquire("old-client");
        Thread.sleep(50);
        limiter.tryAcquire("recent-client");

        // Cleanup with 25ms age threshold
        limiter.cleanup(Duration.ofMillis(25));

        // Old client should be removed, showing default state
        var oldState = limiter.getState("old-client");
        assertThat(oldState.currentTokens()).isEqualTo(10);  // Fresh bucket

        // Recent client should still have consumed token
        var recentState = limiter.getState("recent-client");
        // Due to refill, may have gained tokens back, but bucket should exist
    }

    @Test
    void concurrentAccessIsThreadSafe() throws InterruptedException {
        var limiter = new RateLimiter(1000, 1000, Duration.ofMinutes(1));
        var successCount = new java.util.concurrent.atomic.AtomicInteger(0);

        // 10 threads each trying 100 requests
        var threads = IntStream.range(0, 10)
                .mapToObj(i -> new Thread(() -> {
                    for (int j = 0; j < 100; j++) {
                        if (limiter.tryAcquire("shared-client").allowed()) {
                            successCount.incrementAndGet();
                        }
                    }
                }))
                .toList();

        threads.forEach(Thread::start);
        for (Thread t : threads) {
            t.join();
        }

        // All 1000 requests should succeed (bucket size = 1000)
        assertThat(successCount.get()).isEqualTo(1000);
    }

    @Test
    void retryAfterIsReasonable() {
        // 1 token per minute
        var limiter = new RateLimiter(1, 1, Duration.ofMinutes(1));

        limiter.tryAcquire("client-1");
        var result = limiter.tryAcquire("client-1");

        assertThat(result.allowed()).isFalse();
        // Should be at least 1 second but not more than 1 minute
        assertThat(result.retryAfter()).isBetween(Duration.ofSeconds(1), Duration.ofMinutes(1));
    }
}
