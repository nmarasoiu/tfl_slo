# TfL Tube Status Service

A resilient service providing London Underground status information with SRE best practices.

## Documentation

| Document | Contents |
|----------|----------|
| [DESIGN.md](DESIGN.md) | Architecture, tech choices, trade-offs |
| [SLO_DEFINITION.md](SLO_DEFINITION.md) | SLIs, SLOs, alerting strategy |
| [PRODUCTION_READINESS.md](PRODUCTION_READINESS.md) | Prod checklist, testing strategy |

---

## Quick Start

```bash
# Build
./gradlew build

# Run
./gradlew run

# Run tests
./gradlew test
```

### Multi-Node Setup

```bash
# Terminal 1 - Seed node
TFL_NODE_ID=node-1 TFL_HTTP_PORT=8080 PEKKO_PORT=2551 ./gradlew run

# Terminal 2
TFL_NODE_ID=node-2 TFL_HTTP_PORT=8081 PEKKO_PORT=2552 ./gradlew run

# Terminal 3
TFL_NODE_ID=node-3 TFL_HTTP_PORT=8082 PEKKO_PORT=2553 ./gradlew run
```

---

## API Endpoints

```bash
# Get all tube line statuses (cached)
curl http://localhost:8080/api/v1/tube/status

# With freshness requirement (max 60 seconds old)
curl http://localhost:8080/api/v1/tube/status?maxAgeMs=60000

# Get specific line status
curl http://localhost:8080/api/v1/tube/central/status

# Get status with date range (queries TfL directly)
curl http://localhost:8080/api/v1/tube/northern/status/2026-02-10/to/2026-02-12

# Get unplanned disruptions only
curl http://localhost:8080/api/v1/tube/disruptions

# Health checks
curl http://localhost:8080/api/health/live
curl http://localhost:8080/api/health/ready
```

### Freshness Parameter

| Request | Behavior |
|---------|----------|
| `/status` | Return cached data (any age) |
| `/status?maxAgeMs=60000` | Return if ≤60s old, else try TfL, else return stale with `X-Data-Stale: true` |
| `/status?maxAgeMs=0` | Always attempt TfL fetch (still returns stale on failure) |

---

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
    "queriedAt": "2026-02-02T14:30:00Z",
    "queriedBy": "node-1",
    "ageMs": 5000
  }
}
```

---

## Configuration

| Variable | Default | Description |
|----------|---------|-------------|
| `TFL_NODE_ID` | `node-1` | Unique node identifier |
| `TFL_HTTP_PORT` | `8080` | HTTP server port |
| `PEKKO_HOST` | `127.0.0.1` | Cluster hostname |
| `PEKKO_PORT` | `2551` | Cluster port |
| `PEKKO_SEED_NODES` | localhost:2551 | Comma-separated seed nodes |

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
    ├── CircuitBreaker.java      # Circuit breaker (5 failures → open, 30s recovery)
    ├── RetryPolicy.java         # Exponential backoff with jitter
    └── RateLimiter.java         # Token bucket (100 req/min per IP)
```

---

## SLO Summary

| SLI | Target | Window |
|-----|--------|--------|
| Availability | 99.9% | 30-day |
| Latency (p99) | < 2000ms | 30-day |
| Freshness | 99.9% < 5 min | 30-day |

See [SLO_DEFINITION.md](SLO_DEFINITION.md) for full details including alerting strategy.
