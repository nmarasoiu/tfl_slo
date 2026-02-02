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
│   TfL API   │────────►│ CRDT Cache  │
└─────────────┘  write  │ (Pekko DD)  │
                        └─────────────┘
```

### Key Principle

**Always return data from cache. Never block on TfL API for client requests.**

This decouples our availability from TfL's availability:
- TfL down → we still serve (client sees age via `ageMs`)
- TfL slow → our latency unaffected
- TfL rate-limits us → clients unaffected

### Response Schema (Simplified)

Every response includes one immutable timestamp:

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
| `queriedAt` | UTC timestamp when TfL was successfully queried (immutable through CRDT replication) |
| `queriedBy` | Which node performed the query (debugging) |
| `ageMs` | Age of data in milliseconds: `now() - queriedAt` |

### Why This Simplicity?

Previous design had:
- `tflTimestamp` - was identical to fetchedAt (TfL doesn't reliably include timestamps)
- `fetchedAt` - redundant with above
- `source` (TFL/PEER/CACHE) - meaningless with CRDT (all replicas are equivalent)
- `confidence` (FRESH/STALE/DEGRADED) - client can derive from ageMs

**One timestamp is enough.** It never changes through CRDT replication. Clients know exactly when TfL was last successfully queried.

---

## Future Enhancement: Explicit Stale Data Serving

### The Problem

Currently, if data is old (e.g., TfL was unreachable for 2 hours), we still return it. This is dangerous because clients might not notice the staleness.

### Proposed Solution (Not Implemented)

Add explicit parameter to request stale data:

```
GET /api/v1/tube/status                    → 503 if ageMs > threshold
GET /api/v1/tube/status?allowStale=true    → Returns data with warning
GET /api/v1/tube/status?maxAgeMs=300000    → 503 if older than 5 minutes
```

Response when stale:
```json
{
  "lines": [...],
  "meta": {
    "queriedAt": "2026-02-02T12:30:00Z",
    "ageMs": 7200000,
    "warning": "Data is 2 hours old. TfL API unreachable since 12:30 UTC."
  }
}
```

### Complexity/Accuracy Tradeoff

| Approach | Complexity | Accuracy | Risk |
|----------|------------|----------|------|
| Always serve | Low | Variable | Silent staleness |
| Fail if stale | Low | High | Reduced availability |
| Explicit param | Medium | High | Best of both |
| Historical estimation | High | Low | Invented data |

**Current choice: Always serve with transparent ageMs. Client decides.**

**Future: Add explicit `allowStale` parameter for safety-critical clients.**

---

## Circuit Breaker Strategy

### When Circuit Opens

The circuit breaker protects **background refresh**, not client requests:

```
Client ──► CRDT Cache ──► Response (always works)
              ▲
              │
         Background
         Refresher ──► Circuit Breaker ──► TfL API
```

Circuit states:
- **CLOSED**: Normal operation, refresh every 30s
- **OPEN**: Stop hammering TfL, serve cached data, wait 30s
- **HALF_OPEN**: Try one request, if success → CLOSED, if fail → OPEN

### Configuration

| Parameter | Value | Rationale |
|-----------|-------|-----------|
| Failure threshold | 5 | Filters transient blips, catches real outages |
| Recovery timeout | 30s | Matches TfL's typical recovery time |

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

### What We Retry

| Response | Retry? | Rationale |
|----------|--------|-----------|
| 5xx | Yes | Server error, might recover |
| 429 | Yes | Rate limited, backoff helps |
| Timeout | Yes | Network blip |
| 4xx (except 429) | No | Client error, won't fix itself |

---

## Rate Limiting

### Token Bucket per Client

- Bucket size: 100 tokens
- Refill rate: 100/minute
- Exceed → 429 with `Retry-After` header

---

## CRDT Replication

Using Pekko Distributed Data with LWW-Register:
- Each node can poll TfL independently
- CRDT replication naturally shares data
- LWW ensures freshest data wins
- No leader election needed (simpler, more available)

```
Node-1 ──► TfL ──► LWW-Register ◄──► LWW-Register ◄── Node-2 ──► TfL
                        │                   │
                        └───────────────────┘
                          (gossip replication)
```
