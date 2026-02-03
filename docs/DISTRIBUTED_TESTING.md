# Distributed Systems Testing Options

This document covers testing strategies for distributed systems, with a focus on what's relevant for an AP (Availability-Partition tolerant) CRDT-based system like this TfL Status Service.

---

## Table of Contents

1. [Testing Spectrum Overview](#testing-spectrum-overview)
2. [Jepsen](#jepsen)
3. [Toxiproxy](#toxiproxy)
4. [Chaos Monkey & Chaos Engineering](#chaos-monkey--chaos-engineering)
5. [Gremlin](#gremlin)
6. [Pekko Multi-Node TestKit](#pekko-multi-node-testkit)
7. [Testcontainers](#testcontainers)
8. [What We Use and Why](#what-we-use-and-why)
9. [Comparison Matrix](#comparison-matrix)

---

## Testing Spectrum Overview

Distributed systems testing exists on a spectrum:

```
Unit Tests → Integration Tests → Multi-Node Tests → Chaos Engineering → Production Testing
   (fast)                                                                    (realistic)
```

| Level | Speed | Realism | Use Case |
|-------|-------|---------|----------|
| Unit | ms | Low | Algorithm correctness (circuit breaker states) |
| Integration | seconds | Medium | Component interaction (actor + CRDT) |
| Multi-Node | seconds-minutes | High | Cluster behavior (gossip, partitions) |
| Chaos | minutes-hours | Very High | Production resilience (real failures) |
| Production | continuous | Real | Observability-driven (SLO monitoring) |

---

## Jepsen

**Creator:** Kyle Kingsbury (aphyr)
**Website:** https://jepsen.io
**Purpose:** Formal verification of distributed database consistency claims

### What It Does

Jepsen is a Clojure-based testing framework that:
- Sets up a cluster of nodes (typically 5)
- Runs concurrent operations against the cluster
- Injects network partitions, clock skew, process crashes
- Verifies linearizability (or other consistency models) of operation history

### Why It's Less Relevant for AP Systems

Jepsen is primarily designed to **catch CP system bugs** - systems that claim strong consistency but violate it under partitions. It looks for:
- Linearizability violations
- Lost writes
- Stale reads that shouldn't be stale

**For our AP system:**
- We *explicitly allow* stale reads during partitions
- We use LWW-Register which has well-defined merge semantics
- There's no linearizability claim to verify

### When Jepsen WOULD Be Useful

- If we claimed "no data loss" (Jepsen could verify writes aren't lost)
- If we had complex CRDT merge logic (verify merge correctness)
- If we wanted to verify cluster membership consistency

### Verdict for This Project

**Low priority.** Jepsen's value proposition is exposing hidden consistency bugs. Our consistency model is explicit: "eventually consistent, last-writer-wins, stale reads allowed." No hidden claims to verify.

---

## Toxiproxy

**Creator:** Shopify
**Website:** https://github.com/Shopify/toxiproxy
**Purpose:** TCP proxy that simulates network conditions

### What It Does

Toxiproxy sits between your client and server, allowing you to:
- **Latency:** Add delay to packets (constant or jitter)
- **Bandwidth:** Limit throughput
- **Slow close:** Delay connection close
- **Timeout:** Stop data from getting through
- **Reset peer:** Simulate connection reset
- **Slicer:** Slice data into smaller bits with delay

### How It Works

```
[Test Client] → [Toxiproxy :8474] → [Real Service :8080]
                     ↓
              Apply toxics (latency, drop, etc.)
```

### Example: Simulating TfL API Timeout

```java
// In test setup
ToxiproxyClient toxiproxy = new ToxiproxyClient("localhost", 8474);
Proxy proxy = toxiproxy.createProxy("tfl-api", "localhost:9999", "api.tfl.gov.uk:443");

// Add 5 second latency to simulate slow TfL
proxy.toxics().latency("slow-tfl", ToxicDirection.DOWNSTREAM, 5000);

// Run test - circuit breaker should open
runTflFetch();

// Verify circuit opened
assertThat(circuitBreaker.getState()).isEqualTo(OPEN);
```

### Pros

- Language-agnostic (TCP-level)
- Easy to set up (Docker or binary)
- Fine-grained control over network conditions
- Good for CI pipelines

### Cons

- Doesn't simulate process crashes
- TCP-level only (no application-level failures)
- Extra infrastructure to manage

### Verdict for This Project

**Medium priority.** Useful for testing TfL API resilience (timeouts, slow responses). Less useful for intra-cluster testing since Pekko's TestKit handles that.

---

## Chaos Monkey & Chaos Engineering

**Creator:** Netflix
**Website:** https://netflix.github.io/chaosmonkey/
**Purpose:** Randomly terminate production instances to verify resilience

### Philosophy

> "The best way to avoid failure is to fail constantly."

Chaos Engineering principles:
1. Define steady state (SLOs/SLIs)
2. Hypothesize that steady state continues during experiments
3. Introduce real-world events (crashes, network issues)
4. Observe and compare to baseline

### Chaos Monkey Specifics

Chaos Monkey is narrow in scope:
- **Only** terminates EC2/VM instances
- Runs during business hours
- Targets one instance at a time per group
- Designed for AWS/Spinnaker integration

### Broader Chaos Engineering Tools (Netflix Suite)

| Tool | What It Does |
|------|--------------|
| Chaos Monkey | Terminates instances |
| Chaos Kong | Simulates region failure |
| Latency Monkey | Adds artificial latency |
| Chaos Gorilla | Simulates AZ failure |

### When to Use

- Production environments with redundancy
- After basic resilience testing passes
- When you need confidence in auto-healing
- During game days / chaos experiments

### Verdict for This Project

**Low priority for testing, high priority conceptually.** The *philosophy* of chaos engineering is important - our system should handle instance termination gracefully. But Chaos Monkey itself requires production AWS/Spinnaker infrastructure we don't have.

---

## Gremlin

**Creator:** Gremlin Inc.
**Website:** https://gremlin.com
**Purpose:** Commercial chaos engineering platform

### What It Offers

**Resource attacks:**
- CPU stress
- Memory pressure
- Disk fill
- IO stress

**Network attacks:**
- **Blackhole:** Drop all traffic (simulates network partition)
- Latency injection
- Packet loss
- DNS failures
- Certificate expiry

**State attacks:**
- Process kill
- Time travel (clock skew)
- Shutdown

### Blackhole Attack Details

The "blackhole" attack is essentially:
```bash
iptables -A INPUT -s <target> -j DROP
iptables -A OUTPUT -d <target> -j DROP
```

More sophisticated than just network segmentation:
- Can target specific ports/protocols
- Can be asymmetric (A can't reach B, but B can reach A)
- Can affect specific traffic patterns

### Pros

- Comprehensive attack library
- Good UI and reporting
- Team collaboration features
- Scheduled experiments

### Cons

- Commercial ($$)
- Agent installation required
- Overkill for small teams

### Verdict for This Project

**Low priority.** Gremlin is excellent but expensive. For our scale, DIY chaos with scripts/Toxiproxy is sufficient.

---

## Pekko Multi-Node TestKit

**Documentation:** https://pekko.apache.org/docs/pekko/current/multi-node-testing.html
**Purpose:** Test distributed Pekko applications across multiple JVMs

### What It Does

The Multi-Node TestKit allows you to:
- Spawn multiple ActorSystems in separate JVMs
- Simulate cluster formation and membership changes
- Test CRDT convergence across nodes
- Inject controlled failures at the actor level

### Our Current Usage

From `TwoNodeReplicationTest.java`:

```java
// Two separate ActorTestKits simulate two cluster nodes
node1 = ActorTestKit.create("two-node-test", node1Config);
node2 = ActorTestKit.create("two-node-test", node2Config);

// Form cluster
Cluster.get(node1.system()).manager().tell(Join.create(...));

// Wait for cluster to stabilize
await().until(() -> {
    return cluster1.selfMember().status() == MemberStatus.up()
        && memberCount >= 2;
});
```

### Test Cases We Cover

1. **CRDT Propagation** (`dataWrittenOnNode1_propagatesToNode2_withinSloTimeframe`)
   - Write on node1 → verify node2 receives within 300ms SLO

2. **WriteMajority Consistency** (`writeMajority_ensuresDataReachesMajorityBeforeReturning`)
   - Verify WriteMajority semantics work in 2-node cluster

3. **Fetch Avoidance** (`node2_doesNotFetchFromTfl_whenPeerDataIsFresh`)
   - Verify optimization: node2 uses peer data instead of calling TfL

### What We Don't Currently Test

- **Network partitions:** Simulating split-brain scenarios
- **Node failures:** Clean vs unclean shutdown behavior
- **Clock skew:** LWW-Register with drifted clocks
- **Slow nodes:** Gossip with one slow/unresponsive node

### How to Add Partition Testing

Pekko provides `TestConductor` for coordinated failure injection:

```java
// Isolate node2 from node1 (simulate partition)
testConductor.blackhole(node1, node2, Direction.Both);

// Perform operations during partition
// ...

// Heal partition
testConductor.passThrough(node1, node2, Direction.Both);

// Verify convergence after healing
```

### Pros

- Native to the framework we use
- No external infrastructure
- Tests actual actor/cluster behavior
- Fast (JVM-level, not real network)

### Cons

- Doesn't test real network issues
- Same JVM quirks don't appear
- Limited to Pekko-specific scenarios

### Verdict for This Project

**High priority.** This is our primary tool for distributed testing. We should expand coverage to include:
- Partition simulation with `TestConductor`
- Node failure and rejoin scenarios
- Split-brain resolver behavior

---

## Testcontainers

**Website:** https://testcontainers.com
**Purpose:** Spin up Docker containers for integration tests

### What It Does

Testcontainers provides:
- Programmatic Docker container management
- Pre-built modules (databases, message queues, etc.)
- Network creation for multi-container tests
- Automatic cleanup

### Example: 3-Node Cluster Test

```java
@Container
static Network network = Network.newNetwork();

@Container
static GenericContainer<?> node1 = new GenericContainer<>("tfl-service:test")
    .withNetwork(network)
    .withNetworkAliases("node1")
    .withEnv("SEED_NODES", "node1:2551");

@Container
static GenericContainer<?> node2 = new GenericContainer<>("tfl-service:test")
    .withNetwork(network)
    .withNetworkAliases("node2")
    .withEnv("SEED_NODES", "node1:2551");

@Test
void clusterFormsCorrectly() {
    // Wait for cluster to form
    await().until(() -> getClusterMembers(node1) >= 2);

    // Test CRDT propagation via HTTP endpoints
    postStatus(node1, testData);
    await().until(() -> getStatus(node2).equals(testData));
}
```

### Combining with Toxiproxy

Testcontainers + Toxiproxy = realistic network testing:

```java
@Container
static ToxiproxyContainer toxiproxy = new ToxiproxyContainer()
    .withNetwork(network);

@Test
void survivesNetworkPartition() {
    ToxiproxyClient client = new ToxiproxyClient(toxiproxy.getHost(), toxiproxy.getControlPort());
    Proxy proxy = client.createProxy("node1-to-node2", "0.0.0.0:8666", "node2:2551");

    // Simulate partition
    proxy.toxics().bandwidth("cut", ToxicDirection.DOWNSTREAM, 0);

    // Both nodes should continue serving (AP guarantee)
    assertThat(getStatus(node1)).isNotNull();
    assertThat(getStatus(node2)).isNotNull();

    // Heal and verify convergence
    proxy.toxics().get("cut").remove();
    await().until(() -> getStatus(node1).equals(getStatus(node2)));
}
```

### Pros

- Real containers (closer to production)
- Docker Compose support for complex topologies
- Good CI/CD integration
- Tests full application stack

### Cons

- Slower than in-memory tests
- Requires Docker
- More complex setup
- Resource-intensive

### Verdict for This Project

**Medium priority.** Great for pre-production confidence. Should add after Pekko TestKit coverage is complete.

---

## What We Use and Why

### Current Test Stack

| Layer | Tool | Purpose |
|-------|------|---------|
| Unit | JUnit 5 + AssertJ | Pure logic (CircuitBreaker, RetryPolicy) |
| Actor | Pekko ActorTestKit | Single-node actor behavior |
| Integration | WireMock | TfL API mocking |
| Multi-Node | Pekko TestKit + Cluster | CRDT replication |
| Async | Awaitility | Polling assertions |

### Recommended Additions

**Short-term (high value, low effort):**
1. Expand Pekko multi-node tests with `TestConductor` for partition simulation
2. Add clock skew tests for LWW-Register edge cases

**Medium-term (medium value, medium effort):**
3. Testcontainers for full Docker-based cluster tests
4. Toxiproxy integration for TfL API resilience testing

**Long-term (nice to have):**
5. Chaos engineering game days in staging
6. Production observability-driven testing (synthetic requests + SLO monitoring)

---

## Comparison Matrix

| Tool | Scope | Realism | Setup Effort | Best For |
|------|-------|---------|--------------|----------|
| **Jepsen** | Cluster-wide | Very High | High | CP systems, consistency proofs |
| **Toxiproxy** | Network | Medium | Low | API resilience, timeout testing |
| **Chaos Monkey** | Instance | High | Medium | Production instance failure |
| **Gremlin** | Full stack | Very High | Low (paid) | Enterprise chaos engineering |
| **Pekko TestKit** | Actor/Cluster | Medium | Low | Actor behavior, CRDT tests |
| **Testcontainers** | Full stack | High | Medium | Realistic integration tests |

### Our Priorities

```
1. Pekko Multi-Node TestKit  ████████████ (primary)
2. Testcontainers + Docker   ████████     (secondary)
3. Toxiproxy                 ██████       (TfL API testing)
4. Chaos Engineering         ████         (future/staging)
5. Jepsen                    ██           (low priority for AP)
6. Gremlin                   ██           (overkill for our scale)
```

---

## Testing Gaps to Address

### Current Gaps

1. **No partition simulation** - We test CRDT propagation but not partition tolerance
2. **No node failure tests** - What happens when a node crashes mid-gossip?
3. **No clock skew tests** - LWW-Register depends on timestamps
4. **No slow node tests** - Gossip behavior with one unresponsive node
5. **No full-stack Docker tests** - Container-based realism

### Proposed Test Additions

```java
// 1. Partition test
@Test
void bothNodesContinueServingDuringPartition() {
    testConductor.blackhole(node1, node2, Direction.Both);
    // Both should serve stale data (AP guarantee)
    assertThat(queryNode1()).isNotNull();
    assertThat(queryNode2()).isNotNull();
}

// 2. Node crash test
@Test
void survivesNodeCrash() {
    node2.system().terminate();
    // Node1 should continue serving
    assertThat(queryNode1()).isNotNull();
}

// 3. Split-brain resolver test
@Test
void splitBrainResolverDownsMinority() {
    // Create 3-node cluster, partition 1 from 2+3
    // SBR should down the minority (node1)
}
```

---

## References

- [Jepsen Analyses](https://jepsen.io/analyses)
- [Toxiproxy GitHub](https://github.com/Shopify/toxiproxy)
- [Netflix Chaos Engineering](https://netflix.github.io/chaosmonkey/)
- [Gremlin Documentation](https://www.gremlin.com/docs/)
- [Pekko Multi-Node Testing](https://pekko.apache.org/docs/pekko/current/multi-node-testing.html)
- [Testcontainers](https://testcontainers.com)
- [Designing Data-Intensive Applications](https://dataintensive.net/) - Chapter 8 on distributed testing
