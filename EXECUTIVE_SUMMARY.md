# Executive Summary

A resilient caching service for TfL (Transport for London) tube status, demonstrating SRE patterns for a trading platform context.

---

## The Five Big Decisions

| # | Decision | Why It Matters |
|---|----------|----------------|
| 1 | **AP over CP** (CRDT, not Raft) | Survives network partitions without blocking users. Cache availability > perfect consistency. |
| 2 | **Cache-first architecture** | ~99% of requests served from cache. TfL outage = stale data, not errors. |
| 3 | **No leader election** | Simpler, more resilient. Gossip naturally deduplicates TfL calls. |
| 4 | **Selective retry** | Retry 5xx/timeouts, don't retry 4xx. Shows understanding, not cargo cult. |
| 5 | **SLO-driven alerting** | Multi-burn-rate methodology (Google SRE). Page on budget exhaustion, not noise. |

---

## What This Demonstrates

### Resilience Patterns (Implemented)
- Circuit breaker (Pekko built-in)
- Exponential backoff + jitter
- CRDT replication (LWW-Register)
- Graceful degradation (serve stale when TfL down)

### Observability (Implemented)
- Prometheus metrics at `/metrics`
- OpenTelemetry tracing at TfL API boundary
- Structured JSON logging
- Health check endpoints

### Production Readiness
- 70%+ test coverage enforced
- OWASP dependency scanning
- Kubernetes-ready (liveness/readiness probes)
- Docker-ready (Jib build)

---

## Architecture at a Glance

```
User Request
     │
     ▼
┌─────────────────────────────────────────────────────────┐
│                     SERVICE                              │
│  ┌──────────────────────────────────────────┐           │
│  │ Local CRDT Cache (instant)              │           │
│  │        ↕ gossip                         │           │
│  │ Peer CRDT Cache (~200ms)               │           │
│  └──────────────────────────────────────────┘           │
│                              │                         │
│                              │ only if stale           │
│                              ▼                         │
│                     ┌──────────────────┐               │
│                     │ TfL API          │               │
│                     │ + Circuit Breaker │               │
│                     │ + Retry/Backoff  │               │
│                     └──────────────────┘               │
└─────────────────────────────────────────────────────────┘
```

**Key property:** Users almost never wait for TfL. Cache-first, always.

---

## SLO Targets

| SLI | Target | Window |
|-----|--------|--------|
| Availability | 99.9% | 30-day |
| Latency (p99) | < 2s | 30-day |
| Freshness | 99.9% < 5 min | 30-day |

---

## Tech Stack

| Component | Choice | Why |
|-----------|--------|-----|
| Language | Java 21 | Type safety, mature ecosystem |
| Actor Framework | Apache Pekko | Akka successor (Apache license), battle-tested |
| Clustering | Pekko Distributed Data | CRDT replication, no external dependencies |
| HTTP | Pekko HTTP | Non-blocking, actor-integrated |
| Metrics | Micrometer + Prometheus | Cloud-native standard |
| Tracing | OpenTelemetry | Vendor-neutral |

---

## What's NOT Here (Intentionally)

| Feature | Why Omitted |
|---------|-------------|
| Kubernetes manifests | Out of scope for exercise |
| Terraform | Infrastructure managed separately |
| Database | Stateless cache, no persistence needed |
| OAuth/Auth | Internal service, use network controls |

---

## Quick Start

```bash
./gradlew build
./gradlew run

# Test it
curl http://localhost:8080/api/v1/tube/status
curl http://localhost:8080/api/health/ready
curl http://localhost:8080/metrics
```

---

## Documentation Map

| To understand... | Read... |
|------------------|---------|
| Architecture & trade-offs | [DESIGN.md](DESIGN.md) |
| SLOs & alerting | [SLO_DEFINITION.md](SLO_DEFINITION.md) |
| Production checklist | [PRODUCTION_READINESS.md](PRODUCTION_READINESS.md) |
| Individual decisions | [docs/adr/](docs/adr/) |
| Operations | [ops/INDEX.md](ops/INDEX.md) |
