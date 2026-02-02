# Technical Choices

## Core Architecture Decision: AP with CRDT Replication

### Why AP over CP?

We are a **private CDN for TfL data**. Our availability must be >= TfL's availability.

| Approach | During Partition | User Experience |
|----------|------------------|-----------------|
| CP (Raft/Paxos) | Minority side blocks | User sees error while TfL itself works |
| **AP (CRDT)** | Both sides serve data | User sees working service |

**The "failure mode" of AP** (duplicate TfL polling) is negligible:
- 3 nodes × 2 req/min = 6 req/min
- TfL rate limit ≈ 500 req/min
- No real cost to occasional duplicate polls

### Why No Leader Election?

Leader election was to minimize TfL load. But:
1. TfL load from N pollers is still negligible
2. Leader election requires coordination → availability risk
3. CRDT sharing naturally deduplicates polls anyway

---

## Data Replication: CRDT with Scatter-Gather

### The Model

```
Each node has:
  - Local LWW-Map (Last-Writer-Wins Map) CRDT
  - Background gossip with peers
  - Polling logic that checks peers before TfL
```

### LWW-Map Structure

```
Key: "all-lines" (or per-line: "central", "northern", etc.)
Value: {
  lines: [...],           // Tube status data
  tflTimestamp: ISO8601,  // TfL's timestamp
  fetchedAt: ISO8601,     // When we fetched
  nodeId: "node-1"        // Who fetched it
}
```

**Merge rule:** Keep entry with latest `fetchedAt`. Deterministic, no conflicts.

### Scatter-Gather Refresh

When a node needs fresh data:

```
1. Scatter query to:
   - All known peers (async)
   - TfL API (async)

2. Gather responses (with timeout)

3. Select freshest by fetchedAt

4. Write to local CRDT (propagates via gossip)
```

**Why scatter-gather?**
- Treats TfL and peers as equivalent data sources
- Topology-agnostic: works regardless of which connections are up
- Naturally handles partial partitions

### Configuration

```yaml
refresh:
  interval: 30s
  jitter: 5s                    # Stagger polls across nodes
  recentEnoughThreshold: 5s     # Skip TfL if peer data is fresh
  scatterGatherTimeout: 2s      # Max wait for responses

  # Optional: two-phase (polite to TfL)
  tflFallbackOnly: false        # If true: try peers first, TfL only if stale
```

---

## Technology Stack

### Runtime: Apache Pekko (Java API)

**Why Pekko over Akka?**
- Apache 2.0 license (Akka changed to BSL)
- Same battle-tested codebase
- Active community maintenance

**Pekko modules used:**
- `pekko-cluster` - Node discovery, failure detection
- `pekko-distributed-data` - CRDT replication (LWWMap)
- `pekko-http` - REST API endpoints

### Build: Gradle + Java 21

```
tfl-slo/
├── build.gradle.kts
├── src/main/java/
│   └── com/ig/tfl/
│       ├── TflApplication.java
│       ├── api/
│       │   └── TubeStatusController.java
│       ├── crdt/
│       │   └── TubeStatusReplicator.java
│       ├── client/
│       │   └── TflApiClient.java
│       └── model/
│           └── TubeStatus.java
└── src/main/resources/
    └── application.conf        # Pekko config
```

### HTTP Client: Java HttpClient (built-in)

- No external dependency
- Async support with CompletableFuture
- Sufficient for TfL API calls

### Resilience: Manual Implementation

**Why not Resilience4j?**
- Exercise asks us to demonstrate understanding
- Circuit breaker, retry, rate limiting are straightforward
- Shows we know how these work, not just how to import them

We'll implement:
- Circuit breaker (state machine: CLOSED → OPEN → HALF_OPEN)
- Exponential backoff with jitter and cap
- Token bucket rate limiter

---

## Circuit Breaker Strategy

### Scope

Circuit breaker wraps **TfL API calls**, not peer calls.

Peer calls use timeout + ignore failures (scatter-gather handles it).

### State Machine

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

### Parameters

| Parameter | Value | Rationale |
|-----------|-------|-----------|
| Failure threshold | 5 | Filters transient blips |
| Open duration | 30s | TfL typical recovery time |
| Half-open trials | 1 | Single probe before closing |
| Sliding window | 60s | Only recent failures count |

### When Open

Return cached data with degraded confidence:
```json
{
  "meta": {
    "source": "CACHE",
    "confidence": "DEGRADED",
    "circuitState": "OPEN"
  }
}
```

---

## Retry Strategy

### Exponential Backoff with Cap

```
Attempt | Base Delay | With Jitter (±25%)
--------|------------|-------------------
1       | immediate  | immediate
2       | 1s         | 750ms - 1250ms
3       | 2s         | 1500ms - 2500ms
4       | 4s         | 3000ms - 5000ms
5       | 8s         | 6000ms - 10000ms
6+      | 30s (cap)  | 22500ms - 37500ms
```

**Why cap at 30s?**
- Beyond 30s, it's a "real" outage
- Faster recovery detection than continuing exponential
- Circuit breaker takes over for prolonged outages

### What We Retry

| Response | Retry? | Rationale |
|----------|--------|-----------|
| 5xx | Yes | Server error, transient |
| Timeout | Yes | Network blip |
| Connection refused | Yes | Server restarting |
| 429 | Yes, respect Retry-After | Rate limited |
| 4xx (except 429) | No | Client error, won't fix itself |

---

## Rate Limiting

### Inbound (Clients → Us)

**Token bucket per client IP:**
- Bucket size: 100 tokens
- Refill rate: 100/minute
- Exceed → 429 with `Retry-After` header

### Outbound (Us → TfL)

**Global semaphore:**
- Max concurrent TfL requests: 10
- Max requests/minute: 100 (estimate TfL limit)
- Shared across cluster via CRDT counter (optional)

---

## Response Schema

Every response includes freshness metadata:

```json
{
  "lines": [
    {
      "id": "central",
      "name": "Central",
      "status": "Good Service",
      "disruptions": []
    }
  ],

  "meta": {
    "dataAsOf": "2026-02-02T14:30:00Z",
    "fetchedAt": "2026-02-02T14:30:05Z",
    "fetchedBy": "node-2",
    "freshnessMs": 5000,
    "source": "PEER",
    "confidence": "FRESH"
  }
}
```

| Field | Values | Meaning |
|-------|--------|---------|
| `source` | `TFL`, `PEER`, `CACHE` | Where data came from |
| `confidence` | `FRESH`, `STALE`, `DEGRADED` | Data quality |

---

## Persistence

### Do We Need It?

| Data | Persistence Need | Rationale |
|------|------------------|-----------|
| Current status | No | Refetch from TfL/peers in seconds |
| Historical (estimation) | Nice to have | SQLite/H2 embedded |
| Node state | No | Reconstructed on startup |

**Decision:** Start without persistence. Add SQLite if historical estimation feature is implemented.

---

## Testing Strategy

### Unit Tests
- Circuit breaker state transitions
- Retry logic with mocked clock
- LWW-Map merge behavior

### Integration Tests
- WireMock for TfL API
- Multi-node Pekko cluster in test
- Partition simulation

### Chaos Testing (Stretch)
- Random node kills
- Network partition injection
- TfL mock returning errors/delays

---

## What We're NOT Doing (and Why)

| Feature | Why Not |
|---------|---------|
| Kubernetes deployment | Out of scope for 3h exercise |
| Distributed tracing | Nice to have, not core |
| Prometheus metrics | Mentioned in README as improvement |
| Historical estimation | Stretch goal, time permitting |
| TLS between nodes | Assume trusted network for exercise |
