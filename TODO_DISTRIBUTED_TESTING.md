# Distributed Testing & Architecture Decisions - TODO

Last updated: 2026-02-03

## Overview
Systematic exploration of distributed systems testing approaches and architectural decision documentation for interview preparation.

**STATUS: ALL TASKS COMPLETE**

---

## Task List

### 1. Create Distributed Testing Options Document
**Status:** COMPLETE
**Output:** `docs/DISTRIBUTED_TESTING.md`

Covered:
- [x] Jepsen (Kyle Kingsbury) - CP-focused, less relevant for AP
- [x] Toxiproxy - network fault injection
- [x] Chaos Monkey (Netflix) - random instance termination
- [x] Gremlin - commercial chaos engineering
- [x] Pekko Multi-Node TestKit - built-in distributed testing
- [x] Testcontainers + Docker Compose - realistic containerized testing
- [x] Comparison table with priorities

---

### 2. Document "Why Not Resilience4j" Decision
**Status:** COMPLETE
**Output:** `docs/adr/ADR-003-manual-resilience-patterns.md`

Covered:
- [x] Pekko has built-in CircuitBreaker and RetrySupport
- [x] Manual implementation = over-engineering for production
- [x] Recommendation: migrate to Pekko built-ins
- [x] Interview answers included

---

### 3. Document "Why Not Redis/Hazelcast" Decision
**Status:** COMPLETE
**Output:** `docs/adr/ADR-002-pekko-distributed-data.md`

Covered:
- [x] Redis/Hazelcast capabilities
- [x] Why Pekko DD fits (small dataset, ephemeral, actor integration)
- [x] When to use Redis/Hazelcast instead
- [x] Interview answers included

---

### 4. Review Abstraction Layers in Pekko Ecosystem
**Status:** COMPLETE
**Output:** `docs/ABSTRACTION_LAYERS.md`

Covered:
- [x] HTTP: Pekko HTTP (native actor integration)
- [x] Actors: Pekko Typed (type-safe, supervision)
- [x] Distribution: Pekko DD (CRDT semantics)
- [x] Cluster: Pekko Cluster (membership, SBR)
- [x] Finding: Manual resilience code should use Pekko built-ins

---

### 5. Explore Existing Multi-Node Tests
**Status:** COMPLETE
**Analysis:**

Current tests (`TwoNodeReplicationTest.java`):
- [x] CRDT propagation within 300ms SLO
- [x] WriteMajority consistency
- [x] Fetch avoidance optimization

Gaps identified:
- [ ] No partition simulation (future work)
- [ ] No node crash tests (future work)
- [ ] No clock skew tests (future work)

---

### 6. Identify Over-Engineering
**Status:** COMPLETE
**Output:** `docs/adr/ADR-003-manual-resilience-patterns.md`

Findings:
- [x] CircuitBreaker.java - Pekko has `org.apache.pekko.pattern.CircuitBreaker`
- [x] RetryPolicy.java - Pekko has `org.apache.pekko.pattern.RetrySupport`
- [x] RateLimiter.java - Keep (no Pekko equivalent for per-client limiting)
- [x] Recommendation: Migrate CB and Retry to Pekko built-ins

---

### 7. Create ADR Index
**Status:** COMPLETE
**Output:** `docs/adr/README.md`

ADRs created:
- [x] ADR-001: AP over CP consistency model
- [x] ADR-002: Pekko Distributed Data over external cache
- [x] ADR-003: Manual resilience patterns vs libraries (Under Review)
- [x] ADR-004: No leader election

---

## Key Interview Questions This Prepares For

| Question | Answer Location |
|----------|-----------------|
| "Why didn't you use Resilience4j?" | ADR-003 |
| "Why not Redis for caching?" | ADR-002 |
| "How do you test distributed systems?" | DISTRIBUTED_TESTING.md |
| "Is this over-engineered for the problem?" | ADR-003, ABSTRACTION_LAYERS.md |
| "Why Pekko over Spring/Micronaut?" | ABSTRACTION_LAYERS.md |
| "How do you handle network partitions?" | ADR-001 |
| "What's your chaos engineering strategy?" | DISTRIBUTED_TESTING.md |
| "Why no leader election?" | ADR-004 |

---

## Files Created This Session

```
docs/
├── DISTRIBUTED_TESTING.md     # Comprehensive testing options
├── ABSTRACTION_LAYERS.md      # Technology choices at each layer
└── adr/
    ├── README.md              # ADR index
    ├── ADR-001-ap-over-cp.md
    ├── ADR-002-pekko-distributed-data.md
    ├── ADR-003-manual-resilience-patterns.md
    └── ADR-004-no-leader-election.md
```

---

## Session Log

### Session 1 (2026-02-03)
- Created task list
- Explored existing multi-node tests (TwoNodeReplicationTest.java)
- Discovered Pekko has built-in CircuitBreaker and RetrySupport
- Created comprehensive DISTRIBUTED_TESTING.md
- Created 4 ADRs with interview answers
- Created ABSTRACTION_LAYERS.md
- **Key finding:** Manual CircuitBreaker/RetryPolicy is over-engineering; should migrate to Pekko built-ins

---

## Future Work

### Testing Enhancements
1. Add partition simulation tests using Pekko TestConductor
2. Add node crash/rejoin tests
3. Add Testcontainers-based integration tests
4. Consider Toxiproxy for TfL API resilience testing

### Code Migration (Optional)
1. Replace CircuitBreaker.java with Pekko's CircuitBreaker
2. Replace RetryPolicy.java with Pekko's RetrySupport
3. Keep RateLimiter.java (no Pekko equivalent)
