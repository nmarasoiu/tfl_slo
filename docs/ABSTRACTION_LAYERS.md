# Abstraction Layers and Technology Choices

This document explains why we use specific technologies at each layer and what alternatives exist.

---

## Architecture Overview

```
┌─────────────────────────────────────────────────────────────────┐
│                         HTTP Layer                               │
│                    (Pekko HTTP + Routes)                         │
├─────────────────────────────────────────────────────────────────┤
│                      Application Layer                           │
│              (Typed Actors: Routes, Gateway, Replicator)         │
├─────────────────────────────────────────────────────────────────┤
│                     Distribution Layer                           │
│         (Pekko Cluster + Distributed Data + Gossip)             │
├─────────────────────────────────────────────────────────────────┤
│                      Resilience Layer                            │
│         (CircuitBreaker, Retry, RateLimiter)                    │
├─────────────────────────────────────────────────────────────────┤
│                      External APIs                               │
│                      (TfL HTTP API)                              │
└─────────────────────────────────────────────────────────────────┘
```

---

## Layer 1: HTTP Framework

### Our Choice: Pekko HTTP

**Why Pekko HTTP:**
- Native integration with Pekko actors (ask pattern, streams)
- DSL for routes is expressive and type-safe
- Built on Pekko Streams (backpressure, non-blocking)
- Already using Pekko - no additional runtime

**Code example:**
```java
// Routes integrate naturally with actors
private Route getStatus() {
    return get(() ->
        onSuccess(
            AskPattern.ask(replicator, GetStatus::new, timeout, scheduler),
            response -> complete(StatusCodes.OK, response, Jackson.marshaller())
        )
    );
}
```

### Alternatives Considered

| Framework | Pros | Cons | Verdict |
|-----------|------|------|---------|
| **Spring WebFlux** | Popular, huge ecosystem, reactive | Different runtime model, adds Spring Boot | Overkill for our use case |
| **Vert.x** | Fast, polyglot, event loop | Separate ecosystem from Pekko | Would mix two async models |
| **Helidon** | Lightweight, Oracle-backed | Less mature, smaller community | Not compelling advantage |
| **Micronaut** | Fast startup, GraalVM-friendly | Different DI model | Good for serverless, not our case |

**Conclusion:** Since we're using Pekko actors, Pekko HTTP is the natural choice. Adding Spring/Vert.x would mean two different async models fighting each other.

---

## Layer 2: Actor Model

### Our Choice: Pekko Typed Actors

**Why Typed Actors:**
- Type-safe message protocols (compile-time checks)
- Behavior-based API (clearer state machines)
- Natural supervision and fault isolation
- Built-in ask pattern for request-response

**Code example:**
```java
// Type-safe command definition
public sealed interface Command permits GetStatus, TriggerRefresh { }

// Behavior with clear state machine
public static Behavior<Command> create() {
    return Behaviors.setup(context ->
        Behaviors.receive(Command.class)
            .onMessage(GetStatus.class, this::onGetStatus)
            .onMessage(TriggerRefresh.class, this::onRefresh)
            .build()
    );
}
```

### Alternatives Considered

| Approach | Pros | Cons | Verdict |
|----------|------|------|---------|
| **Untyped Actors (Classic)** | More flexible, any message type | No compile-time safety, `Any` everywhere | Legacy, migrating away |
| **Plain Threads** | Simple mental model | Manual coordination, race conditions | Doesn't scale, error-prone |
| **Virtual Threads (Loom)** | Lightweight, familiar sync model | No built-in supervision, state isolation | Could work, but loses actor benefits |
| **RxJava/Reactor** | Good for streams | Not great for stateful services | Different problem domain |

**Conclusion:** Typed actors give us state isolation, supervision trees, and location transparency. Perfect for distributed stateful services.

---

## Layer 3: Distribution

### Our Choice: Pekko Distributed Data

**Why Pekko DD:**
- CRDT semantics (conflict-free merge)
- Gossip replication (no single coordinator)
- Integrated with Pekko Cluster (membership, failure detection)
- WriteMajority/ReadLocal consistency controls

**Code example:**
```java
// Write with WriteMajority (wait for majority ack)
replicator.tell(new Replicator.Update<>(
    KEY,
    LWWRegister.create(selfUniqueAddress, status),
    Replicator.writeMajority(timeout),
    current -> LWWRegister.create(selfUniqueAddress, status)
));
```

