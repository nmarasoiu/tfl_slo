# Architecture

## Core Design: Cache-First with Freshness Transparency

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

---

## Response Schema

```json
{
  "lines": [...],
  "meta": {
    "queriedAt": "2026-02-02T14:30:00Z",
    "queriedBy": "node-1",
    "ageMs": 125000
  }
}
```

| Field | Meaning |
|-------|---------|
| `queriedAt` | UTC timestamp when TfL was queried (immutable through CRDT) |
| `queriedBy` | Which node performed the query |
| `ageMs` | Age of data in milliseconds |

**One timestamp. Immutable. Client knows the truth.**

---

## System Diagram

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

---

## Multi-Node CRDT Replication

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
- No leader election needed

**Replication strategy (AP with aggressive best-effort):**
```
TfL fetch completes
        │
        ├──► currentStatus = fresh  (immediate, serves HTTP)
        │
        └──► WriteMajority(2s)      (async, best-effort)
                    │
                    ├── success: majority have data in ~200ms
                    └── timeout: gossip propagates eventually
```
- HTTP responses never wait for replication
- WriteMajority is optimization, not requirement
- Gossip interval: 200ms (fast convergence)
- Delta-CRDT enabled (efficient wire format)

---

## Query Tiers

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

**Currently implemented:** Tier 0 (local) → Tier 1 (TfL) → Fallback

**Future enhancement:** Add explicit peer scatter-gather between Tier 0 and TfL:
```
Tier 0: Local CRDT replica (readLocal)
Tier 1: Cluster majority (ReadMajority) ← not yet implemented
Tier 2: Cluster all (ReadAll) ← not yet implemented
Tier 3: TfL API
```

The CRDT gossip propagates data between nodes in the background, but explicit
scatter-gather queries to peers would reduce TfL calls further.

**Local replica IS the cache. No separate Caffeine layer needed.**

### Why No Durable Storage?

Considered and rejected. For data loss to matter, you'd need:
1. All nodes crash simultaneously
2. AND data in DB still fresh enough to be useful (tube status has short TTL)
3. AND DB reachable while TfL is not

This is an unlikely conjunction. On restart, nodes warm up from TfL in seconds.
The "disaster recovery" for a cache is just... refetching from origin.

If we added persistence, we'd be optimizing for a scenario where:
- TfL is down (can't refetch)
- All our nodes crashed (lost in-memory)
- But our DB is up (can recover)
- And the persisted data is < 5 minutes old (still useful)

Over-engineering. The simpler answer: run enough nodes that simultaneous crash is unlikely.

---

## Coalescing: Actor Mailbox

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

## Actors vs Streams

| Aspect | Typed Actors | Pekko Streams |
|--------|--------------|---------------|
| State | Natural (actor encapsulates) | Awkward (statefulMapConcat) |
| Request-Response | Natural (ask pattern) | Awkward |
| Distribution | Easy (remote actors) | Hard (streams are local) |

**Decision:** Actors for stateful coordination (TubeStatusReplicator), Streams where already used (Pekko HTTP).

---

## Circuit Breaker

```
Client ──► CRDT Cache ──► Response (always works)
              ▲
              │
         Background
         Refresher ──► Circuit Breaker ──► TfL API
```

| State | Behavior |
|-------|----------|
| CLOSED | Normal operation |
| OPEN | Stop calling TfL, serve cached, wait 30s |
| HALF_OPEN | Try one request, close on success |

Config: 5 failures → OPEN, 30s recovery.

---

## Retry Policy

```
Attempt 1: immediate
Attempt 2: 1s ± jitter
Attempt 3: 2s ± jitter
Attempt 4: 4s ± jitter (capped at 30s)
```

| Response | Retry? |
|----------|--------|
| 5xx | Yes |
| 429 | Yes |
| 4xx | No |

---

## Rate Limiting

Token bucket per client IP:
- 100 tokens/minute
- Exceed → 429 with Retry-After header

---

## Future Enhancement: Explicit Stale Serving

Currently: always serve with ageMs, client decides.

Future option:
```
GET /api/v1/tube/status?maxAgeMs=300000  → 503 if older than 5 min
GET /api/v1/tube/status?allowStale=true  → Returns with warning
```

Not implemented: returning invented/estimated data is dangerous without explicit request.
