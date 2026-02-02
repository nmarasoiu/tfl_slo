# Query Cascade Design

## Overview

A tiered query strategy that maximizes availability while minimizing load on TfL.
Each tier has configurable freshness thresholds and timeouts.

```
        ┌─────────────┐
        │   Request   │
        └──────┬──────┘
               │
               ▼
        ┌─────────────┐
        │  Tier 0:    │──── HIT + FRESH ────► Response
        │  Local RAM  │
        └──────┬──────┘
               │ MISS/STALE
               ▼
        ┌─────────────┐
        │  Tier 1:    │──── PEER FRESH ────► Response
        │  Cluster    │                       + async gossip
        └──────┬──────┘
               │ ALL STALE/TIMEOUT
               ▼
        ┌─────────────┐
        │  Tier 2..N: │──── (optional additional rounds)
        │  Extended   │
        └──────┬──────┘
               │
               ▼
        ┌─────────────┐
        │  Tier N:    │──── SUCCESS ────► Response
        │  TfL API    │                   + cache + gossip
        └──────┬──────┘
               │ FAILURE
               ▼
        ┌─────────────┐
        │  Fallback:  │
        │  Best Stale │
        └─────────────┘
```

---

## Tier Configuration

```yaml
query-cascade:
  tiers:
    - name: local
      type: LOCAL_CACHE
      freshness-threshold: 2s      # Consider fresh if < 2s old
      timeout: 0ms                  # Instant (in-process)
      enabled: true

    - name: cluster
      type: PEER_SCATTER_GATHER
      freshness-threshold: 5s      # Accept peer data if < 5s old
      timeout: 500ms               # Max wait for peer responses
      peer-selection:
        strategy: ALL              # ALL, RANDOM_N, NEAREST
        count: 3                   # If RANDOM_N
      enabled: true

    # Optional: additional tiers for multi-region, etc.
    # - name: regional
    #   type: PEER_SCATTER_GATHER
    #   freshness-threshold: 30s
    #   timeout: 1s
    #   peer-selection:
    #     strategy: REGIONAL
    #   enabled: false

    - name: tfl
      type: UPSTREAM_API
      freshness-threshold: null    # Always fresh by definition
      timeout: 10s                 # Includes retries
      circuit-breaker:
        enabled: true
        failure-threshold: 5
        open-duration: 30s
      enabled: true

  fallback:
    serve-stale: true              # Return stale data if all tiers fail
    max-stale-age: 5m              # Don't serve data older than this
```

---

## Tier Details

### Tier 0: Local RAM (Caffeine Cache)

**Purpose:** Instant response for hot data.

**Behavior:**
1. Check local Caffeine cache
2. If entry exists AND `now - fetchedAt < freshnessThreshold`:
   - Return immediately
   - Source: `LOCAL`
3. Else: proceed to Tier 1

**Cost:** ~1μs (in-process hashmap lookup)

**Cache eviction:** LRU, max 1000 entries (way more than needed for 11 lines)

---

### Tier 1: Cluster (Peer Scatter-Gather)

**Purpose:** Get fresh data from peers without hitting TfL.

