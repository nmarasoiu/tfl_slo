# Production Readiness Checklist

This document outlines what's needed to take this service from exercise to production.

---

## 1. Testing Strategy

### Philosophy: Detroit Style (Classical TDD)

- **Real collaborators** over mocks where possible
- **Mock at system boundaries** only (TfL API, clock, network)
- **Integration tests are first-class** - they catch what unit tests miss during refactors
- **High ROI focus** - test behavior, not implementation details

### Test Pyramid

```
                    ┌─────────────┐
                    │   E2E/Chaos │  Few, expensive, high confidence
                    ├─────────────┤
                    │ Integration │  Real components, mocked boundaries
                    ├─────────────┤
                    │    Unit     │  Pure functions, state machines
                    └─────────────┘
```

#### Unit Tests (Fast, Focused)

| Component | What to Test | Approach |
|-----------|--------------|----------|
| CircuitBreaker | State transitions (CLOSED→OPEN→HALF_OPEN) | Real clock or controllable clock |
| RetryPolicy | Backoff calculation, jitter bounds | Pure function, no mocking |
| RateLimiter | Token bucket refill, exhaustion | Controllable clock |
| TubeStatus | Freshness calculation, confidence levels | Pure |

```java
// Example: Circuit breaker with real failures, not mocks
@Test
void opensAfterFiveConsecutiveFailures() {
    var cb = new CircuitBreaker("test", 5, Duration.ofSeconds(30));

    for (int i = 0; i < 5; i++) {
        cb.onFailure(new RuntimeException("fail " + i));
    }

    assertThat(cb.getState()).isEqualTo(State.OPEN);
}
```

#### Integration Tests (Real Collaborators, Mocked Boundaries)

| Test | Components | Mocked |
|------|------------|--------|
| TflApiClient integration | HttpClient + CircuitBreaker + RetryPolicy | WireMock for TfL |
| HTTP routes | Full Pekko HTTP stack | TfL API |
| CRDT replication | Multi-node Pekko cluster | TfL API |

```java
// Example: Real HTTP client against WireMock
@Test
void retriesOn503ThenSucceeds() {
    wireMock.stubFor(get("/Line/Mode/tube/Status")
        .inScenario("retry")
        .whenScenarioStateIs(STARTED)
        .willReturn(serverError())
        .willSetStateTo("second-attempt"));

    wireMock.stubFor(get("/Line/Mode/tube/Status")
        .inScenario("retry")
        .whenScenarioStateIs("second-attempt")
        .willReturn(okJson(TUBE_STATUS_JSON)));

    var client = new TflApiClient("test-node");
    var result = client.fetchAllLines();

    assertThat(result.lines()).hasSize(11);
    wireMock.verify(2, getRequestedFor(urlPathEqualTo("/Line/Mode/tube/Status")));
}
```

#### Multi-Node / Chaos Tests

| Test | Setup | Tool |
|------|-------|------|
| CRDT convergence | 3-node cluster | Pekko Multi-Node TestKit |
| Network partition | Split cluster | Toxiproxy |
| Node failure | Kill random node | Chaos Monkey / custom |
| TfL outage | Block TfL endpoint | Toxiproxy |
| Slow TfL | Add latency | Toxiproxy |

```java
// Example: Toxiproxy partition test
@Test
void continuesServingDuringPartition() {
    // Setup: 3 nodes, all healthy
    cluster.awaitAllNodesUp();

    // Partition node-3 from others
    toxiproxy.partition("node-3");

    // Both partitions should still serve (AP)
    assertThat(queryNode1("/api/v1/tube/status")).isSuccessful();
    assertThat(queryNode3("/api/v1/tube/status")).isSuccessful();

    // Heal partition
    toxiproxy.heal("node-3");

    // Data should converge
    await().atMost(10, SECONDS).until(() ->
        dataOnNode1().equals(dataOnNode3()));
}
```

---

## 2. Observability

### Metrics (Prometheus)

```java
// Add micrometer-registry-prometheus dependency

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

// CRDT metrics
Counter.builder("crdt_updates_total")
    .tag("source", source)  // TFL, PEER
    .register(registry);
```

### Logging (Structured JSON)

```xml
<!-- logback.xml for production -->
<encoder class="net.logstash.logback.encoder.LogstashEncoder">
    <includeMdcKeyName>nodeId</includeMdcKeyName>
    <includeMdcKeyName>requestId</includeMdcKeyName>
</encoder>
```

Key log events:
- Circuit breaker state changes
- TfL fetch success/failure
- CRDT updates received
- Rate limiting triggered

### Distributed Tracing (OpenTelemetry)

```java
// Add opentelemetry-javaagent or manual instrumentation

Span span = tracer.spanBuilder("tfl-fetch")
    .setAttribute("tfl.line", lineId)
    .startSpan();
try (Scope scope = span.makeCurrent()) {
    return tflClient.fetchLine(lineId);
} finally {
    span.end();
}
```

Trace correlation:
- Request ID propagated through all components
- Spans for: HTTP request → CRDT read → TfL fetch → response

---

## 3. Deployment

### Containerization

