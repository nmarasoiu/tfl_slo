# Request Coalescing Strategies

## Problem

Multiple concurrent requests for the same data should share a single upstream fetch.

```
Without coalescing:          With coalescing:

Req 1 ──► TfL fetch          Req 1 ──┬──► TfL fetch
Req 2 ──► TfL fetch          Req 2 ──┤      │
Req 3 ──► TfL fetch          Req 3 ──┴──────┴──► shared result
     3 fetches                    1 fetch
```

---

## Strategy Comparison

| Strategy | Complexity | Throughput | Latency | When to Use |
|----------|------------|------------|---------|-------------|
| **synchronized + Caffeine** | Trivial | 10K req/s | ~μs contention | Default choice |
| Caffeine AsyncLoadingCache | Zero | 50K+ req/s | Lock-free | Already async |
| AtomicReference<Future> | Low | 100K+ req/s | CAS only | Custom control |
| Pekko Actor + Stash | Medium | 50K+ req/s | Mailbox | Already in Pekko |
| LMAX Disruptor | High | 1M+ req/s | Mechanical sympathy | Trading systems |

---

## 1. synchronized + Caffeine (Recommended)

**Complexity:** Trivial
**Our choice:** Yes

```java
public class TubeStatusService {
    private final Cache<String, TubeStatus> cache = Caffeine.newBuilder()
        .expireAfterWrite(30, TimeUnit.SECONDS)
        .build();

    private final TflApiClient tflClient;

    public synchronized TubeStatus getStatus() {
        return cache.get("all-lines", key -> tflClient.fetchAllLines());
    }
}
```

**How it works:**
1. `synchronized` ensures only one thread enters at a time
2. First thread checks cache, misses, fetches from TfL
3. Other threads block on monitor, wake up, find cache populated
4. Caffeine's `get(key, loader)` handles the "load once" internally

**Why this is fine:**
- At 1000 req/s, threads hold lock for ~1ms (cache check)
- Contention cost: ~1-10μs per request
- Total overhead: negligible compared to network I/O

**When it breaks down:**
- 100K+ req/s: monitor contention becomes measurable
- Strict p99 latency requirements: occasional long waits

---

## 2. Caffeine AsyncLoadingCache (Zero Code)

**Complexity:** Zero
**Good for:** Already async codebases

```java
AsyncLoadingCache<String, TubeStatus> cache = Caffeine.newBuilder()
    .expireAfterWrite(30, TimeUnit.SECONDS)
    .refreshAfterWrite(25, TimeUnit.SECONDS)  // Background refresh
    .buildAsync(key -> tflClient.fetchAllLinesAsync());

public CompletableFuture<TubeStatus> getStatusAsync() {
    return cache.get("all-lines");
}
```

**How it works:**
1. Caffeine uses CAS to install a "loading" sentinel
2. First caller wins CAS, starts async load
3. Other callers see sentinel, return same CompletableFuture
4. All callers complete when future completes

**Internals (simplified):**
```java
// Caffeine's internal logic (conceptual)
CompletableFuture<V> get(K key) {
    Node node = table.get(key);
    if (node == null) {
        CompletableFuture<V> future = new CompletableFuture<>();
        if (table.CAS(key, null, new LoadingNode(future))) {
            // Won the race, start loading
            loader.apply(key).thenAccept(future::complete);
        } else {
            // Lost, someone else is loading
            return table.get(key).future;
        }
        return future;
    }
    return node.isLoading() ? node.future : completedFuture(node.value);
}
```

**Advantages:**
- Lock-free (CAS only)
- `refreshAfterWrite` returns stale immediately, refreshes in background
- Battle-tested at scale

---

## 3. AtomicReference<CompletableFuture> (Manual CAS)

**Complexity:** Low
**Good for:** Custom coalescing logic

