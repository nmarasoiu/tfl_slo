# Observability

Metrics, dashboards, and alerting for TfL Tube Status Service.

---

## 1. Metrics

### Metrics to Expose

All metrics exposed at `/metrics` endpoint (Prometheus format).

#### HTTP Metrics

```prometheus
# Request count by endpoint, method, status
http_requests_total{method="GET", path="/api/v1/tube/status", status="200"} 12345

# Request duration histogram
http_request_duration_seconds_bucket{method="GET", path="/api/v1/tube/status", le="0.1"} 11000
http_request_duration_seconds_bucket{method="GET", path="/api/v1/tube/status", le="0.5"} 12000
http_request_duration_seconds_bucket{method="GET", path="/api/v1/tube/status", le="2.0"} 12300
http_request_duration_seconds_count{method="GET", path="/api/v1/tube/status"} 12345
http_request_duration_seconds_sum{method="GET", path="/api/v1/tube/status"} 1543.21
```

#### Business Metrics

```prometheus
# Data freshness (gauge, updated on each response)
data_freshness_seconds{node="node-1"} 15.3

# Cache state
cache_entries_total{type="tube_status"} 1
cache_last_update_timestamp_seconds{type="tube_status"} 1706889600

# TfL API calls
tfl_api_requests_total{status="success"} 500
tfl_api_requests_total{status="failure"} 3
tfl_api_request_duration_seconds_bucket{le="1.0"} 480
tfl_api_request_duration_seconds_bucket{le="5.0"} 498
```

#### Resilience Metrics

```prometheus
# Circuit breaker state (0=CLOSED, 1=HALF_OPEN, 2=OPEN)
circuit_breaker_state{name="tfl-api"} 0

# Circuit breaker transitions
circuit_breaker_transitions_total{name="tfl-api", from="CLOSED", to="OPEN"} 2

# Retry attempts
retry_attempts_total{operation="tfl_fetch", attempt="1"} 500
retry_attempts_total{operation="tfl_fetch", attempt="2"} 10
retry_attempts_total{operation="tfl_fetch", attempt="3"} 2

# Rate limiting
rate_limit_requests_total{result="allowed"} 10000
rate_limit_requests_total{result="rejected"} 15
```

#### Cluster Metrics

```prometheus
# Cluster membership
cluster_members_total{status="up"} 3
cluster_members_total{status="unreachable"} 0

# CRDT replication
crdt_updates_total{source="local"} 100
crdt_updates_total{source="peer"} 200
crdt_replication_lag_seconds{peer="node-2"} 0.05
```

### Adding Metrics (Implementation Guide)

```java
// Add micrometer-registry-prometheus dependency
// build.gradle.kts:
// implementation("io.micrometer:micrometer-registry-prometheus:1.12.0")

// In TubeStatusRoutes.java:
private final MeterRegistry registry;

private void recordRequest(String path, String method, int status, long durationMs) {
    Timer.builder("http_request_duration_seconds")
        .tag("path", path)
        .tag("method", method)
        .tag("status", String.valueOf(status))
        .register(registry)
        .record(Duration.ofMillis(durationMs));
}
```

---

## 2. Dashboards

### Grafana Dashboard: Service Overview

**Dashboard ID:** `tfl-status-overview`

#### Row 1: SLO Status

| Panel | Type | Query |
|-------|------|-------|
| Availability (30d) | Stat | `sum(rate(http_requests_total{status=~"2.."}[30d])) / sum(rate(http_requests_total[30d])) * 100` |
| Latency p99 (30d) | Stat | `histogram_quantile(0.99, sum(rate(http_request_duration_seconds_bucket[30d])) by (le))` |
| Freshness SLO | Stat | `sum(rate(data_freshness_ok_total[30d])) / sum(rate(http_requests_total[30d])) * 100` |
| Error Budget Remaining | Gauge | `1 - (sum(rate(http_requests_total{status=~"5.."}[30d])) / sum(rate(http_requests_total[30d]))) / 0.001` |

#### Row 2: Traffic

| Panel | Type | Query |
|-------|------|-------|
| Requests/sec | Graph | `sum(rate(http_requests_total[1m]))` |
| By Status Code | Graph (stacked) | `sum(rate(http_requests_total[1m])) by (status)` |
| By Endpoint | Graph | `sum(rate(http_requests_total[1m])) by (path)` |

#### Row 3: Latency

| Panel | Type | Query |
|-------|------|-------|
| Latency Heatmap | Heatmap | `sum(rate(http_request_duration_seconds_bucket[1m])) by (le)` |
| p50/p95/p99 | Graph | `histogram_quantile(0.5/0.95/0.99, ...)` |
| By Endpoint | Table | Percentiles grouped by path |

#### Row 4: TfL API Health

