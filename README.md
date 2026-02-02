# TfL Tube Status Service

A resilient service providing London Underground status information with SRE best practices.

## Quick Start

```bash
# Build
./gradlew build

# Run single node
./gradlew run

# Run with custom port
TFL_HTTP_PORT=8081 TFL_NODE_ID=node-2 ./gradlew run
```

## API Endpoints

```bash
# Get all tube line statuses
curl http://localhost:8080/api/v1/tube/status

# Get specific line status
curl http://localhost:8080/api/v1/tube/central/status

# Get status with date range
curl http://localhost:8080/api/v1/tube/northern/status/2026-02-10/to/2026-02-12

# Get unplanned disruptions only
curl http://localhost:8080/api/v1/tube/disruptions

# Health checks
curl http://localhost:8080/api/health/live
curl http://localhost:8080/api/health/ready
```

## Response Format

```json
{
  "lines": [
    {
      "id": "central",
      "name": "Central",
      "status": "Good Service",
      "statusSeverityDescription": "Good Service",
      "disruptions": []
    }
  ],
  "meta": {
    "dataAsOf": "2026-02-02T14:30:00Z",
    "fetchedAt": "2026-02-02T14:30:05Z",
    "fetchedBy": "node-1",
    "freshnessMs": 5000,
    "source": "PEER",
    "confidence": "FRESH"
  }
}
```

---

## Architecture

### Design: AP with CRDT Replication

```
┌──────────────────────────────────────────────────────────────┐
│                     Client Requests                          │
└──────────────────────────┬───────────────────────────────────┘
                           │
         ┌─────────────────┼─────────────────┐
         ▼                 ▼                 ▼
   ┌──────────┐      ┌──────────┐      ┌──────────┐
   │  Node 1  │◄────►│  Node 2  │◄────►│  Node 3  │
   │          │ CRDT │          │ CRDT │          │
   │ LWW-Map  │gossip│ LWW-Map  │gossip│ LWW-Map  │
   └────┬─────┘      └────┬─────┘      └────┬─────┘
        │                 │                 │
        └─────────────────┼─────────────────┘
                          │ scatter-gather
                          ▼
                    ┌──────────┐
                    │ TfL API  │
                    └──────────┘
```

### Key Decisions

#### 1. Why AP (Availability-first) over CP?

We're a **private CDN for TfL data**. Our availability must exceed TfL's.

| Approach | During Partition | User Experience |
|----------|------------------|-----------------|
| CP | Minority blocks | Users see error while TfL works |
| **AP** | Both sides serve | Users get data from any reachable node |

The "failure mode" of AP (duplicate TfL polling) is negligible - 6 req/min vs TfL's ~500 req/min limit.

#### 2. Why No Leader Election?

Leader election was to minimize TfL load. But:
- N pollers at 2 req/min each is still negligible
- Leader election requires coordination → availability risk
- CRDT sharing naturally deduplicates most polls

Each node polls independently. CRDT replication means usually only one actually hits TfL.

#### 3. Why Scatter-Gather?

When refreshing data, we query **all sources** (peers + TfL) concurrently:
- If peers have fresh data (< 5s old), use it, skip TfL
- If peers are stale, TfL provides fresh data
- Network topology doesn't matter - best of all reachable sources wins

This treats TfL and peers as equivalent data sources, maximizing availability.

---

## Reliability Patterns

### Circuit Breaker

Protects against TfL API failures:

| Parameter | Value | Rationale |
|-----------|-------|-----------|
| Failure threshold | 5 | Filters transient blips |
| Open duration | 30s | Typical TfL recovery time |
| Half-open trials | 1 | Single probe before closing |

When open: Serve cached data with `confidence: DEGRADED`.

### Retry Logic

Exponential backoff with jitter:

```
Attempt 1: immediate
Attempt 2: 1s ± 250ms
Attempt 3: 2s ± 500ms
Attempt 4: 4s ± 1s (capped at 30s)
```

**Retry on:** 5xx, timeouts, connection errors, 429 (with Retry-After)
**Don't retry on:** 4xx client errors (except 429)

### Rate Limiting

Token bucket per client IP:
- 100 requests/minute
- 429 response when exceeded
- `Retry-After` header included

---

## Configuration

Environment variables:

| Variable | Default | Description |
|----------|---------|-------------|
| `TFL_NODE_ID` | `node-1` | Unique node identifier |
| `TFL_HTTP_PORT` | `8080` | HTTP server port |
| `PEKKO_HOST` | `127.0.0.1` | Cluster hostname |
| `PEKKO_PORT` | `2551` | Cluster port |
| `PEKKO_SEED_NODES` | localhost:2551 | Comma-separated seed nodes |

See `application.conf` for full configuration options.

---

## Multi-Node Setup

```bash
# Terminal 1 - Seed node
TFL_NODE_ID=node-1 TFL_HTTP_PORT=8080 PEKKO_PORT=2551 ./gradlew run

# Terminal 2 - Second node
TFL_NODE_ID=node-2 TFL_HTTP_PORT=8081 PEKKO_PORT=2552 ./gradlew run

# Terminal 3 - Third node
TFL_NODE_ID=node-3 TFL_HTTP_PORT=8082 PEKKO_PORT=2553 ./gradlew run
```

---

## Trade-offs

### What We Prioritized

1. **Availability over consistency** - Serve stale data rather than error
2. **Simplicity over optimization** - No leader election complexity
3. **Transparency over hiding** - Response includes freshness metadata
4. **Resilience over performance** - Circuit breaker may delay first request

### What We Sacrificed

1. **Minimal TfL load** - Each node may poll independently during partitions
2. **Strong consistency** - Brief periods where nodes have different data
3. **Historical data** - No persistent storage (would add for estimation feature)

---

## Production Readiness Gaps

| Gap | Why Not Included | To Add |
|-----|------------------|--------|
| Prometheus metrics | Time constraint | Add micrometer-registry-prometheus |
| TLS between nodes | Assumes trusted network | Configure Pekko SSL |
| Kubernetes manifests | Out of scope | Add Helm chart |
| Distributed tracing | Nice to have | Add OpenTelemetry |
| Persistent storage | Not needed for MVP | Add for historical estimation |

---

## Testing

```bash
# Unit tests
./gradlew test

# Integration tests (requires TfL API access)
./gradlew integrationTest
```

---

## Project Structure

```
src/main/java/com/ig/tfl/
├── TflApplication.java          # Entry point
├── api/
│   └── TubeStatusRoutes.java    # HTTP endpoints
├── client/
│   └── TflApiClient.java        # TfL API client with resilience
├── crdt/
│   ├── TubeStatusReplicator.java # CRDT-replicated status actor
│   └── SelfCluster.java         # Cluster helper
├── model/
│   └── TubeStatus.java          # Domain model
└── resilience/
    ├── CircuitBreaker.java      # Circuit breaker implementation
    ├── RetryPolicy.java         # Retry with backoff
    └── RateLimiter.java         # Token bucket rate limiter
```

---

## SLO Summary

See [SLO_DEFINITION.md](SLO_DEFINITION.md) for full details.

| SLI | SLO Target | Window |
|-----|------------|--------|
| Availability | 99.9% | 30-day |
| Latency (p99) | < 2000ms | 30-day |
| Freshness | 99.9% < 5 min | 30-day |
