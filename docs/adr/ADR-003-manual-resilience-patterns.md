# ADR-003: Manual Resilience Patterns vs Pekko Built-ins / Resilience4j

**Status:** Superseded (migrated to Pekko built-ins)
**Date:** 2026-02-03
**Deciders:** Architecture team

---

## Context

We implemented three resilience patterns manually:

| Pattern | Our Implementation | Lines of Code |
|---------|-------------------|---------------|
| Circuit Breaker | `CircuitBreaker.java` | ~140 |
| Retry with Backoff | `RetryPolicy.java` | ~226 |
| Rate Limiter | `RateLimiter.java` | ~176 |
| **Total** | | **~542** |

However, alternatives exist:

| Pattern | Pekko Built-in | Resilience4j |
|---------|---------------|--------------|
| Circuit Breaker | `org.apache.pekko.pattern.CircuitBreaker` | `CircuitBreaker` |
| Retry | `org.apache.pekko.pattern.RetrySupport` | `Retry` |
| Rate Limiter | (Streams throttle only) | `RateLimiter` |
| Bulkhead | (Actor isolation) | `Bulkhead` |
| Time Limiter | (ask timeout) | `TimeLimiter` |

---

## Current Decision

**We implemented these patterns manually.**

Original rationale:
1. Demonstrates understanding of the patterns (interview/learning value)
2. Smaller dependency footprint
3. Full control over behavior

---

## Critical Review: Is This Over-Engineering?

### Arguments FOR Manual Implementation

1. **Educational value** - Writing a circuit breaker teaches you how they work
2. **No hidden magic** - Every line is visible and debuggable
3. **Precise fit** - Tailored exactly to our needs (no unused features)
4. **Fewer dependencies** - Less supply chain risk

### Arguments AGAINST Manual Implementation

1. **Pekko already has CircuitBreaker and RetrySupport**
   - We're adding `pekko-actor` anyway
   - The built-ins are well-tested and maintained
   - Reinventing the wheel in the same ecosystem

2. **Maintenance burden**
   - 542 lines of code to maintain
   - Edge cases we might miss (concurrency bugs, timing issues)
   - No community bug fixes

3. **Signals to interviewers**
   - Could appear as "not invented here" syndrome
   - Could suggest unfamiliarity with the framework
   - Good engineers use existing tools when appropriate

4. **Resilience4j is battle-tested**
   - Used by Netflix, many others
   - Comprehensive metrics integration
   - Well-documented

---

## Comparison: Our Code vs Pekko Built-in

### Circuit Breaker

**Our implementation:**
```java
public class CircuitBreaker {
    private final AtomicReference<State> state = new AtomicReference<>(State.CLOSED);
    private final AtomicInteger consecutiveFailures = new AtomicInteger(0);
    // ... 140 lines
}
```

**Pekko's implementation:**
```java
import org.apache.pekko.pattern.CircuitBreaker;

CircuitBreaker breaker = new CircuitBreaker(
    system.scheduler(),
    maxFailures,
    callTimeout,
    resetTimeout
);

breaker.callWithCircuitBreaker(() -> riskyCall())
    .whenComplete((result, error) -> { /* handle */ });
```

**Pekko advantages:**
- Integrated with Pekko scheduler
- `callTimeout` for automatic timeout handling
- Built-in exponential backoff (`exponentialBackoffFactor`)
- Callbacks: `onOpen`, `onClose`, `onHalfOpen`
- Telemetry SPI for metrics

### Retry

**Our implementation:**
```java
public class RetryPolicy {
    // 226 lines with exponential backoff, jitter, async support
}
```

**Pekko's RetrySupport:**
```java
import static org.apache.pekko.pattern.Patterns.retry;

CompletionStage<String> result = retry(
    () -> fetchFromTfl(),
    3,                          // attempts
    Duration.ofSeconds(1),      // delay
    system.scheduler(),
    system.executionContext()
);
```

**Pekko advantages:**
- Built-in delay scheduling
- Predicate-based retry (since 1.1.0)
- Scheduler-integrated (no Thread.sleep)

---

## Recommendation

### Option A: Keep Manual (Current State)

**When this makes sense:**
- This is explicitly a learning/demonstration project
- You want to show you understand the patterns
- The interview context values "show your work"

### Option B: Migrate to Pekko Built-ins (Recommended for Production)

**Migrate:**
- `CircuitBreaker.java` → `org.apache.pekko.pattern.CircuitBreaker`
- `RetryPolicy.java` → `org.apache.pekko.pattern.RetrySupport`

**Keep:**
- `RateLimiter.java` - Pekko doesn't have a built-in per-client rate limiter

**Why this is better for production:**
- Less code to maintain (-366 lines)
- Framework-integrated (scheduler, telemetry)
- Community-tested

### Option C: Use Resilience4j

**When this makes sense:**
- You want maximum features (bulkhead, cache, time limiter)
- You need advanced metrics (Micrometer integration built-in)
- You're not using Pekko (Spring Boot, etc.)

**For this project:**
- Adds another dependency
- Overlaps with Pekko features
- Slightly awkward with actor model

---

## Interview Answer

> "Why did you implement CircuitBreaker manually instead of using Pekko's?"

**Honest answer:** "For this project, I implemented it manually to demonstrate understanding of the pattern - the state machine, failure counting, half-open probing. In production, I'd use Pekko's built-in `org.apache.pekko.pattern.CircuitBreaker` because it's scheduler-integrated and has telemetry hooks. The manual version was educational; the Pekko version is operational."

**If challenged - "Isn't that over-engineering?"**

"Fair point. The manual implementation is ~140 lines that duplicates functionality Pekko provides. For a production service, I'd use the built-in. For this demo, I wanted to show I understand how circuit breakers work, not just how to import one. If I were joining a team, I'd follow whatever patterns they've established."

**If asked about Resilience4j:**

"Resilience4j is excellent - especially for Spring Boot projects. For a Pekko-based service, I'd prefer Pekko's built-ins because they integrate with the scheduler and actor model. Resilience4j would work but adds a dependency that overlaps with what Pekko provides."

---

## Migration Completed (2026-02-03)

We migrated to Pekko built-ins:

1. [x] Replaced `CircuitBreaker.java` with `org.apache.pekko.pattern.CircuitBreaker`
2. [x] Replaced `RetryPolicy.java` with `org.apache.pekko.pattern.Patterns.retry()`
3. [x] Kept `RateLimiter.java` (no Pekko equivalent for per-client limiting)
4. [x] Updated tests to use Pekko patterns
5. [x] Deleted old implementations (~366 lines removed)

**Result:** Cleaner codebase using framework-native resilience patterns.

---

## References

- [Pekko CircuitBreaker](https://pekko.apache.org/docs/pekko/current/common/circuitbreaker.html)
- [Pekko RetrySupport](https://pekko.apache.org/api/pekko/current/org/apache/pekko/pattern/RetrySupport.html)
- [Resilience4j](https://resilience4j.readme.io/)
- [Release It! - Michael Nygard](https://pragprog.com/titles/mnee2/release-it-second-edition/) (Circuit Breaker pattern origin)
