# Design

This document covers architecture, technology choices, and trade-offs in one place.

---

## 1. Core Decision: AP with CRDT

### Why AP (Availability-first) over CP?

We are a **private CDN for TfL data**. Our availability must exceed TfL's.

| Approach | During Partition | User Experience |
|----------|------------------|-----------------|
| CP (Raft/Paxos) | Minority side blocks | User sees error while TfL itself works |
| **AP (CRDT)** | Both sides serve data | User gets data from any reachable node |

The "failure mode" of AP (duplicate TfL polling) is negligible:
- 3 nodes × 2 req/min = 6 req/min
- TfL rate limit ≈ 500 req/min
- No real cost to occasional duplicate polls

### Why No Leader Election?

Leader election was considered to minimize TfL load. But:
1. N pollers at 2 req/min each is still negligible
2. Leader election requires coordination → availability risk
3. CRDT sharing naturally deduplicates most polls

Each node polls independently. CRDT replication means usually only one actually hits TfL.

### Why LWW-Register Works Here

```
Key: "tube-status"
Value: {
  lines: [...],
  queriedAt: ISO8601,
  queriedBy: "node-1"
}

Merge rule: Keep entry with latest queriedAt. Deterministic, no conflicts.
```

For a cache of ephemeral data, Last-Writer-Wins is ideal:
- Freshest data always wins (that's what we want)
- No complex merge logic needed
- Automatic convergence via gossip

### Why No Durable Storage?

For data loss to matter, you'd need:
1. All nodes crash simultaneously
2. AND data in DB still fresh enough to be useful (tube status has short TTL)
3. AND DB reachable while TfL is not

This is an unlikely conjunction. On restart, nodes warm up from TfL in seconds.
The "disaster recovery" for a cache is just... refetching from origin.

---

## 2. System Architecture

### Cache-First Principle

```
Client Request
      │
      ▼
┌─────────────┐     always
│   Service   │◄────returns────┐
└─────────────┘                │
      │                        │
      │ async refresh          │
      ▼                        │
┌─────────────┐         ┌──────┴──────┐
│   TfL API   │────────►│ CRDT Cache  │
└─────────────┘  write  │ (Pekko DD)  │
                        └─────────────┘
```

**Principle:** Always return data from cache. Never block on TfL API for client requests.

### System Diagram

```
                            ┌──────────────────────────────────────────────┐
                            │                    NODE                      │
                            │                                              │
   HTTP Request             │  ┌─────────────────────────────────────┐    │
        │                   │  │         TubeStatusReplicator        │    │
        │                   │  │              (Actor)                │    │
        ▼                   │  │                                     │    │
┌───────────────┐           │  │  ┌─────────────────────────────┐   │    │
│  HTTP Routes  │◄──────────┼──┤  │ currentStatus (local copy) │   │    │
│   (Handler)   │  ask      │  │  └─────────────────────────────┘   │    │
└───────────────┘           │  └──────────────┬──────────────────────┘    │
        │                   │                 │                           │
        │                   │                 │ read/write                │
        │                   │                 ▼                           │
        │                   │  ┌─────────────────────────────────────┐    │
        │                   │  │      Pekko Replicator (Actor)       │    │
        │                   │  │                                     │    │
        │                   │  │  ╔═══════════════════════════════╗  │    │
        │                   │  │  ║   LWW-Register<TubeStatus>    ║  │    │
        │                   │  │  ║         (CRDT)                ║  │    │
        │                   │  │  ╚═══════════════════════════════╝  │    │
        │                   │  └──────────────┬──────────────────────┘    │
        │                   │                 │ gossip                    │
        │                   │                 ▼                           │
        │                   │         ═══════════════════                 │
        │                   │          To other nodes                     │
        │                   └──────────────────────────────────────────────┘
        │
        │ rate limit check
        ▼
┌───────────────┐           ┌─────────────────┐
│  RateLimiter  │           │   TflApiClient  │───► TfL API
│ (per-client)  │           │ + CircuitBreaker│
└───────────────┘           └─────────────────┘
```

### Multi-Node CRDT Replication

```
   NODE 1                    NODE 2                    NODE 3
  ┌──────────────────┐      ┌──────────────────┐      ┌──────────────────┐
  │ LWW-Register     │      │ LWW-Register     │      │ LWW-Register     │
  │ (local replica)  │◄────►│ (local replica)  │◄────►│ (local replica)  │
  └────────┬─────────┘      └────────┬─────────┘      └────────┬─────────┘
           │                         │                         │
           └─────────────────────────┴─────────────────────────┘
                              gossip protocol
                         (delta-based, crdt merge)
```

- Each node can poll TfL independently
- CRDT replication shares data automatically
- LWW (Last-Writer-Wins): freshest timestamp wins
- Gossip interval: 200ms (fast convergence)

### Query Tiers

```
Request with ?maxAgeMs=60000
   │
   ▼
Tier 0: Local CRDT replica ──── FRESH ENOUGH ────► Response
   │
   │ TOO STALE
   ▼
Tier 1: TfL API ──── SUCCESS ────► Response + update CRDT
   │
   │ FAILURE (circuit open, timeout, etc.)
   ▼
Fallback: Return stale with ageMs + X-Data-Stale header
```

### Tiered Freshness Strategy

```
Client request with maxAgeMs=250
            │
            ▼
    Local cache age?
            │
    ┌───────┴───────┬─────────────────┐
    │               │                 │
  ≤5s             5-250ms           >250ms
(very fresh)   (slightly stale)   (too stale)
    │               │                 │
    ▼               ▼                 ▼
  Return        Return +           Wait for
immediately   trigger async       TfL fetch
              background
              refresh
```

The soft threshold (5s) keeps the cache warm proactively.

### Coalescing via Actor Mailbox

```
┌─────────────────────────────────────────────────┐
│              TubeStatusReplicator               │
│                                                 │
│  ┌─────────────┐      ┌──────────────────────┐ │
│  │   Mailbox   │ ───► │  LWW-Register CRDT   │ │
│  │ (coalesces  │      │  (local replica =    │ │
│  │  requests)  │      │   in-memory cache)   │ │
│  └─────────────┘      └──────────────────────┘ │
└─────────────────────────────────────────────────┘
```

Actor processes one message at a time = natural coalescing.
No Caffeine, no Disruptor, no AtomicReference tricks.

---

## 3. Resilience Patterns

### Circuit Breaker

Protects against TfL API failures.

| Parameter | Value | Rationale |
|-----------|-------|-----------|
| Failure threshold | 5 | Filters transient blips |
| Open duration | 30s | Typical TfL recovery time |
| Half-open trials | 1 | Single probe before closing |

**State machine:**
```
        5 consecutive failures
CLOSED ────────────────────────► OPEN
   ▲                               │
   │                               │ 30s timeout
   │         success               ▼
   └─────────────────────────── HALF_OPEN
                                   │
                          failure  │
                                   ▼
                                 OPEN
```

When open: Serve cached data, don't call TfL.

### Retry Policy

Exponential backoff with jitter:

| Attempt | Base Delay | With Jitter (±25%) |
|---------|------------|-------------------|
| 1 | immediate | immediate |
| 2 | 1s | 750ms - 1250ms |
| 3 | 2s | 1500ms - 2500ms |
| 4 | 4s | 3000ms - 5000ms |
| 5+ | 30s (cap) | 22500ms - 37500ms |

**What we retry:**

| Response | Retry? | Rationale |
|----------|--------|-----------|
| 5xx | Yes | Server error, transient |
| Timeout | Yes | Network blip |
| 429 | Yes | Rate limited, respect Retry-After |
| 4xx (except 429) | No | Client error, won't fix itself |

### Rate Limiting

Token bucket per client IP:
- 100 tokens/minute
- Exceed → 429 with `Retry-After` header
- `X-RateLimit-Remaining` header on each response

---

## 4. Technology Stack

### Runtime: Apache Pekko

**Why Pekko over Akka?**
- Apache 2.0 license (Akka changed to BSL)
- Same battle-tested codebase
- Active community maintenance

**Modules used:**
- `pekko-cluster` - Node discovery, failure detection
- `pekko-distributed-data` - CRDT replication (LWWRegister)
- `pekko-http` - REST API endpoints

### Why Manual Resilience Implementation?

The exercise asks us to demonstrate understanding. Circuit breaker, retry, rate limiting are straightforward to implement and show we know how they work, not just how to import Resilience4j.

### Actors vs Streams

| Aspect | Typed Actors | Pekko Streams |
|--------|--------------|---------------|
| State | Natural (actor encapsulates) | Awkward (statefulMapConcat) |
| Request-Response | Natural (ask pattern) | Awkward |
| Distribution | Easy (remote actors) | Hard (streams are local) |

**Decision:** Actors for stateful coordination, Streams where already used (Pekko HTTP).

---

## 5. Trade-offs

### What We Prioritized

1. **Availability over consistency** - Serve stale data rather than error
2. **Simplicity over optimization** - No leader election complexity
3. **Transparency over hiding** - Response includes freshness metadata
4. **Resilience over performance** - Circuit breaker may delay first request

### What We Sacrificed

1. **Minimal TfL load** - Each node may poll independently during partitions
2. **Strong consistency** - Brief periods where nodes have different data
3. **Historical data** - No persistent storage

### Key Trade-off Tables

**Freshness vs Availability:**

| Approach | Freshness | Availability | Complexity |
|----------|-----------|--------------|------------|
| Always hit TfL API | Real-time | Tied to TfL uptime | Low |
| Cache with TTL | Stale by TTL | High (serve stale) | Medium |
| **Cache + background refresh** | Near real-time | High | Higher |

**Circuit Breaker Strategy:**

- Fail fast: Circuit open → immediate 503 → client knows to back off
- **Graceful (chosen):** Circuit open → serve cached → client unaware of upstream issues

Trading platform context: stale data > no data.

**SLO Target Reality:**

Your SLO cannot exceed TfL's reliability (~99.5%). We target 99.9% because caching decouples us from TfL - we can serve stale when TfL is down.

---

## 6. What We're NOT Doing (and Why)

| Feature | Why Not |
|---------|---------|
| Kubernetes deployment | Out of scope for 3h exercise |
| Distributed tracing | Nice to have, not core |
| Prometheus metrics | First priority for prod, but time constraint |
| Historical estimation | Stretch goal |
| TLS between nodes | Assume trusted network for exercise |
| Persistent storage | Over-engineering for cache (see section 1) |

---

## 7. Multi-Datacenter (Future)

Pekko supports DC-aware replication:

```bash
PEKKO_DC=eu-west-1 ./gradlew run
PEKKO_DC=us-east-1 ./gradlew run
```

| Consistency | Behavior |
|-------------|----------|
| `WriteMajority` | Majority of ALL nodes (current) |
| `WriteMajorityPlus` | Majority from LOCAL DC + some remote |
| `WriteLocal` | Local only, cross-DC via gossip |

Current setup uses `WriteMajority` - fine for single-region multi-AZ.
