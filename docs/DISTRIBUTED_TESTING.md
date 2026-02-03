# Distributed Testing

Testing strategy for the CRDT-based distributed cache.

---

## Current Test Stack

| Layer | Tool | Purpose |
|-------|------|---------|
| Unit | JUnit 5 + AssertJ | Pure logic (rate limiter, retry decisions) |
| Actor | Pekko ActorTestKit | Single-node actor behavior |
| Integration | WireMock | TfL API mocking |
| Multi-Node | Pekko TestKit + Cluster | CRDT replication |
| Async | Awaitility | Polling assertions |

---

## What We Test

From `TwoNodeReplicationTest.java`:

1. **CRDT Propagation** - Write on node1 → verify node2 receives within 300ms SLO
2. **WriteMajority Consistency** - Verify WriteMajority semantics in 2-node cluster
3. **Fetch Avoidance** - Node2 uses peer data instead of calling TfL

---

## Gaps to Address

| Gap | Why It Matters |
|-----|----------------|
| No partition simulation | Should verify both sides serve during split |
| No node crash tests | Verify surviving nodes continue serving |
| No clock skew tests | LWW-Register depends on timestamps |

### Adding Partition Tests

Pekko provides `TestConductor` for failure injection:

```java
// Isolate node2 from node1
testConductor.blackhole(node1, node2, Direction.Both);

// Both should continue serving (AP guarantee)
assertThat(queryNode1()).isNotNull();
assertThat(queryNode2()).isNotNull();

// Heal and verify convergence
testConductor.passThrough(node1, node2, Direction.Both);
```

---

## Tools We Don't Use (and Why)

| Tool | Why Not |
|------|---------|
| Jepsen | For CP systems verifying linearizability; we're AP with explicit eventual consistency |
| Chaos Monkey | Requires AWS/Spinnaker production infrastructure |
| Gremlin | Commercial; overkill for our scale |

For TfL API resilience testing, Toxiproxy could be useful but Pekko TestKit + WireMock covers most scenarios.

---

## References

- [Pekko Multi-Node Testing](https://pekko.apache.org/docs/pekko/current/multi-node-testing.html)
- [Testcontainers](https://testcontainers.com)
