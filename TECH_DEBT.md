# Technical Debt Backlog

Known issues tracked for future resolution. Ordered by severity.

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

### TD-008: No serialVersionUID on Serializable records
**Resolved in:** `e2e2a50`

Added `@Serial serialVersionUID = 1L` to TubeStatus, LineStatus, Disruption. Prevents deserialization failures during rolling upgrades.

### TD-007: No JVM metrics
**Resolved in:** `68406fd`

Bound JvmMemoryMetrics, JvmGcMetrics, JvmThreadMetrics, ProcessorMetrics to Prometheus registry.

### TD-005 + TD-006: Histogram buckets + freshness histogram
**Resolved in:** `7810f40`

Duration histogram now has SLO-aligned buckets (100ms/500ms/2s). Added `response_freshness_seconds` histogram with buckets at 5s/60s/300s.

### TD-004: Graceful shutdown race condition
**Resolved in:** `d966e8e`

Shutdown hook now awaits unbind (10s) then terminate (15s). Fits K8s 30s grace period.

### TD-003: Pending freshness queue had no timeout
**Resolved in:** `bb07d90`

Added `DrainStalePendingRequests` timer (10s) to prevent unbounded queue growth from dead `ActorRef`s.