```java
private final AtomicReference<CompletableFuture<TubeStatus>> inFlight =
    new AtomicReference<>();

public CompletableFuture<TubeStatus> getStatus() {
    while (true) {
        CompletableFuture<TubeStatus> existing = inFlight.get();

        // Join existing in-flight request
        if (existing != null && !existing.isDone()) {
            return existing;
        }

        // Try to start new fetch
        CompletableFuture<TubeStatus> newFetch = new CompletableFuture<>();
        if (inFlight.compareAndSet(existing, newFetch)) {
            // Won the race, do the fetch
            tflClient.fetchAllLinesAsync()
                .whenComplete((result, error) -> {
                    if (error != null) {
                        newFetch.completeExceptionally(error);
                    } else {
                        newFetch.complete(result);
                    }
                    inFlight.compareAndSet(newFetch, null);  // Clear
                });
            return newFetch;
        }
        // Lost race, retry loop
    }
}
```

**How it works:**
1. CAS loop to install our future
2. Winner starts fetch, losers return winner's future
3. Clear reference on completion

**Advantages:**
- Full control over coalescing window
- Can add custom timeout logic
- No external dependencies

**Disadvantages:**
- Manual lifecycle management
- Easy to get wrong (reference leaks, race conditions)

---

## 4. Pekko Actor + Stash

**Complexity:** Medium
**Good for:** Already in actor system

```java
public class CoalescingActor extends AbstractBehavior<Command> {
    private final List<ActorRef<Response>> waiters = new ArrayList<>();
    private boolean loading = false;

    private Behavior<Command> onGetStatus(GetStatus msg) {
        waiters.add(msg.replyTo);

        if (!loading) {
            loading = true;
            fetchFromTfl();
        }
        return this;
    }

    private Behavior<Command> onFetchComplete(FetchComplete msg) {
        // Notify ALL waiters
        for (var waiter : waiters) {
            waiter.tell(new Response(msg.status));
        }
        waiters.clear();
        loading = false;
        return this;
    }
}
```

**How it works:**
1. Actor mailbox serializes all requests
2. First request triggers fetch, subsequent requests queued
3. On completion, reply to all queued requests

**Advantages:**
- Natural fit for actor systems
- Mailbox handles queuing
- Easy timeout handling

