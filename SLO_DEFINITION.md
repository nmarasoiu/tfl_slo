# SLO Definition: TfL Tube Status Service

## Service Overview

This service provides London Underground status information to a trading platform. Traders rely on this data to plan commutes and trading floor presence. The service acts as a resilient cache/CDN for TfL's public API.

---

## 1. Service Level Indicators (SLIs)

### SLI 1: Availability

**Definition:** The proportion of valid requests that receive a non-error response.

```
Availability = (requests returning 2xx or 429) / (total requests) × 100%
```

**Measurement:**
- Counter: `http_requests_total{status=~"2..|429"}`
- Counter: `http_requests_total`
- Exclude: Health check endpoints (`/api/health/*`)

**Why this SLI:**
- Directly measures "can users get data?"
- 429 (rate limited) is intentional, not an error
- 5xx responses indicate service failure
- Aligns with user perception of "working"

---

### SLI 2: Latency (Request Duration)

**Definition:** The proportion of requests served faster than a threshold.

```
Latency SLI = (requests with duration < threshold) / (total requests) × 100%
```

**Thresholds:**
- p50: 100ms (typical cache hit)
- p95: 500ms (includes TfL fetch)
- p99: 2000ms (includes retries)

**Measurement:**
- Histogram: `http_request_duration_seconds`
- Use DDSketch or similar for accurate tail percentiles

**Why this SLI:**
- Traders check status quickly; slow responses degrade UX
- p99 captures worst-case without noise from extreme outliers
- Multiple thresholds give visibility into distribution shape

---

### SLI 3: Data Freshness

**Definition:** The proportion of responses where data is fresh (< threshold age).

```
Freshness SLI = (responses where freshnessMs < threshold) / (total responses) × 100%
```

**Thresholds:**
- Fresh: < 60 seconds (1 minute)
- Acceptable: < 300 seconds (5 minutes)

**Measurement:**
- Histogram: `response_freshness_seconds`
- Derived from `meta.freshnessMs` in responses

**Why this SLI:**
- Unique to caching services
- Stale data might show "Good Service" when there's actually disruption
- Distinguishes between "service up but useless" vs "service up and useful"

---

### SLI 4: Error Rate by Type

**Definition:** Breakdown of error types for debugging/trending.

**Categories:**
- `upstream_error`: TfL API failures (5xx, timeout)
- `circuit_open`: Circuit breaker preventing TfL calls
- `rate_limited`: Client exceeded rate limit (429)
- `client_error`: Bad request from client (4xx except 429)

**Measurement:**
- Counter: `errors_total{type="..."}`

**Why this SLI:**
- Operational visibility, not for SLO target
- Helps distinguish "our problem" vs "TfL's problem" vs "client's problem"

---

## 2. Service Level Objectives (SLOs)

### SLO 1: Availability

| Target | Window | Justification |
|--------|--------|---------------|
| **99.9%** | 30-day rolling | Trading platform criticality |

**Error Budget:** 0.1% of 30 days = **43.2 minutes/month** of downtime

**Why 99.9% (not higher):**
- TfL API itself is ~99.5% available (based on public reports)
- Our caching architecture decouples us from TfL, enabling higher target
- 99.99% would be 4.3 min/month - too tight for a non-critical service
- Tube status is important but not trade-execution critical

**Why 99.9% (not lower):**
- Trading platform dependency justifies high bar
- Caching architecture makes this achievable
- Lower target would signal "unreliable service"

---

### SLO 2: Latency

| Percentile | Target | Window | Justification |
|------------|--------|--------|---------------|
| p50 | < 100ms | 30-day | Cache hit path |
| p95 | < 500ms | 30-day | Most requests fast |
| **p99** | **< 2000ms** | 30-day | Even with retries |

**Primary SLO:** p99 < 2000ms

**Why these thresholds:**
- 100ms p50: Most requests should be cache hits (local Caffeine)
- 500ms p95: Allows for CRDT reads from peers
- 2000ms p99: Allows for one TfL fetch + one retry

**Why p99 (not p99.9):**
- p99 captures user-impacting tail without measurement noise
- p99.9 on low-traffic service is statistically noisy
- Aligns with Google SRE best practices for this traffic level

---

### SLO 3: Data Freshness

| Threshold | Target | Window | Justification |
|-----------|--------|--------|---------------|
| < 60s | 99% | 30-day | Normal operation |
| **< 300s** | **99.9%** | 30-day | Degraded but usable |

**Primary SLO:** 99.9% of responses have data < 5 minutes old

**Why 5 minutes:**
- Tube status changes rarely (every few minutes during disruption)
- 5-minute-old data is still actionable for commute planning
- Allows for TfL outages up to 5 min without breaching SLO
- Longer staleness would mislead users about current conditions

---

## 3. Alerting Strategy

### Burn Rate Methodology

We use **multi-window, multi-burn-rate alerts** per Google SRE practices.

