# Architecture Decisions

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
│   TfL API   │────────►│    Cache    │
└─────────────┘  write  │ (Hazelcast/ │
                        │   Redis)    │
                        └─────────────┘
```

### Key Principle

**Always return data from cache. Never block on TfL API for client requests.**

This decouples our availability from TfL's availability:
- TfL down → we still serve (stale but available)
- TfL slow → our latency unaffected
- TfL rate-limits us → clients unaffected

### Response Schema

Every response includes freshness metadata **in the body** (not just HTTP headers):

```json
{
  "line": "central",
  "status": "Good Service",
  "disruptions": [],

  "meta": {
    "dataAsOf": "2026-02-02T14:30:00Z",
    "fetchedAt": "2026-02-02T14:30:05Z",
    "freshnessSeconds": 125,
    "source": "CACHE",
    "confidence": "FRESH"
  }
}
```

| Field | Meaning |
|-------|---------|
| `dataAsOf` | TfL's timestamp for this data (from their response) |
| `fetchedAt` | When we last successfully fetched from TfL |
| `freshnessSeconds` | Age of data: `now() - fetchedAt` |
| `source` | `CACHE`, `LIVE`, or `ESTIMATED` |
| `confidence` | `FRESH` (<5min), `STALE` (5-30min), `DEGRADED` (>30min) |

### Why in the Response Body?

1. **Client awareness** - clients can make decisions based on freshness
2. **Auditable** - the response is self-describing
3. **Works with any client** - no HTTP header parsing required
4. **Cacheable downstream** - proxies won't strip it

### Degradation Modes

| TfL State | Our Behavior | `source` | `confidence` |
|-----------|--------------|----------|--------------|
| Healthy | Background refresh every 30s | `CACHE` | `FRESH` |
| Slow (>2s) | Serve cache, keep trying | `CACHE` | `FRESH` |
| 5xx errors | Serve cache, circuit breaker | `CACHE` | `STALE` |
| Down >10min | Serve cache, alert | `CACHE` | `DEGRADED` |
| Down >30min | Optional: historical estimation | `ESTIMATED` | `DEGRADED` |

### Historical Estimation (Stretch Goal)

Tube status follows patterns:
- Weekday rush hours → higher disruption probability
- Weekends → engineering works
- Historical same-day-of-week data

If TfL is down for extended period, we could:
1. Use last known state (most conservative)
2. Use historical probability for this time/day
3. Flag clearly as `ESTIMATED`

This is **transparent degradation** - we never lie about what we know.

---

## Circuit Breaker Strategy

### When Circuit Opens

The circuit breaker protects **background refresh**, not client requests:

```
Client ──► Cache ──► Response (always works)
              ▲
              │
         Background
         Refresher ──► Circuit Breaker ──► TfL API
```

Circuit states:
- **CLOSED**: Normal operation, refresh every 30s
- **OPEN**: Stop hammering TfL, serve stale cache, wait 30s
- **HALF_OPEN**: Try one request, if success → CLOSED, if fail → OPEN

### Why 5 Failures / 30s Recovery?

| Parameter | Value | Rationale |
|-----------|-------|-----------|
| Failure threshold | 5 | Filters transient blips, catches real outages |
| Recovery timeout | 30s | Matches TfL's typical recovery time for transient issues |
| Sliding window | 60s | Failures must be recent to count |

These are **starting points**. In production, tune based on observed TfL behavior.

---

## Retry Policy

### Exponential Backoff with Jitter

```
Attempt 1: immediate
Attempt 2: 1s ± 250ms
Attempt 3: 2s ± 500ms
Attempt 4: 4s ± 1s
```

Total worst case: ~7s

### Why Jitter?

Without jitter, if TfL recovers after an outage, all clients retry at the same moment → thundering herd → TfL goes down again.

Jitter spreads retries across time, allowing gradual recovery.

### What We Retry

| Response | Retry? | Rationale |
|----------|--------|-----------|
| 5xx | Yes | Server error, might recover |
| Timeout | Yes | Network blip |
| 429 | Yes, with Retry-After | Respect their rate limit |
| 4xx (except 429) | No | Client error, won't fix itself |
| Connection refused | Yes | Server might be restarting |

---

## Rate Limiting

### Two Dimensions

1. **Inbound** (our clients → us): 100 req/min per IP
2. **Outbound** (us → TfL): Respect TfL's limits, likely ~500 req/min

### Implementation

Inbound: Token bucket per client IP
- Bucket size: 100
- Refill rate: 100/min
- Exceed → 429 with `Retry-After: <seconds until tokens available>`

Outbound: Global semaphore for TfL calls
- Shared across all instances (via Redis/Hazelcast)
- Prevents thundering herd on TfL

---

## Redundancy

| Component | Redundancy Strategy |
|-----------|---------------------|
| Service instances | 3+ replicas, load balanced |
| Cache (Hazelcast/Redis) | Clustered, replicated |
| Background refresh | Leader election, one writer |

### Leader Election for Refresh

Only one instance should refresh from TfL at a time:
- Prevents duplicate requests
- Reduces TfL load
- Leader writes to shared cache
- All instances read from cache

If leader dies, another instance takes over (via Hazelcast/Redis lock).