**Disadvantages:**
- Actor overhead (~100ns per message)
- Single-threaded bottleneck (but that's the point)

---

## 5. LMAX Disruptor (Nuclear Option)

**Complexity:** High
**Good for:** >100K req/s, strict latency requirements

```java
// Ring buffer setup
RingBuffer<RequestEvent> ringBuffer = RingBuffer.createMultiProducer(
    RequestEvent::new,
    1024,
    new YieldingWaitStrategy()
);

// Producer (request handler)
public CompletableFuture<TubeStatus> getStatus() {
    CompletableFuture<TubeStatus> future = new CompletableFuture<>();
    long sequence = ringBuffer.next();
    try {
        RequestEvent event = ringBuffer.get(sequence);
        event.setFuture(future);
    } finally {
        ringBuffer.publish(sequence);
    }
    return future;
}

// Consumer (batch processor)
public void onEvent(RequestEvent event, long sequence, boolean endOfBatch) {
    pendingFutures.add(event.getFuture());

    if (endOfBatch) {
        // Fetch once for entire batch
        TubeStatus result = tflClient.fetchAllLines();

        // Complete all futures
        for (var future : pendingFutures) {
            future.complete(result);
        }
        pendingFutures.clear();
    }
}
```

**How it works:**
1. Producers publish to ring buffer (lock-free)
2. Consumer processes in batches
3. Single fetch serves entire batch
4. Mechanical sympathy: cache-line padding, no false sharing

**Advantages:**
- Highest throughput possible
- Predictable latency (no GC from allocations)
- Batch processing amortizes fetch cost

**Disadvantages:**
- Complex setup and tuning
- Fixed ring size (backpressure if full)
- Requires understanding of memory barriers

**When to use:**
- Trading systems (IG's actual matching engine, probably)
- Market data distribution
- NOT for tube status at 100 req/s

---

## Decision Matrix

```
                    Throughput requirement

        Low (<10K)          High (>100K)
        ┌───────────────────┬───────────────────┐
Simple  │ synchronized +    │ Caffeine          │
        │ Caffeine          │ AsyncLoadingCache │
        ├───────────────────┼───────────────────┤
Complex │ AtomicReference   │ LMAX Disruptor    │
        │ (why?)            │                   │
        └───────────────────┴───────────────────┘
```

---

## Virtual Threads (Java 21) - Changes the Game

With virtual threads, blocking becomes cheap again. But there's a nuance:

### synchronized vs ReentrantLock

```java
// BAD with virtual threads - pins carrier thread
public synchronized TubeStatus getStatus() {
    return cache.get("all-lines", key -> tflClient.fetch());
}

// GOOD with virtual threads - doesn't pin
private final ReentrantLock lock = new ReentrantLock();

public TubeStatus getStatus() {
    lock.lock();
    try {
        return cache.get("all-lines", key -> tflClient.fetch());
    } finally {
        lock.unlock();
    }
}
```

**Why `synchronized` pins:**
- JVM can't unmount virtual thread while holding monitor
- Carrier thread (platform thread) is blocked
- Defeats the purpose of virtual threads

**Why `ReentrantLock` doesn't pin:**
- Uses `LockSupport.park()` internally
- Virtual thread unmounts, carrier freed
- Millions of virtual threads can wait on same lock

### Recommendation with Virtual Threads

```java
// Best of both worlds: simple blocking code, massive concurrency
ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

private final ReentrantLock lock = new ReentrantLock();
private final Cache<String, TubeStatus> cache = Caffeine.newBuilder()
    .expireAfterWrite(30, TimeUnit.SECONDS)
    .build();

public TubeStatus getStatus() {
    lock.lock();
    try {
        return cache.get("all-lines", key -> tflClient.fetch());
    } finally {
        lock.unlock();
    }
}
```

**Result:**
- Simple blocking code (easy to reason about)
- Scales to 100K+ concurrent requests
- No Disruptor complexity needed

### When Virtual Threads DON'T Help

- CPU-bound work (still limited by cores)
- Native code / JNI (pins carrier)
- `synchronized` blocks (pins carrier)

For I/O-bound coalescing (waiting on TfL API): **virtual threads are perfect**.

---

## Our Choice: Simple + Virtual Threads

For tube status service:
- ~100-1000 req/s expected (could handle 100K+ with virtual threads)
- Latency requirement: <100ms p99 (trivial)
- Already have Caffeine
- Java 21 with virtual threads

**Implementation:**
```java
private final ReentrantLock lock = new ReentrantLock();
private final Cache<String, TubeStatus> cache = Caffeine.newBuilder()
    .expireAfterWrite(30, TimeUnit.SECONDS)
    .build();

// Simple blocking code, scales with virtual threads
public TubeStatus getStatus() {
    lock.lock();
    try {
        return cache.get("all-lines", key -> queryCascade.execute());
    } finally {
        lock.unlock();
    }
}
```

**Why this works:**
- `ReentrantLock` doesn't pin virtual threads
- Caffeine's internal coalescing handles concurrent loads
- Simple to reason about, test, debug

**Document the alternatives** (this file) to show we considered them.

---

## Interview Talking Points

If asked "why not Disruptor?":
> "Disruptor shines at 100K+ req/s with strict latency percentiles. For tube status at 1K req/s, the complexity tax doesn't pay off. We'd use it for actual trading data, not commute information."

If asked "what if scale increased 100x?":
> "First, verify Caffeine's built-in coalescing isn't sufficient - it's already lock-free. If we need more, AtomicReference<Future> gives us control without Disruptor's complexity. Disruptor is the final escalation."
