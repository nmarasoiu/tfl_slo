# ADR-001: AP over CP Consistency Model

**Status:** Accepted
**Date:** 2026-02-03
**Deciders:** Architecture team

---

## Context

We need to choose a consistency model for the TfL tube status caching service. The CAP theorem tells us that during network partitions, we must choose between:

- **CP (Consistency-Partition tolerance):** During a partition, the system blocks or returns errors to maintain consistency
- **AP (Availability-Partition tolerance):** During a partition, the system continues serving requests, potentially with stale data

### Our Use Case

- Tube status data is **highly cacheable** (status changes every few minutes at most)
- The service is **non-trade-critical** but operationally important for trader productivity
- TfL's own API has ~99.5% availability - we want to **exceed** this
- Users prefer **stale data over no data** - knowing "Victoria line: Good Service (5 min ago)" is better than "Service Unavailable"

---

## Decision

**We choose AP (Availability-Partition tolerance) using CRDTs.**

Specifically:
- Use Pekko Distributed Data with LWW-Register (Last-Writer-Wins)
- During partitions, both sides of the partition serve from local cache
- After partition heals, CRDT merge reconciles (latest timestamp wins)

---

## Consequences

### Positive

1. **Higher availability than TfL itself** - We can serve cached data even when TfL is down
2. **No coordination overhead** - No consensus protocol, no leader election
3. **Simple failure mode** - Worst case during partition: duplicate TfL polling (3 nodes × 2 req/min = 6 req/min, negligible vs TfL's 500 req/min limit)
4. **Natural graceful degradation** - Stale data is clearly better than errors for this use case

### Negative

1. **Eventual consistency** - Two users hitting different nodes during partition may see different data
2. **Clock sensitivity** - LWW depends on timestamps; significant clock skew could cause issues
3. **No strong guarantees** - Can't promise "if you wrote it, you'll read it immediately"

### Mitigations

- **Clock skew:** Modern NTP keeps clocks within milliseconds; our data freshness is measured in seconds/minutes
- **Stale reads:** We expose `X-Data-Stale: true` header so clients know when data is stale
- **Convergence:** Gossip protocol converges in ~100-200ms under normal conditions

---

## Alternatives Considered

### 1. CP with Raft/Paxos

**Rejected because:**
- Adds coordination overhead and failure modes
- During partition, the minority partition would be unavailable
- Overkill for caching ephemeral data
- Doesn't match our availability goal

### 2. Single-node cache (no distribution)

**Rejected because:**
- Single point of failure
- No horizontal scaling
- Node restart means cold cache

### 3. External cache (Redis Cluster)

**Rejected because:**
- Additional operational dependency
- Redis Cluster uses CP model by default
- Doesn't integrate as cleanly with actor model

---

## Interview Answer

> "Why AP over CP?"

**Short answer:** "Stale tube status is better than no tube status. Our users are traders checking their commute - they'd rather see '5 minutes ago: Good Service' than 'Service Unavailable'. The failure mode of AP (duplicate polling, stale reads) is acceptable; the failure mode of CP (unavailability) is not."

**Follow-up - "What about consistency?"**

"We use LWW-Register CRDT, so there's a well-defined merge rule. During normal operation, gossip converges in ~200ms. During partitions, both sides serve their local cache. After healing, the latest write wins. For ephemeral cache data with minute-level freshness requirements, this is fine."

**Follow-up - "What if clocks are skewed?"**

"LWW does depend on timestamps, but modern NTP keeps clocks within milliseconds. Our data freshness is measured in seconds to minutes. A 100ms clock skew affecting a 30-second cache refresh is noise. If we had stricter requirements, we'd use vector clocks or a different CRDT."

---

## References

- [CAP Theorem](https://en.wikipedia.org/wiki/CAP_theorem)
- [CRDTs: Consistency without consensus](https://arxiv.org/abs/1805.06358)
- [Pekko Distributed Data](https://pekko.apache.org/docs/pekko/current/typed/distributed-data.html)
