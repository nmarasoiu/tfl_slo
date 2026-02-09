# Technical Debt Backlog

Known issues tracked for future resolution. Ordered by severity.

---

## Open — Fix Now (interviewer would flag)

### TD-004: Graceful shutdown race condition
**Severity:** Critical
**Location:** `TflApplication.java:170-174`

Shutdown hook calls `serverBinding.unbind()` then `system.terminate()` without awaiting unbind completion. In-flight requests are dropped. Also `system.terminate()` has no timeout — in K8s with a 30s grace period, a slow shutdown causes forceful kill.

**Fix:** Await unbind completion with timeout, then terminate with timeout.

---

### TD-005: Histogram buckets not aligned with SLOs
**Severity:** Critical
**Location:** `Metrics.java:67-72`

`Timer.builder("http_request_duration_seconds").publishPercentileHistogram()` uses Micrometer defaults. The `TflLatencyCriticalBurn` alert in `SLO_DEFINITION.md` queries `le="2"` which may not exist as a bucket boundary. SLO alerting rules can't be built from current metrics.

**Fix:** Add `.serviceLevelObjectives(Duration.ofMillis(100), Duration.ofMillis(500), Duration.ofSeconds(2))` to align with SLO thresholds.

---

### TD-006: Missing response_freshness_seconds histogram
**Severity:** Critical
**Location:** `Metrics.java`

Only a gauge (`data_freshness_seconds`) exists. The freshness SLO "99.9% of responses < 5 min old" requires a histogram to compute percentiles over time. The `TflDataStale` alert queries `response_freshness_seconds` which doesn't exist.

**Fix:** Add a `DistributionSummary` for `response_freshness_seconds` with SLO buckets at 5s, 60s, 300s. Record on every response alongside the gauge.

---

### TD-007: No JVM metrics (heap, GC, threads)
**Severity:** High
**Location:** `TflApplication.java`

No JVM metrics exposed. Can't answer "during that p99 spike, what was heap usage?" — table stakes for Java SRE at a trading firm.

**Fix:** Bind `JvmMemoryMetrics`, `JvmGcMetrics`, `JvmThreadMetrics`, `ProcessorMetrics` to the Metrics registry.

---

### TD-008: No serialVersionUID on Serializable records
**Severity:** High
**Location:** `TubeStatus.java:20,49,86`

`TubeStatus`, `LineStatus`, `Disruption` implement `Serializable` but have no `serialVersionUID`. These are CRDT values serialized across cluster nodes. Schema change during rolling upgrade could cause deserialization failures and cluster split.

**Fix:** Add `@Serial private static final long serialVersionUID = 1L;` to each record.

---

## Open — Backlog

### TD-001: Metrics cardinality explosion from unnormalized paths
**Severity:** Medium
**Location:** `Metrics.java:109-116`

`normalizePath()` replaces known patterns (`/tube/{lineId}/status`) but any unexpected path (e.g., bot scanners hitting `/wp-admin`) creates a new unique `(method, path, status)` tuple in the Prometheus registry, never evicted.

**Fix options:** Catch-all normalization to `/unknown` for unrecognized routes; or verify Pekko HTTP returns 404 before `withTimedMetrics` runs.

---

### TD-002: TflGateway actor mailbox unbounded under slow TfL
**Severity:** Low
**Location:** `TflGateway.java`

Single actor processes messages sequentially. Slow TfL responses (10s timeout + retries) cause mailbox growth. Mitigated by replicator coalescing, but not bounded.

---

### TD-009: OWASP suppressions too broad
**Severity:** Medium
**Location:** `config/owasp/suppressions.xml:31-35`

`CVE-.*` regex suppresses ALL CVEs for Pekko. Could mask real vulnerabilities.

**Fix:** Replace with explicit CVE list or version-scoped regex.

---

### TD-010: No structured JSON logging
**Severity:** Medium
**Location:** `logback.xml`

Text-only log output. Can't be queried in ELK/Splunk without regex parsing. Trading firms need structured fields (trace_id, span_id, latency_ms).

**Fix:** Add `logstash-logback-encoder` dependency, switch to JSON appender.

---

### TD-011: No cluster membership metrics
**Severity:** Medium
**Location:** Not implemented

No visibility into nodes joining/leaving/unreachable. Can't detect split-brain or quorum loss from metrics alone.

**Fix:** Subscribe to Pekko cluster events, publish `cluster_members_total{state}` gauge.

---

### TD-012: No CRDT replication lag metrics
**Severity:** Medium
**Location:** Not implemented

No visibility into how long data takes to propagate across nodes. Can't distinguish freshness SLO failures caused by TfL slowness vs cluster issues.

**Fix:** Track replication latency in `TubeStatusReplicator` on CRDT update responses.

---

### TD-013: Health check endpoints included in SLO metrics
**Severity:** Medium
**Location:** `TubeStatusRoutes.java:108-121`

`/api/health/*` goes through `withTimedMetrics` alongside business endpoints. SLO_DEFINITION.md says to exclude health checks from availability SLI. Prometheus queries would need to filter by path.

**Fix:** Move health routes outside `withTimedMetrics` wrapper, or add an `endpoint` tag to distinguish.

---

### TD-014: Thread.sleep() in tests
**Severity:** Low
**Location:** `TwoNodeReplicationTest.java:182,187,229`, `TubeStatusReplicatorTest.java:150`

Hard-coded sleeps make tests timing-dependent and flaky in slow CI.

**Fix:** Replace with Awaitility polling.

---

### TD-015: ObjectMapper created per Routes instance
**Severity:** Low
**Location:** `TubeStatusRoutes.java:94-96`

ObjectMapper is thread-safe and expensive to create. Should be static or injected.

---

### TD-016: No Cache-Control headers on API responses
**Severity:** Low
**Location:** `TubeStatusRoutes.java`

Status endpoints return custom `X-*` headers but no `Cache-Control`. If response goes through CDN, it may be cached indefinitely.

**Fix:** Add `Cache-Control: public, max-age=5` (aligned with freshness floor).

---

### TD-017: No distributed trace context propagation across cluster
**Severity:** Medium
**Location:** `Tracing.java`

Traces only cover TfL API calls. No trace context carried in Pekko cluster messages. Can't correlate a request across nodes in a single trace.

---

### TD-018: Broad catch(Exception) in test code
**Severity:** Low
**Location:** `TflApiClientIntegrationTest.java:157,176`, `ToxiproxyTflApiTest.java:144,176`

Catches `Exception` instead of specific types, obscuring test intent and hiding bugs.

---

### TD-019: Unused mockito dependency
**Severity:** Low
**Location:** `build.gradle.kts:65`

`mockito-core` is imported but never used. Remove or document intent.

---

## Resolved

### TD-003: Pending freshness queue had no timeout
**Resolved in:** `bb07d90`
**Location:** `TubeStatusReplicator.java:55`

Added `DrainStalePendingRequests` timer (10s) to prevent unbounded queue growth from dead `ActorRef`s.
