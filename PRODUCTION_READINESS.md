# Production Readiness

This document covers testing strategy and what's needed to take this service to production.

---

## 1. Testing Strategy

### Philosophy: Detroit Style (Classical TDD)

- **Real collaborators** over mocks where possible
- **Mock at system boundaries** only (TfL API, network)
- **Test behavior**, not implementation details
- **High ROI** - focus on tests that catch real bugs

**What we DON'T do:** Mock internal classes, verify method call counts, test private methods.

### Test Pyramid

```
                    ┌─────────────────┐
                    │  Multi-Node /   │  Few, expensive
                    │  Chaos Tests    │  High confidence
                    ├─────────────────┤
                    │  Integration    │  Real HTTP, WireMock
                    │  Tests          │  Real actors
                    ├─────────────────┤
                    │  Unit Tests     │  Pure functions
                    │                 │  State machines
                    └─────────────────┘
                         Many, fast
```

### What to Test at Each Layer

| Layer | Component | Approach |
|-------|-----------|----------|
| Unit | CircuitBreaker | Real object, controllable clock |
| Unit | RetryPolicy | Pure function |
| Unit | RateLimiter | Real object, controllable clock |
| Integration | TflApiClient | Real HTTP + WireMock |
| Integration | HTTP routes | Full Pekko HTTP stack |
| Multi-node | CRDT convergence | Pekko Multi-Node TestKit |
| Chaos | Partition tolerance | Toxiproxy |

### Example: Circuit Breaker Unit Test

```java
@Test
void opensAfterFiveConsecutiveFailures() {
    var cb = new CircuitBreaker("test", 5, Duration.ofSeconds(30));

    for (int i = 0; i < 5; i++) {
        cb.onFailure(new RuntimeException("fail"));
    }

    assertThat(cb.getState()).isEqualTo(State.OPEN);
}
```

### Example: WireMock Integration Test

```java
@Test
void retriesOn503ThenSucceeds() {
    wireMock.stubFor(get("/Line/Mode/tube/Status")
        .inScenario("retry")
        .whenScenarioStateIs(STARTED)
        .willReturn(serverError())
        .willSetStateTo("attempt-2"));

    wireMock.stubFor(get("/Line/Mode/tube/Status")
        .inScenario("retry")
        .whenScenarioStateIs("attempt-2")
        .willReturn(okJson(SAMPLE_TFL_RESPONSE)));

    TubeStatus status = client.fetchAllLinesAsync()
        .toCompletableFuture().join();

    assertThat(status.lines()).isNotEmpty();
    wireMock.verify(2, getRequestedFor(urlPathEqualTo("/Line/Mode/tube/Status")));
}
```

### What We DON'T Test

| Skip | Why |
|------|-----|
| Pekko internals | Trust the framework |
| JSON parsing edge cases | Jackson is well-tested |
| CRDT merge algorithm | Pekko Distributed Data is well-tested |

**Test our code, not the framework.**

### Coverage Goals

| Layer | Target | Rationale |
|-------|--------|-----------|
| Unit | 90%+ | Fast, cheap, catch logic bugs |
| Integration | Key paths | Real HTTP, real actors |
| Multi-node | Happy + partition | Validate distributed behavior |

---

## 2. Observability

### Metrics (Prometheus) - TODO

```java
// Request metrics
Counter.builder("http_requests_total")
    .tag("method", method)
    .tag("path", path)
    .tag("status", status)
    .register(registry);

Timer.builder("http_request_duration_seconds")
    .publishPercentiles(0.5, 0.95, 0.99)
    .register(registry);

// Business metrics
Gauge.builder("data_freshness_seconds", this::getCurrentFreshness)
    .register(registry);

Gauge.builder("circuit_breaker_state", () -> circuitBreaker.getState().ordinal())
    .tag("name", "tfl-api")
    .register(registry);
```

### Logging (Structured JSON) - Partial

```xml
<!-- logback.xml for production -->
<encoder class="net.logstash.logback.encoder.LogstashEncoder">
    <includeMdcKeyName>nodeId</includeMdcKeyName>
    <includeMdcKeyName>requestId</includeMdcKeyName>
</encoder>
```

Key log events: circuit breaker state changes, TfL fetch success/failure, CRDT updates, rate limiting.

### Distributed Tracing (OpenTelemetry) - TODO

Trace correlation: Request ID propagated through all components.

---

## 3. Deployment

### Containerization

