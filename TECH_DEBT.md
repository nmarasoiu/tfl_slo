# Technical Debt Backlog

Known issues tracked for future resolution. Ordered by severity.

---

## Open

### TD-001: Metrics cardinality explosion from unnormalized paths
**Severity:** Medium
**Location:** `Metrics.java:109-116`

`normalizePath()` replaces known patterns (`/tube/{lineId}/status`) but any unexpected path (e.g., bot scanners hitting `/wp-admin`, `/api/v1/tube/../../etc/passwd`) creates a new unique `(method, path, status)` tuple in the Prometheus registry. Each tuple allocates a `Counter` and `Timer` that are never evicted.

**Risk:** OOM under sustained scanning/fuzzing from diverse paths.

**Fix options:**
1. Catch-all normalization: if path doesn't match a known route, bucket it as `/unknown`
2. Limit cardinality: track a set of known paths, reject others
3. Pekko HTTP already returns 404 for unknown routes before `withTimedMetrics` runs — verify this and skip metrics for unmatched routes

---

### TD-002: TflGateway actor mailbox unbounded under slow TfL
**Severity:** Low
**Location:** `TflGateway.java`

`TflGateway` is a single actor processing messages sequentially. If TfL API is slow (up to 10s response timeout + retries), messages queue in the actor mailbox. Under high throughput, this could lead to significant mailbox growth.

**Risk:** Memory pressure during sustained TfL outages with high freshness-request traffic.

**Fix options:**
1. Use a bounded mailbox with backpressure (Pekko `BoundedMailbox`)
2. Use a stash with size limit
3. Accept: the replicator already coalesces duplicate fetches, so in practice only one TfL fetch is in-flight at a time

---

## Resolved

### TD-003: Pending freshness queue had no timeout ~~(was unbounded)~~
**Resolved in:** `3f37a4d` → next commit
**Location:** `TubeStatusReplicator.java:55`

`LinkedList<PendingFreshnessRequest>` had no drain mechanism. If the HTTP ask-timeout (5s) fired but the TfL fetch was still in-flight, entries would accumulate for dead `ActorRef`s until the fetch eventually completed. If the fetch never completed (e.g., actor restart, message loss), entries leaked permanently.

**Fix:** Added a `DrainStalePendingRequests` timer (10s) that fires after pending requests are queued. Drains all remaining entries with stale data. Timer is cancelled when requests are answered normally.
