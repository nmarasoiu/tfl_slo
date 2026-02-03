# ADR-004: No Leader Election

**Status:** Accepted
**Date:** 2026-02-03
**Deciders:** Architecture team

---

## Context

In a distributed caching system, a common pattern is to elect a leader that:
- Is the only node that polls the upstream API (TfL)
- Distributes updates to followers
- Handles coordination tasks

Should we implement leader election?

---

## Decision

**No. We do not use leader election.**

Each node independently:
- Polls TfL API on its own schedule
- Writes to CRDT with its timestamp
- Gossip propagates to other nodes
- LWW-Register merges (latest timestamp wins)

---

## Consequences

### Positive

1. **Simpler architecture** - No consensus protocol, no split-brain handling
2. **Higher availability** - No leader = no leader failure mode
3. **Faster recovery** - No re-election delay after failures
4. **Natural load distribution** - All nodes share the polling work

### Negative

1. **Duplicate polling** - Multiple nodes may poll TfL simultaneously
2. **Slightly higher TfL load** - 3 nodes × 2 req/min = 6 req/min (vs 2 req/min with leader)
3. **Potential timestamp conflicts** - Two nodes polling at same instant

### Why The Negatives Are Acceptable

**Duplicate polling:**
- TfL rate limit: 500 req/min
- Our worst case: 6 req/min (3 nodes, no coordination)
- Headroom: 494 req/min unused
- Cost: negligible

**Timestamp conflicts:**
- Two nodes poll TfL at t=0
- Both write to CRDT with t=0
- LWW picks one arbitrarily (both have same data anyway)
- No user impact

---

## Why Leader Election Would Hurt Us

### 1. Availability Risk

Leader election requires:
- Heartbeats to detect leader failure
- Consensus to elect new leader
- Follower timeout before re-election

**Failure scenario:**
```
t=0:   Leader dies
t=5s:  Followers detect missing heartbeat
t=10s: Election starts
t=15s: New leader elected
t=16s: New leader polls TfL

Result: 16 seconds of no fresh data during leader transition
```

**Our approach:**
```
t=0:   Any node dies
t=0:   Other nodes continue serving from their cache
t=30s: Surviving nodes poll TfL on their normal schedule

Result: No interruption, just one fewer node polling
```

### 2. Complexity

Leader election adds:
- Raft/Paxos/ZAB implementation or dependency
- Split-brain resolution
- Quorum requirements
- Lease renewal
- Fencing tokens

All for saving ~4 req/min to TfL.

### 3. Partition Behavior

**With leader election:**
- Network partition splits cluster
- Minority partition has no leader
- Minority partition stops serving fresh data (or serves stale)
- Requires human intervention or split-brain resolver

**Without leader:**
- Network partition splits cluster
- Both partitions continue polling TfL independently
- Both partitions serve fresh data
- Partition heals, CRDT merges, done

---

## Alternatives Considered

### Pekko Cluster Singleton

Pekko provides `ClusterSingleton` for exactly this use case - a single actor across the cluster.

**Rejected because:**
- Singleton = single point of failure
- Handover delay during failures (~10-30s)
- Doesn't improve our situation

### Custom Leader Election

Implement election using Pekko Distributed Data (e.g., CRDTCounter for voting).

**Rejected because:**
- Reinventing Raft poorly
- Adds complexity without benefit
- Election edge cases are hard to get right

### Consistent Hashing (One Node Per Task)

Hash "tfl-poll" to a node, only that node polls.

**Rejected because:**
- That node fails = polling stops until rehash
- Similar problems to leader election
- Slightly better but still adds failure modes

---

## References

- [Pekko Cluster Singleton](https://pekko.apache.org/docs/pekko/current/typed/cluster-singleton.html)
- [Raft Consensus](https://raft.github.io/)
- [The Trouble with Distributed Systems - Kleppmann](https://dataintensive.net/)
