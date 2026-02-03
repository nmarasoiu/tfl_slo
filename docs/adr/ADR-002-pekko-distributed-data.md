# ADR-002: Pekko Distributed Data over Redis/Hazelcast

**Status:** Accepted
**Date:** 2026-02-03
**Deciders:** Architecture team

---

## Context

We need a distributed cache for tube status data. Options considered:

| Option | Type | Consistency | Operational Complexity |
|--------|------|-------------|------------------------|
| **Redis** | External service | CP (default), can be AP | High (separate cluster) |
| **Hazelcast** | Embedded/External | CP (IMap) or AP (ReplicatedMap) | Medium |
| **Pekko Distributed Data** | Embedded | AP (CRDT) | Low (same JVM) |
| **Memcached** | External service | None (no replication) | Medium |

---

## Decision

**We choose Pekko Distributed Data (DD).**

Reasons:
1. We're already using Pekko actors and cluster
2. CRDT semantics match our AP requirements
3. No additional infrastructure to deploy/manage
4. Natural integration with actor messaging

---

## Consequences

### Positive

1. **Zero external dependencies** - Cache is embedded in the application
2. **Consistent with actor model** - DD integrates with Pekko cluster membership
3. **CRDT semantics** - Built-in conflict resolution (LWW-Register)
4. **Gossip replication** - Efficient delta propagation, no single point of failure
5. **Simpler deployment** - No Redis/Hazelcast cluster to manage

### Negative

1. **No persistence** - Data is in-memory; all-node restart loses cache (acceptable for us)
2. **Limited data structures** - Only CRDTs (counters, sets, registers, maps)
3. **Memory-bound** - Cache size limited by JVM heap
4. **Less mature tooling** - Redis has better CLI, monitoring, debugging tools

### Trade-offs Accepted

- We accept losing cache on full cluster restart (TfL data is always re-fetchable)
- We accept in-memory only (our dataset is small: ~11 tube lines × ~1KB each)
- We accept limited data structures (LWW-Register is sufficient for our use case)

---

## When You WOULD Use Redis/Hazelcast Instead

### Use Redis When:

1. **Persistence required** - Data must survive restarts (AOF, RDB snapshots)
2. **Large dataset** - Exceeds single-node memory
3. **Complex data structures** - Lists, sorted sets, streams, pub/sub
4. **Shared cache** - Multiple applications sharing same cache
5. **Existing infrastructure** - Redis already deployed and managed

### Use Hazelcast When:

1. **Java-native preferred** - Cleaner API for Java developers
2. **Near-cache needed** - Local + remote cache tiers
3. **SQL-like queries** - Need to query by predicates
4. **Existing Hazelcast infrastructure** - Already deployed

### Our Situation:

- Small dataset (KB, not GB)
- Ephemeral data (re-fetchable from TfL)
- Single application (not shared)
- No existing cache infrastructure
- Already using Pekko

**Conclusion:** Pekko DD is the right fit.

---

## Alternatives in Detail

### Redis

**Pros:**
- Battle-tested at massive scale
- Rich data structures
- Pub/sub for event broadcasting
- Persistence options (RDB, AOF)
- Excellent tooling (redis-cli, RedisInsight)

**Cons:**
- External service to deploy/manage
- Network hop for every operation
- Default CP model (can lose writes during failover)
- Sentinel/Cluster adds complexity

**Rejected because:** Adds operational complexity we don't need. For a small, ephemeral cache, the overhead isn't justified.

### Hazelcast

**Pros:**
- Embeddable (can run in-process)
- Java-native API
- ReplicatedMap for AP semantics
- Near-cache for local reads

**Cons:**
- Less common in Pekko ecosystems
- IMap is CP by default (needs configuration for AP)
- Adds another cluster protocol alongside Pekko cluster
- License concerns (paid features)

**Rejected because:** Running two cluster protocols (Pekko + Hazelcast) adds complexity. Pekko DD does what we need with one protocol.

### Memcached

**Pros:**
- Simple key-value store
- Very fast
- Multi-tenant

**Cons:**
- No replication (each node is independent)
- No built-in clustering
- No persistence

**Rejected because:** No replication means no distributed cache. Each node would have its own cache, defeating the purpose.

---

## Interview Answer

> "Why not Redis?"

**Short answer:** "We're already using Pekko actors and cluster. Pekko Distributed Data gives us a distributed cache with CRDT semantics, zero external dependencies, and native integration with our actor model. For a small, ephemeral cache (11 tube lines), adding Redis would be operational complexity without benefit."

**Follow-up - "But Redis is proven at scale"**

"Absolutely, and if we were caching millions of items, needed persistence, or had multiple services sharing a cache, Redis would be the right choice. Our dataset is ~11KB and can be re-fetched from TfL anytime. The trade-offs favor embedded over external for this use case."

**Follow-up - "What if you needed to scale?"**

"Pekko DD scales horizontally - add more nodes, gossip replicates automatically. For our dataset, the limit is number of cluster nodes (overhead per node), not data size. If we hit that limit (unlikely), we'd revisit external cache. Build for today's requirements, not hypothetical future ones."

---

## References

- [Pekko Distributed Data](https://pekko.apache.org/docs/pekko/current/typed/distributed-data.html)
- [Redis Cluster](https://redis.io/docs/management/scaling/)
- [Hazelcast ReplicatedMap](https://docs.hazelcast.com/hazelcast/latest/data-structures/replicated-map)
