# Observability

Metrics, alerting, logging, and tracing for TfL Tube Status Service.

---

## Metrics

All metrics exposed at `/metrics` endpoint (Prometheus format).

| Metric | Type | Description |
|--------|------|-------------|
| `http_requests_total{method, path, status}` | Counter | Request count |
| `http_request_duration_seconds{method, path}` | Histogram | Latency with percentiles |
| `data_freshness_seconds{node}` | Gauge | Age of cached data |
| `circuit_breaker_state{name}` | Gauge | 0=CLOSED, 1=HALF_OPEN, 2=OPEN |
| `tfl_api_requests_total{status}` | Counter | TfL API call outcomes |
| `cluster_members_total{status}` | Gauge | Cluster membership |

---

## Alerting Strategy

| Alert | Severity | Condition |
|-------|----------|-----------|
| `TflAvailabilityCriticalBurn` | PAGE | Error rate > 14.4x burn rate (5m) |
| `TflLatencyCriticalBurn` | PAGE | p99 latency > 2s (5m) |
| `TflDataStale` | TICKET | Avg freshness > 5 min (10m) |
| `TflCircuitBreakerOpen` | TICKET | Circuit OPEN > 5 min |
| `TflClusterMemberDown` | TICKET | < 3 healthy nodes (5m) |

**Methodology:** Multi-window, multi-burn-rate (Google SRE best practice).

---

## Logging

### Structured JSON Format

```json
{
  "timestamp": "2026-02-03T10:15:30.123Z",
  "level": "INFO",
  "logger": "com.ig.tfl.api.TubeStatusRoutes",
  "message": "Request completed",
  "nodeId": "node-1",
  "requestId": "abc-123",
  "method": "GET",
  "path": "/api/v1/tube/status",
  "status": 200,
  "durationMs": 15
}
```

### Key Events to Monitor

| Event | Level | Indicates |
|-------|-------|-----------|
| `Circuit breaker OPEN` | WARN | TfL API failures |
| `Retries exhausted` | ERROR | Persistent TfL issue |
| `Cluster member unreachable` | WARN | Node failure |

---

## Distributed Tracing

**Implemented:** OpenTelemetry manual instrumentation at TfL API boundary.

**Purpose:** Distinguish our latency vs TfL API latency.

**Export:** Logging by default; OTLP export available for Jaeger/Tempo.

```bash
# To enable OTLP export:
export OTEL_EXPORTER_OTLP_ENDPOINT=http://jaeger:4317
export OTEL_SERVICE_NAME=tfl-status-service
```

---

## Health Checks

| Endpoint | Purpose | K8s Probe |
|----------|---------|-----------|
| `/api/health/live` | Process alive | Liveness |
| `/api/health/ready` | Can serve traffic | Readiness |

**Ready response:**
```json
{
  "status": "ready",
  "circuit": "CLOSED",
  "dataAgeMs": 15230,
  "clusterSize": 3
}
```

---

## Dashboard Panels (Grafana)

| Panel | Query Summary |
|-------|---------------|
| Availability SLO | Success rate over 30d |
| Latency p99 | Histogram percentile |
| Error Budget | Remaining vs burned |
| Traffic by Status | Requests grouped by status code |
| Circuit Breaker | State gauge with value mapping |
| Data Freshness | Freshness gauge over time |
| Cluster Health | Member count gauge |

---

*Full Prometheus alert rules, Alertmanager routing, and Grafana dashboard JSON available on request.*
