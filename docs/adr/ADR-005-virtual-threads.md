# ADR-005: Virtual Threads (Project Loom) with Pekko

**Status:** Accepted (not using virtual threads)
**Date:** 2026-02-03
**Deciders:** Architecture team

---

## Context

Java 21 introduced **Virtual Threads** (Project Loom) - lightweight threads managed by the JVM that can be created in large numbers (millions) without the overhead of OS threads.

Question: Should we use virtual threads with our Pekko-based service?

---

## Decision

**No. We do not use virtual threads with Pekko actors.**

Pekko already provides its own concurrency model that doesn't benefit from virtual threads.

---

## Why Virtual Threads Don't Help Here

### 1. Pekko Actors Already Solve the Same Problem

Virtual threads solve: "I want to write synchronous-looking code but have thousands of concurrent operations without blocking OS threads."

Pekko actors already solve this:
- Millions of actors can run on a small thread pool
- Message-passing is inherently non-blocking
- Actor mailboxes queue work efficiently

**We're not thread-starved** because we don't block threads.

### 2. Pekko HTTP is Already Non-Blocking

Our HTTP layer uses Pekko Streams/HTTP which is:
- Backpressure-aware
- Non-blocking
- Efficient with small thread pools

Virtual threads help when you have blocking I/O code. We don't.

### 3. Mixing Concurrency Models is Risky

Using virtual threads with Pekko would mean:
- Two different concurrency models in the same app
- Potential deadlocks from model mismatches
- Harder debugging
- Unclear ownership of threads

**Principle:** Pick one concurrency model and use it consistently.

---

## When Virtual Threads WOULD Be Useful

### Good Use Cases

1. **Legacy blocking code** - If you have `Thread.sleep()`, `synchronized` blocks, blocking JDBC calls
2. **Traditional servlet containers** - Tomcat/Jetty with one-thread-per-request
3. **Simple request handlers** - No complex async pipelines
4. **Migrating from threads to async** - Virtual threads as stepping stone

### Our Situation

- We use Pekko actors (already async)
- We use Pekko HTTP (already non-blocking)
- We use CompletionStage/CompletableFuture (already async)
- No blocking I/O in hot paths

**Conclusion:** Virtual threads solve a problem we don't have.

---

## What About Pekko's Dispatcher?

Pekko dispatchers manage thread pools for actors. Could we use virtual threads as the underlying pool?

### Theory

```hocon
pekko.actor.default-dispatcher {
  executor = "thread-pool-executor"
  thread-pool-executor {
    # Could theoretically use virtual thread factory
  }
}
```

### Practice

- Pekko's scheduler, timers, and some internals assume platform threads
- ForkJoinPool (default) has work-stealing optimizations that don't apply to virtual threads
- No significant benefit since actors don't block anyway

### Pekko Team Position

As of Pekko 1.x, the team hasn't prioritized virtual thread integration because actors already provide similar benefits. Future versions may explore it, but it's not a high priority.

---

## Comparison

| Aspect | Virtual Threads | Pekko Actors |
|--------|----------------|--------------|
| Concurrency model | Threads (simplified) | Message passing |
| Blocking tolerance | High | Low (don't block) |
| State isolation | Shared memory + synchronization | Actor encapsulates state |
| Debugging | Stack traces | Mailbox inspection |
| Ecosystem | New (Java 21+) | Mature (10+ years) |
| Learning curve | Low (looks like threads) | Medium (actor model) |

**For our use case:** We already paid the learning curve for actors. Switching to virtual threads would lose actor benefits (supervision, location transparency, fault isolation) without gaining anything.

---

## Future Considerations

### If Virtual Threads Make Sense Later

1. **JDBC access** - If we added a database with blocking driver, virtual threads could help
2. **Legacy integration** - Calling blocking third-party libraries
3. **Pekko evolution** - If future Pekko versions embrace virtual threads natively

### What We'd Need to Change

1. Ensure all blocking code runs on virtual thread executor
2. Test for deadlocks with mixed models
3. Profile for any performance regressions

---

## Interview Answer

> "Why don't you use virtual threads? You're on Java 21."

**Short answer:** "Pekko actors already give us millions of lightweight concurrent entities without blocking OS threads. Virtual threads solve the same problem - making blocking code scalable. Since we don't have blocking code (Pekko HTTP and actors are non-blocking), virtual threads wouldn't help us."

**Follow-up - "But virtual threads are the future of Java"**

"For traditional thread-per-request servers, yes. But actor model has been doing lightweight concurrency for decades. We'd lose actor benefits (supervision, location transparency, message passing) if we switched. Different tools for different problems."

**Follow-up - "What if you needed to call a blocking API?"**

"Good question. If we added JDBC or a blocking library, I'd run those calls on a virtual thread executor to not block Pekko's dispatcher. But I'd keep the actor model for the rest of the app."

---

## References

- [JEP 444: Virtual Threads](https://openjdk.org/jeps/444)
- [Pekko Dispatchers](https://pekko.apache.org/docs/pekko/current/dispatchers.html)
- [Don't Block the Dispatcher](https://pekko.apache.org/docs/pekko/current/typed/dispatchers.html#blocking-needs-careful-management)
- [Virtual Threads vs Reactive](https://blog.softwaremill.com/virtual-threads-vs-webflux-or-reactive-programming-57da7a4c39d1)