**Error Budget Consumption Rate:**
```
Burn Rate = (actual error rate) / (SLO error rate)
```

For 99.9% SLO (0.1% error budget):
- Burn rate 1.0 = consuming budget at exactly sustainable pace
- Burn rate 14.4 = would exhaust 30-day budget in 2 days
- Burn rate 6.0 = would exhaust budget in 5 days

---

### Alert Definitions

#### Page (Wake Someone Up)

**Availability - Critical Burn**
```yaml
alert: TflAvailabilityCriticalBurn
expr: |
  (
    sum(rate(http_requests_total{status=~"5.."}[5m]))
    / sum(rate(http_requests_total[5m]))
  ) > (14.4 * 0.001)  # 14.4x burn rate
for: 2m
severity: page
```

**Rationale:** 14.4x burn rate over 5 minutes = exhausts 2-day budget. Requires immediate attention.

**Latency - Critical Burn**
```yaml
alert: TflLatencyCriticalBurn
expr: |
  (
    sum(rate(http_request_duration_seconds_bucket{le="2"}[5m]))
    / sum(rate(http_request_duration_seconds_count[5m]))
  ) < 0.99
for: 5m
severity: page
```

**Rationale:** >1% of requests exceeding p99 threshold for 5 minutes.

---

#### Ticket (Next Business Day)

**Availability - Slow Burn**
```yaml
alert: TflAvailabilitySlowBurn
expr: |
  (
    sum(rate(http_requests_total{status=~"5.."}[1h]))
    / sum(rate(http_requests_total[1h]))
  ) > (3 * 0.001)  # 3x burn rate
for: 30m
severity: ticket
```

**Rationale:** 3x burn rate over 1 hour = will exhaust budget in 10 days. Needs investigation but not urgent.

**Freshness - Degraded**
```yaml
alert: TflDataStale
expr: |
  avg(response_freshness_seconds) > 300
for: 10m
severity: ticket
```

**Rationale:** Average freshness > 5 min suggests TfL issues or CRDT replication problems.

**Circuit Breaker Open**
```yaml
alert: TflCircuitOpen
expr: circuit_breaker_state{name="tfl-api"} == 2  # OPEN state
for: 5m
severity: ticket
```

**Rationale:** Circuit open for 5 min means TfL is likely down. Not our fault, but worth tracking.

---

#### Informational (Dashboard Only)

**Rate Limiting Active**
```yaml
alert: TflRateLimitingActive
expr: sum(rate(http_requests_total{status="429"}[5m])) > 0.1
for: 1m
severity: info
```

**Rationale:** Clients being rate-limited. Not an incident, but worth visibility.

---

### Alert Routing

| Severity | Destination | Response Time |
|----------|-------------|---------------|
| page | PagerDuty → On-call | < 5 min |
| ticket | Jira/Slack | Next business day |
| info | Dashboard only | No response needed |

---

## 4. SLO Review Cadence

| Review | Frequency | Focus |
|--------|-----------|-------|
| SLO Status | Weekly | Are we meeting targets? |
| Error Budget | Monthly | How much budget remains? |
| SLO Targets | Quarterly | Are targets appropriate? |

### Error Budget Policy

**When budget is exhausted:**
1. Freeze non-critical changes
2. Focus engineering time on reliability
3. Post-mortem on budget consumption
4. Resume normal work when budget regenerates

**When budget is healthy (>50% remaining):**
1. Normal feature development velocity
2. Consider tightening SLOs if consistently over-performing

---

## 5. Dependency Considerations

### TfL API Dependency

Our SLOs assume TfL API availability of ~99.5%. If TfL degrades:
- Our availability SLO may still be met (cache serving)
- Our freshness SLO will be impacted
- Alert on `TflCircuitOpen` to track upstream issues

### Cascade Failure Protection

If this service fails, downstream impact to trading platform is:
- Traders can't check tube status
- They default to assuming normal service or checking TfL directly
- **Not trade-blocking** - this is informational, not transactional

This context justifies 99.9% (high but not extreme) availability target.

---

## Appendix: Metric Instrumentation

```java
// Prometheus metrics (example)
Counter requestsTotal = Counter.build()
    .name("http_requests_total")
    .labelNames("method", "path", "status")
    .register();

Histogram requestDuration = Histogram.build()
    .name("http_request_duration_seconds")
    .labelNames("method", "path")
    .buckets(0.01, 0.05, 0.1, 0.25, 0.5, 1, 2, 5)
    .register();

Histogram responseFreshness = Histogram.build()
    .name("response_freshness_seconds")
    .buckets(5, 15, 30, 60, 120, 300, 600)
    .register();

Gauge circuitState = Gauge.build()
    .name("circuit_breaker_state")
    .labelNames("name")
    .help("0=CLOSED, 1=HALF_OPEN, 2=OPEN")
    .register();
```