**Behavior:**
1. Send parallel requests to selected peers
2. Wait up to `timeout` for responses
3. Collect all responses that arrived
4. If ANY response has `fetchedAt > now - freshnessThreshold`:
   - Use the freshest one
   - Update local cache
   - Gossip this data back (async, don't wait)
   - Return response, Source: `PEER`
5. Else: proceed to Tier 2/N

**Peer Selection Strategies:**

| Strategy | Description | Use When |
|----------|-------------|----------|
| ALL | Query all known peers | Small cluster (3-5 nodes) |
| RANDOM_N | Query N random peers | Larger cluster |
| NEAREST | Query by network latency | Multi-AZ deployment |
| HASH_RING | Consistent hashing | Sharded data (future) |

**Failure handling:**
- Peer timeout: ignore that peer, use others
- Peer error: ignore that peer, use others
- All peers fail: proceed to next tier (not an error)

---

### Tier 2..N-1: Extended Rounds (Optional)

**Purpose:** Additional protection layers before hitting TfL.

**Examples:**
- Regional cluster (query peers in other regions)
- Secondary cache (Redis, if we had one)
- Partner API (if TfL had mirrors)

**Configuration:** Disabled by default, enable per environment.

---

### Tier N: TfL API

**Purpose:** Source of truth, final tier.

**Behavior:**
1. Call TfL API with circuit breaker + retry
2. On success:
   - Update local cache
   - Write to CRDT (gossips to all peers)
   - Return response, Source: `TFL`
3. On failure:
   - If circuit is OPEN: skip to fallback
   - If retries exhausted: skip to fallback

---

### Fallback: Best Stale Data

**Purpose:** Always return something usable.

**Behavior:**
1. Return the freshest data we have (local cache, even if stale)
2. If we have data < `maxStaleAge`:
   - Return it with Confidence: `DEGRADED`
3. If we have no data or data > `maxStaleAge`:
   - Return error (this should be rare in steady state)

---

## CRDT Sharding Decision

### Current: Single Key

```
Key: "tube-status-all"
Value: {
  lines: [...all 11 lines...],
  fetchedAt: timestamp,
  fetchedBy: nodeId
}
```

**Rationale:**
- TfL API returns all lines atomically
- Total payload is ~2KB (trivial)
- Simplifies freshness reasoning (one timestamp for everything)

### Future Option: Per-Line Sharding

```
Keys: "tube-status:central", "tube-status:northern", ...
Values: {
  line: {...single line...},
  fetchedAt: timestamp,
  fetchedBy: nodeId
}
```

**When to consider:**
- If we add per-line queries to TfL (different lines have different freshness)
- If individual line updates become frequent
- If we want partial responses (some lines fresh, some stale)

**Migration path:** Add per-line keys alongside aggregate key, deprecate aggregate later.

---

## Sequence Diagram

```
Client          Node-1           Node-2           Node-3           TfL
  │                │                │                │               │
  │─── GET /status─►│                │                │               │
  │                │                │                │               │
  │                │── check local ──┐               │               │
  │                │◄── MISS ────────┘               │               │
  │                │                │                │               │
  │                │── scatter ─────►│               │               │
  │                │── scatter ──────────────────────►│              │
  │                │                │                │               │
  │                │◄── "5s old" ────┤               │               │
  │                │◄── "3s old" ─────────────────────┤              │
  │                │                │                │               │
  │                │   (3s < 5s threshold, use it)   │               │
  │                │                │                │               │
  │◄── response ───┤                │                │               │
  │  (source:PEER) │                │                │               │
  │                │                │                │               │
  │                │── gossip (async) ──────────────►│               │
  │                │                │                │               │
```

---

## Metrics per Tier

```java
Counter.builder("query_cascade_tier_hits")
    .tag("tier", tierName)
    .description("Requests satisfied by this tier")
    .register(registry);

Timer.builder("query_cascade_tier_latency")
    .tag("tier", tierName)
    .register(registry);

Counter.builder("query_cascade_tier_skipped")
    .tag("tier", tierName)
    .tag("reason", "timeout|fresh_found|disabled")
    .register(registry);
```

**Key dashboard metrics:**
- Tier 0 hit rate: Should be >90% in steady state
- Tier 1 hit rate: Should satisfy most cache misses
- Tier N (TfL) hit rate: Should be rare (~1 req/30s per cluster)

---

## Configuration Tuning Guide

| Scenario | Adjustment |
|----------|------------|
| TfL is flaky | Relax Tier 1 threshold (5s → 15s) |
| Need fresher data | Tighten Tier 0 threshold (2s → 1s) |
| Large cluster | Use RANDOM_N peer selection |
| Cost sensitive | Add more tiers before TfL |
| Latency sensitive | Reduce Tier 1 timeout (500ms → 200ms) |

---

## Invariants

1. **Tier order is fixed:** Local → Cluster → TfL
2. **Early exit:** If any tier succeeds with fresh data, skip remaining tiers
3. **Async gossip:** Never block response on gossip completion
4. **Fallback always works:** If we have any data, serve it (with appropriate confidence)
5. **Single writer to CRDT:** The node that fetches from TfL writes to CRDT

---

## Open Questions (Future Work)

1. **Request coalescing:** If 100 requests arrive while Tier N is in flight, do we coalesce?
2. **Speculative execution:** Start Tier 1 while Tier 0 is checking (parallel)?
3. **Adaptive freshness:** Auto-adjust thresholds based on TfL health?
4. **Read-your-writes:** If this node just wrote, should it skip Tier 1?
