# TfL Tube Status Service

A resilient caching service for London Underground status, demonstrating SRE patterns.

> **Start here:** [EXECUTIVE_SUMMARY.md](EXECUTIVE_SUMMARY.md) - 5-minute overview of the key decisions.

---

## The Five Big Decisions

| # | Decision | Why |
|---|----------|-----|
| 1 | **AP over CP** | Survives partitions without blocking |
| 2 | **Cache-first** | Users never wait for TfL API |
| 3 | **No leader election** | Gossip naturally deduplicates |
| 4 | **Selective retry** | Don't retry 4xx (client errors) |
| 5 | **SLO-driven alerting** | Page on budget burn, not noise |

---

## Documentation

| Document | Contents |
|----------|----------|
| [EXECUTIVE_SUMMARY.md](EXECUTIVE_SUMMARY.md) | Quick overview of decisions and architecture |
| [DESIGN.md](DESIGN.md) | Architecture, tech choices, trade-offs |
| [SLO_DEFINITION.md](SLO_DEFINITION.md) | SLIs, SLOs, alerting strategy |
| [PRODUCTION_READINESS.md](PRODUCTION_READINESS.md) | Prod checklist, testing strategy |
| [ops/](ops/INDEX.md) | Operations guides |
| [docs/adr/](docs/adr/) | Architecture Decision Records |

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
| `/status` | Return cached data (default 60s freshness) |
| `/status?maxAgeMs=60000` | Return if ≤60s old, else try TfL, else return stale with `X-Data-Stale: true` |
| `/status?maxAgeMs=1000` | Floor enforced at 5s (headers indicate: `X-Freshness-Floor-Applied: true`) |

**Note:** A 5-second freshness floor protects against unreasonable demands that could exhaust TfL API quota.

---

## Cache-First Architecture

**Key property:** Users almost never wait for TfL API responses.

The only time a user request blocks on TfL is during **first cold start of the entire cluster** (no cached data exists anywhere). After that:

| Scenario | User Experience |
|----------|----------------|
| Normal operation | Served from cache instantly |
| Node restart | New node reads from CRDT peers, serves immediately |
| Rolling upgrade | Nodes join cluster, get data from peers via gossip |
| Network partition | Each partition serves from local cache (AP model) |
| TfL API down | Stale data served with `X-Data-Stale: true` header |

**How it works:**
1. **Background poller** refreshes cache every 30s regardless of user traffic
2. **CRDT replication** (Pekko Distributed Data) keeps data synchronized across nodes
3. On startup, nodes check peers before calling TfL - if peer data is fresh enough, no TfL call needed

**Result:** ~99% of requests are served from cache. TfL API quota is consumed only by the background poller (~2 calls/min for the entire cluster in P99), not by user traffic spikes. When one node fetches from TfL, CRDT gossip propagates the data to other nodes within 200ms - before their pollers fire - so they skip their TfL calls.

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
    "dataAsOfUtc": "2026-02-02T14:30:00.123Z",
    "respondedAtUtc": "2026-02-02T14:30:05.456Z"
  }
}
```

All timestamps are ISO-8601 UTC. Compute freshness as `respondedAtUtc - dataAsOfUtc`.

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