| Panel | Type | Query |
|-------|------|-------|
| Circuit Breaker State | Stat (value mapping) | `circuit_breaker_state{name="tfl-api"}` |
| TfL Request Rate | Graph | `sum(rate(tfl_api_requests_total[1m])) by (status)` |
| TfL Latency | Graph | `histogram_quantile(0.99, sum(rate(tfl_api_request_duration_seconds_bucket[1m])) by (le))` |
| Data Freshness | Graph | `data_freshness_seconds` |

#### Row 5: Cluster Health

| Panel | Type | Query |
|-------|------|-------|
| Cluster Members | Stat | `cluster_members_total{status="up"}` |
| CRDT Updates/sec | Graph | `sum(rate(crdt_updates_total[1m])) by (source)` |
| Replication Lag | Graph | `max(crdt_replication_lag_seconds)` |

### Grafana Dashboard JSON

```json
{
  "dashboard": {
    "title": "TfL Status Service",
    "uid": "tfl-status-overview",
    "tags": ["tfl", "sre"],
    "timezone": "browser",
    "refresh": "30s",
    "panels": [
      {
        "title": "Availability (30d SLO: 99.9%)",
        "type": "stat",
        "gridPos": {"x": 0, "y": 0, "w": 6, "h": 4},
        "targets": [{
          "expr": "sum(rate(http_requests_total{status=~\"2..\"}[30d])) / sum(rate(http_requests_total[30d])) * 100",
          "legendFormat": "Availability %"
        }],
        "fieldConfig": {
          "defaults": {
            "thresholds": {
              "steps": [
                {"color": "red", "value": null},
                {"color": "yellow", "value": 99.5},
                {"color": "green", "value": 99.9}
              ]
            },
            "unit": "percent"
          }
        }
      }
    ]
  }
}
```

Full dashboard JSON available at: `ops/grafana/tfl-status-dashboard.json` (if implemented)

---

## 3. Alerting

### Prometheus Alert Rules

```yaml
# ops/prometheus/alerts.yaml
groups:
  - name: tfl-status-slo
    interval: 30s
    rules:
      # === AVAILABILITY ===

      # Page: 14.4x burn rate over 5 minutes (exhausts 2-day budget)
      - alert: TflAvailabilityCriticalBurn
        expr: |
          (
            sum(rate(http_requests_total{status=~"5.."}[5m]))
            / sum(rate(http_requests_total[5m]))
          ) > (14.4 * 0.001)
        for: 2m
        labels:
          severity: page
        annotations:
          summary: "TfL Status Service availability critical burn rate"
          description: "Error rate {{ $value | humanizePercentage }} exceeds 14.4x burn rate. 2-day budget at risk."
          runbook: "https://wiki/runbooks/tfl-availability"

      # Ticket: 3x burn rate over 1 hour
      - alert: TflAvailabilitySlowBurn
        expr: |
          (
            sum(rate(http_requests_total{status=~"5.."}[1h]))
            / sum(rate(http_requests_total[1h]))
          ) > (3 * 0.001)
        for: 30m
        labels:
          severity: ticket
        annotations:
          summary: "TfL Status Service availability slow burn"
          description: "Error rate elevated for 30+ minutes. Investigate before budget exhaustion."

      # === LATENCY ===

      - alert: TflLatencyCriticalBurn
        expr: |
          (
            histogram_quantile(0.99, sum(rate(http_request_duration_seconds_bucket[5m])) by (le))
          ) > 2
        for: 5m
        labels:
          severity: page
        annotations:
          summary: "TfL Status Service p99 latency exceeds 2s"
          description: "p99 latency is {{ $value | humanizeDuration }}. SLO breach."

      # === FRESHNESS ===

      - alert: TflDataStale
        expr: avg(data_freshness_seconds) > 300
        for: 10m
        labels:
          severity: ticket
        annotations:
          summary: "TfL data is stale (>5 minutes)"
          description: "Average data freshness is {{ $value | humanizeDuration }}. Check TfL API and CRDT replication."

      # === CIRCUIT BREAKER ===

      - alert: TflCircuitBreakerOpen
        expr: circuit_breaker_state{name="tfl-api"} == 2
        for: 5m
        labels:
          severity: ticket
        annotations:
          summary: "TfL API circuit breaker is OPEN"
          description: "Circuit breaker has been open for 5+ minutes. TfL API may be experiencing issues."

      # === CLUSTER ===

      - alert: TflClusterMemberDown
        expr: cluster_members_total{status="up"} < 3
        for: 5m
        labels:
          severity: ticket
        annotations:
          summary: "TfL cluster has fewer than 3 healthy members"
          description: "Only {{ $value }} nodes are healthy. Check for node failures."

      # === RATE LIMITING ===

      - alert: TflHighRateLimitRejections
        expr: |
          sum(rate(rate_limit_requests_total{result="rejected"}[5m]))
          / sum(rate(rate_limit_requests_total[5m])) > 0.01
        for: 5m
        labels:
          severity: info
        annotations:
          summary: "High rate of rate-limited requests"
          description: "{{ $value | humanizePercentage }} of requests are being rate-limited."
```