### Alternatives Considered

| Approach | Pros | Cons | Verdict |
|----------|------|------|---------|
| **Redis Cluster** | Mature, feature-rich | External dependency, CP default | More than we need |
| **Hazelcast** | Java-native, embeddable | Another cluster protocol | Redundant with Pekko Cluster |
| **etcd/Consul** | Good for config/discovery | CP, not designed for frequent updates | Wrong tool |
| **Custom gossip** | Full control | Hard to get right | Reinventing wheel |

**Conclusion:** Pekko DD is the right abstraction - distributed state with CRDT semantics, integrated with our existing cluster.

---

## Layer 4: Cluster Management

### Our Choice: Pekko Cluster

**Why Pekko Cluster:**
- Membership management (join, leave, failure detection)
- Split-brain resolver (keep-majority strategy)
- Cluster sharding (if we needed it)
- Location-transparent actor references

**Configuration:**
```hocon
pekko.cluster {
    seed-nodes = ["pekko://tfl@node1:2551"]
    downing-provider-class = "org.apache.pekko.cluster.sbr.SplitBrainResolverProvider"
    split-brain-resolver.active-strategy = keep-majority
}
```

### Alternatives Considered

| Approach | Pros | Cons | Verdict |
|----------|------|------|---------|
| **Kubernetes Services** | Cloud-native, no app-level clustering | No CRDT, just load balancing | Different layer |
| **Consul** | Service discovery, health checks | Would still need Pekko for actors | Complementary, not replacement |
| **ZooKeeper** | Battle-tested coordination | Heavy, CP model | Overkill for our needs |

**Conclusion:** Pekko Cluster handles what we need. Kubernetes provides infrastructure-level orchestration on top.

---

## Layer 5: Resilience

### Current State: Pekko Built-ins + Manual Rate Limiter

| Pattern | Implementation | Notes |
|---------|---------------|-------|
| Circuit Breaker | `org.apache.pekko.pattern.CircuitBreaker` | Scheduler-integrated, telemetry hooks |
| Retry | `org.apache.pekko.pattern.Patterns.retry()` | Built-in backoff support |
| Rate Limiter | `RateLimiter.java` (manual) | Per-client limiting (no Pekko equivalent) |
| Timeout | Ask timeout | Native |
| Bulkhead | Actor isolation | Natural in actor model |

See [ADR-003](adr/ADR-003-manual-resilience-patterns.md) for decision history.

---

## Layer 6: Observability

### Our Choices

| Aspect | Tool | Why |
|--------|------|-----|
| **Metrics** | Micrometer + Prometheus | Industry standard, Grafana integration |
| **Tracing** | OpenTelemetry (manual) | Vendor-neutral, future-proof |
| **Logging** | Logback + SLF4J | Java standard, structured logging |

### Pekko-Specific Observability

Pekko offers:
- `pekko-diagnostics` (commercial) - Actor tracing
- CircuitBreaker telemetry SPI
- Cluster metrics

We use manual instrumentation instead - simpler, sufficient for our needs.

---

## Summary: Are We Using the Right Abstractions?

| Layer | Abstraction | Right Level? | Notes |
|-------|-------------|--------------|-------|
| HTTP | Pekko HTTP | Yes | Native actor integration |
| Actors | Pekko Typed | Yes | Type-safe, supervision |
| Distribution | Pekko DD | Yes | CRDT semantics |
| Cluster | Pekko Cluster | Yes | Membership, SBR |
| Resilience | Pekko built-ins | Yes | CircuitBreaker, RetrySupport |
| Observability | Micrometer/OTel | Yes | Standard tools |

---

## Interview Questions This Answers

1. **"Why Pekko HTTP over Spring WebFlux?"**
   - Already using Pekko actors; native integration; one async model.

2. **"Why typed actors over classic?"**
   - Compile-time safety; clearer behavior modeling; future direction of Pekko.

3. **"Why not Kubernetes for service discovery?"**
   - Kubernetes handles infrastructure; Pekko Cluster handles application-level coordination and CRDT replication.

4. **"Why use Pekko's built-in CircuitBreaker?"**
   - Scheduler-integrated, telemetry hooks, well-tested. Manual rate limiter remains for per-client limiting (no Pekko equivalent).
