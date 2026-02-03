# Date Range Caching: Analysis & Future Options

This document analyzes caching strategies for date range queries, which are currently not cached.

---

## Current State

**Live status** (`/api/v1/tube/status`): Cached via LWW-Register CRDT, replicated across all nodes.

**Date range queries** (`/tube/{line}/status/{from}/to/{to}`): Bypass cache, query TfL directly.

### Why Live Status Works Without Eviction

The live status payload has **constant memory footprint**:
- 11 tube lines × small JSON payload per line
- Single LWW-Register, always overwritten
- No key proliferation, no eviction needed

This makes CRDT replication trivial and memory-safe.

---

## Why Date Ranges Are Different

| Concern | Impact |
|---------|--------|
| **Unbounded key space** | Every unique `(line, from, to)` tuple = separate cache entry |
| **Memory growth** | Without eviction, cache grows indefinitely |
| **Eviction complexity** | Need smart eviction + size bounds + TTL per entry |
| **Low traffic** | Date range queries are rare vs live status (most users want "now") |

---

## Implementation Options

### Option A: Local Cache (Caffeine)

```
Each node maintains its own Caffeine cache for date ranges.
```

**Pros:**
- W-TinyLFU eviction (smart frequency+recency, similar to ZFS ARC)
- Built-in size bounds and TTL
- Simple to implement

**Cons:**
- No distribution across nodes
- Cache miss storms after node failover
- Each node builds cache independently

### Option B: CRDT (LWWMap)

```
Key: "{line}:{fromDate}:{toDate}"
Value: TubeStatus with timestamp
Replicated via Pekko Distributed Data (same or separate Replicator)
```

**Pros:**
- Distributed, conflict-free
- Survives node failures (data on other nodes)
- Consistent with existing architecture

**Cons:**
- No built-in eviction - memory grows until restart
- Would need custom eviction logic (periodic pruning by age/size)
- CRDT overhead for potentially thousands of keys

### Option C: External Cache (Redis/Hazelcast)

```
Centralized cache cluster, nodes query it before TfL.
```

**Pros:**
- Battle-tested eviction policies
- Shared across all nodes
- Rich tooling and monitoring

**Cons:**
- Another infrastructure dependency
- Network partitions create new failure modes:
  - Partition between service and Redis → cache miss storm to TfL
  - Need to handle Redis unavailability gracefully
- Latency overhead for cache lookups

**Note:** In production with significant date range traffic, this might actually be the right choice. Redis Cluster handles partitions reasonably, and the operational burden is well-understood.

### Option D: Hybrid (Local + CRDT for Hot Ranges)

```
1. Local Caffeine for all date ranges (fast, bounded)
2. CRDT replication only for "hot" ranges (accessed N times in M minutes)
3. On node startup, populate local cache from CRDT hot set
```

**Pros:**
- Best of both: local speed + distributed resilience for hot data
- Bounded memory via local eviction
- Failover gets hot data from peers

**Cons:**
- Significant complexity
- Need to define "hot" threshold
- Two cache layers to reason about

---

## Deep Dive: Caffeine's W-TinyLFU

Caffeine uses **Window TinyLFU**, an adaptive policy similar to ZFS ARC:

| Component | Size | Role |
|-----------|------|------|
| Admission window | ~1% | LRU for recency/burst patterns |
| Main cache | ~99% | SLRU with frequency-based admission |
| TinyLFU sketch | - | Compact frequency histogram (Count-Min Sketch) |

**Adaptive hill-climbing:** The window/main ratio adjusts dynamically based on workload. If recency patterns dominate, window grows. If frequency patterns dominate, window shrinks.

This outperforms pure LRU, LFU, and even ARC on mixed workloads.

---

## Could We CRDT-Replicate Eviction Metadata?

Theoretically, to get distributed W-TinyLFU:
- Frequency counts → G-Counter or PN-Counter CRDT
- Recency lists → LWW-Register per entry with access timestamps
- Cache entries → LWWMap

**Challenges:**
- Each node has different access patterns → merge semantics unclear (sum frequencies? max?)
- Count-Min Sketch (TinyLFU's core) has no obvious CRDT-friendly merge
- Hill-climbing adapts to *local* hit rates; global adaptation needs consensus
- Eviction decisions need coordination or accept inconsistency (some nodes evict, others don't)

**Reality:** Distributed caches with smart eviction (Redis Cluster, Memcached) use consistent hashing or centralized coordination, not pure CRDTs. A CRDT-native W-TinyLFU would be research-paper territory.

---

## Recommendation

**For this exercise:** Don't cache date ranges. The traffic pattern doesn't justify the complexity.

**If date range traffic grows in production:**

| Traffic Level | Recommendation |
|---------------|----------------|
| Low (current) | No caching, direct to TfL |
| Medium | Option A (local Caffeine) - simple, bounded |
| High | Option C (Redis) - if infra already exists |
| Critical | Option D (hybrid) - if failover cache hits matter |

**Metrics needed before deciding:**
- Date range request rate (req/min)
- Unique key cardinality (how many distinct ranges?)
- Cache hit ratio potential (are ranges repeated?)
- TfL API quota consumption from date range queries

---

## References

- [Caffeine W-TinyLFU Design](https://github.com/ben-manes/caffeine/wiki/Design)
- [TinyLFU Paper (arXiv)](https://arxiv.org/pdf/1512.00727)
- [Pekko Distributed Data](https://pekko.apache.org/docs/pekko/current/typed/distributed-data.html)