### Alert Routing (Alertmanager)

```yaml
# ops/alertmanager/config.yaml
route:
  receiver: default
  group_by: [alertname, severity]
  group_wait: 30s
  group_interval: 5m
  repeat_interval: 4h
  routes:
    - match:
        severity: page
      receiver: pagerduty
      continue: true
    - match:
        severity: ticket
      receiver: slack-tickets
    - match:
        severity: info
      receiver: slack-info

receivers:
  - name: default
    slack_configs:
      - channel: '#tfl-alerts'

  - name: pagerduty
    pagerduty_configs:
      - service_key: '<PAGERDUTY_KEY>'
        severity: critical

  - name: slack-tickets
    slack_configs:
      - channel: '#tfl-alerts'
        title: '[TICKET] {{ .GroupLabels.alertname }}'

  - name: slack-info
    slack_configs:
      - channel: '#tfl-alerts-info'
```

---

## 4. Logging

### Structured Logging Format

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
  "durationMs": 15,
  "clientIp": "10.0.0.50",
  "dataAgeMs": 5230
}
```

### Log Levels by Component

| Component | Default Level | Debug Level |
|-----------|---------------|-------------|
| TubeStatusRoutes | INFO | DEBUG (shows all requests) |
| TflApiClient | INFO | DEBUG (shows response bodies) |
| TubeStatusReplicator | INFO | DEBUG (shows CRDT operations) |
| CircuitBreaker | WARN | INFO (shows all transitions) |
| Pekko Cluster | WARNING | DEBUG (verbose cluster events) |

### Key Log Events to Monitor

| Event | Level | Indicates |
|-------|-------|-----------|
| `Circuit breaker OPEN` | WARN | TfL API failures |
| `Circuit breaker CLOSED` | INFO | Recovery |
| `TfL fetch failed` | WARN | Transient TfL issue |
| `Retries exhausted` | ERROR | Persistent TfL issue |
| `CRDT update from peer` | DEBUG | Replication working |
| `Cluster member unreachable` | WARN | Node failure |

### Logback Configuration (Production)

```xml
<!-- src/main/resources/logback-prod.xml -->
<configuration>
  <appender name="JSON" class="ch.qos.logback.core.ConsoleAppender">
    <encoder class="net.logstash.logback.encoder.LogstashEncoder">
      <includeMdcKeyName>nodeId</includeMdcKeyName>
      <includeMdcKeyName>requestId</includeMdcKeyName>
    </encoder>
  </appender>

  <root level="INFO">
    <appender-ref ref="JSON"/>
  </root>

  <logger name="org.apache.pekko" level="WARNING"/>
  <logger name="com.ig.tfl" level="INFO"/>
</configuration>
```

---

## 5. Distributed Tracing (Optional)

For this service, distributed tracing adds limited value because:
- Single service (no microservice calls)
- Simple request flow (cache → maybe TfL → response)
- Low latency (tracing overhead not justified)

If needed for compliance or consistency with other services:

```java
// OpenTelemetry auto-instrumentation
// Add javaagent: -javaagent:opentelemetry-javaagent.jar

// Or manual instrumentation:
Span span = tracer.spanBuilder("tfl-fetch")
    .setAttribute("tfl.endpoint", "/Line/Mode/tube/Status")
    .startSpan();
try (Scope scope = span.makeCurrent()) {
    return tflClient.fetchAllLines();
} finally {
    span.end();
}
```

**Recommendation:** Skip tracing for this service. Metrics + logs are sufficient.

---

## 6. Health Checks

### Endpoints

| Endpoint | Purpose | Success | Failure |
|----------|---------|---------|---------|
| `/api/health/live` | Process alive | 200 always | - |
| `/api/health/ready` | Can serve traffic | 200 (has data) | 503 (warming up) |

### Kubernetes Probes

```yaml
livenessProbe:
  httpGet:
    path: /api/health/live
    port: 8080
  initialDelaySeconds: 10
  periodSeconds: 10
  failureThreshold: 3

readinessProbe:
  httpGet:
    path: /api/health/ready
    port: 8080
  initialDelaySeconds: 5
  periodSeconds: 5
  failureThreshold: 3
```

### Health Check Response

```json
// GET /api/health/ready
{
  "status": "ready",
  "circuit": "CLOSED",
  "dataAgeMs": 15230,
  "clusterSize": 3
}
```