```dockerfile
FROM eclipse-temurin:21-jre-alpine
COPY build/libs/tfl-slo-*.jar app.jar
EXPOSE 8080 2551
ENTRYPOINT ["java", "-jar", "app.jar"]
```

### Kubernetes

```yaml
apiVersion: apps/v1
kind: Deployment
metadata:
  name: tfl-status
spec:
  replicas: 3
  template:
    spec:
      containers:
      - name: tfl-status
        image: tfl-status:latest
        ports:
        - containerPort: 8080  # HTTP
        - containerPort: 2551  # Pekko cluster
        env:
        - name: TFL_NODE_ID
          valueFrom:
            fieldRef:
              fieldPath: metadata.name
        - name: PEKKO_HOST
          valueFrom:
            fieldRef:
              fieldPath: status.podIP
        readinessProbe:
          httpGet:
            path: /api/health/ready
            port: 8080
        livenessProbe:
          httpGet:
            path: /api/health/live
            port: 8080
        resources:
          requests:
            memory: "256Mi"
            cpu: "100m"
          limits:
            memory: "512Mi"
            cpu: "500m"
```

### Pekko Cluster Bootstrap (Kubernetes)

```hocon
pekko.management {
  cluster.bootstrap {
    contact-point-discovery {
      discovery-method = kubernetes-api
      service-name = "tfl-status"
    }
  }
}
```

---

## 4. Security

| Connection | Current | Production |
|------------|---------|------------|
| Client → Service | HTTP | HTTPS (terminate at ingress) |
| Service → TfL | HTTPS | HTTPS (already) |
| Node ↔ Node | Unencrypted | Pekko Artery TLS |

### Input Validation

- Line ID: whitelist valid tube line IDs
- Date range: validate format, reasonable range
- Already have: rate limiting per IP

---

## 5. Resilience Hardening

### Timeouts (Currently Implemented)

| Timeout | Value | Location |
|---------|-------|----------|
| TfL HTTP connect | 5s | TflApiClient |
| TfL HTTP request | 10s | TflApiClient |
| Circuit breaker open | 30s | CircuitBreaker |
| CRDT read | 5s | TubeStatusReplicator |

### Additional Resilience (TODO)

| Pattern | Purpose |
|---------|---------|
| Bulkhead | Isolate TfL calls from serving |
| Graceful shutdown | Drain connections (Pekko coordinated shutdown) |
| Health-based routing | K8s readiness probe |

### Graceful Degradation Matrix

| Failure | Behavior |
|---------|----------|
| TfL down | Serve cache + alert |
| All peers unreachable | Serve local cache + alert |
| High memory | Backpressure + GC tuning |
| High CPU | Autoscaling |

---

## 6. Runbooks (TODO)

| Runbook | Trigger | Actions |
|---------|---------|---------|
| TfL API Down | Circuit breaker OPEN > 5min | Check TfL status page, wait |
| High Latency | p99 > 2s | Check TfL latency, check node health, scale up |
| Data Stale | Freshness > 10min | Check circuit breaker, check CRDT replication |
| Node Crash Loop | Pod restart > 3 | Check logs, resources, cluster health |

---

## 7. Performance

### Benchmarking Target

```bash
wrk -t4 -c100 -d60s http://localhost:8080/api/v1/tube/status
```

Target: 1000 req/s at p99 < 100ms (cache hits)

### JVM Tuning

```bash
java -XX:+UseG1GC \
     -XX:MaxGCPauseMillis=100 \
     -Xms256m -Xmx512m \
     -jar app.jar
```

### Resource Estimates

| Load | Nodes | Memory | CPU |
|------|-------|--------|-----|
| 100 req/s | 2 | 256MB each | 0.1 core |
| 1000 req/s | 3 | 512MB each | 0.5 core |
| 10000 req/s | 5+ | 1GB each | 1 core |

---

## 8. Priority Order for Production

1. **Metrics** - Can't improve what you can't measure
2. **Integration tests** - Confidence for refactoring
3. **Kubernetes manifests** - Deployment automation
4. **Structured logging** - Debuggability
5. **TLS** - Security baseline
6. **Chaos tests** - Validate resilience claims
7. **Runbooks** - Operational readiness
8. **Tracing** - Debug complex issues

---

## 9. Not Doing (Explicitly Out of Scope)

| Feature | Why Not |
|---------|---------|
| Multi-region | Overkill for tube status |
| Event sourcing | No audit requirement |
| GraphQL | REST is sufficient |
| gRPC | No internal service mesh |