```dockerfile
# Dockerfile
FROM eclipse-temurin:21-jre-alpine
COPY build/libs/tfl-slo-*.jar app.jar
EXPOSE 8080 2551
ENTRYPOINT ["java", "-jar", "app.jar"]
```

### Kubernetes

```yaml
# deployment.yaml
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
# application.conf for K8s
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

### TLS

| Connection | Current | Production |
|------------|---------|------------|
| Client → Service | HTTP | HTTPS (terminate at ingress or service) |
| Service → TfL | HTTPS | HTTPS (already) |
| Node ↔ Node (Pekko) | Unencrypted | Pekko Artery TLS |

```hocon
# Pekko cluster TLS
pekko.remote.artery {
  transport = tls-tcp
  ssl.config-ssl-engine {
    key-store = "/certs/keystore.jks"
    trust-store = "/certs/truststore.jks"
  }
}
```

### Secrets Management

- TfL API key (if using authenticated endpoints): Kubernetes Secret / Vault
- TLS certificates: cert-manager / Vault

### Input Validation

- Line ID: whitelist valid tube line IDs
- Date range: validate format, reasonable range (not 100 years)
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

| Pattern | Purpose | Implementation |
|---------|---------|----------------|
| Bulkhead | Isolate TfL calls from serving | Separate thread pool |
| Graceful shutdown | Drain connections | Pekko coordinated shutdown |
| Health-based routing | Remove unhealthy nodes | K8s readiness probe |

### Graceful Degradation Matrix

| Failure | Current Behavior | Production Behavior |
|---------|------------------|---------------------|
| TfL down | Serve cache | Same + alert |
| All peers unreachable | Serve local cache | Same + alert |
| High memory | OOM crash | Backpressure + GC tuning |
| High CPU | Slow responses | Autoscaling |

---

## 6. Performance

### Benchmarking (TODO)

```bash
# wrk or k6 load test
wrk -t4 -c100 -d60s http://localhost:8080/api/v1/tube/status
```

Target: 1000 req/s at p99 < 100ms (cache hits)

### JVM Tuning

```bash
# Production JVM flags
java -XX:+UseG1GC \
     -XX:MaxGCPauseMillis=100 \
     -Xms256m -Xmx512m \
     -jar app.jar
```

### Connection Pooling

- HTTP client: default pool is fine for TfL (low volume)
- Pekko remoting: already pooled

---

## 7. Documentation

### Runbooks (TODO)

| Runbook | Trigger | Actions |
|---------|---------|---------|
| TfL API Down | Circuit breaker OPEN > 5min | Check TfL status page, wait or contact TfL |
| High Latency | p99 > 2s | Check TfL latency, check node health, scale up |
| Data Stale | Freshness > 10min | Check circuit breaker, check CRDT replication |
| Node Crash Loop | Pod restart > 3 | Check logs, check resources, check cluster health |

### Architecture Decision Records (ADRs)

Already documented in:
- `ARCHITECTURE.md` - High-level design
- `tech_choices.md` - Technology decisions
- `tradeoffs.md` - Trade-off analysis

---

## 8. Compliance / Audit

### Data Handling

- **No PII**: Tube status is public data
- **No persistence**: Data is ephemeral in memory
- **TfL Terms**: Respect TfL API terms of service (attribution, rate limits)

### Audit Logging

```java
// Log all admin operations
log.info("Config change: {} changed {} from {} to {}",
    user, setting, oldValue, newValue);
```

---

## 9. Rollout Strategy

### Canary Deployment

```yaml
# Argo Rollouts or similar
spec:
  strategy:
    canary:
      steps:
      - setWeight: 10
      - pause: {duration: 5m}
      - setWeight: 50
      - pause: {duration: 10m}
      - setWeight: 100
```

### Feature Flags (Future)

- Historical estimation feature: behind flag
- New TfL API endpoints: behind flag

---

## 10. Cost / Capacity

### Resource Estimates

| Load | Nodes | Memory | CPU |
|------|-------|--------|-----|
| 100 req/s | 2 | 256MB each | 0.1 core |
| 1000 req/s | 3 | 512MB each | 0.5 core |
| 10000 req/s | 5+ | 1GB each | 1 core |

### TfL API Usage

- 3 nodes × 2 req/min = 6 req/min to TfL
- Well under any reasonable rate limit
- Consider authenticated API for higher limits if needed

---

## Priority Order for Production

1. **Metrics** - Can't improve what you can't measure
2. **Integration tests** - Confidence for refactoring
3. **Kubernetes manifests** - Deployment automation
4. **Structured logging** - Debuggability
5. **TLS** - Security baseline
6. **Chaos tests** - Validate resilience claims
7. **Runbooks** - Operational readiness
8. **Tracing** - Debug complex issues

---

## Not Doing (Explicitly Out of Scope)

| Feature | Why Not |
|---------|---------|
| Multi-region | Overkill for tube status |
| Event sourcing | No audit requirement |
| GraphQL | REST is sufficient |
| gRPC | No internal service mesh |
| Custom scheduler | mq-deadline is fine (just kidding, this is Java) |
