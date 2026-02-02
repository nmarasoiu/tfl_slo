# Testing Strategy

## Philosophy: Detroit Style (Classical TDD)

**Principles:**
1. **Real collaborators** over mocks where possible
2. **Mock at system boundaries** only (TfL API, network)
3. **Test behavior**, not implementation details
4. **High ROI** - focus on tests that catch real bugs

**What we DON'T do:**
- Mock internal classes
- Verify method call counts (brittle)
- Test private methods
- Over-specify interactions

---

## Test Pyramid

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

---

## Layer 1: Unit Tests

### What to Unit Test

| Component | Test | Approach |
|-----------|------|----------|
| CircuitBreaker | State transitions | Real object, controllable clock |
| RetryPolicy | Backoff calculation | Pure function |
| RateLimiter | Token bucket behavior | Real object, controllable clock |
| TubeStatus | Freshness calculation | Pure function |

### Example: Circuit Breaker

```java
@Test
void opensAfterFiveConsecutiveFailures() {
    var cb = new CircuitBreaker("test", 5, Duration.ofSeconds(30));

    // Real failures, no mocking
    for (int i = 0; i < 5; i++) {
        cb.onFailure(new RuntimeException("fail"));
    }

    assertThat(cb.getState()).isEqualTo(State.OPEN);
}

@Test
void transitionsToHalfOpenAfterTimeout() {
    var clock = new ControllableClock();
    var cb = new CircuitBreaker("test", 5, Duration.ofSeconds(30), clock);

    // Open the circuit
    IntStream.range(0, 5).forEach(i -> cb.onFailure(new RuntimeException()));
    assertThat(cb.getState()).isEqualTo(State.OPEN);

    // Advance time
    clock.advance(Duration.ofSeconds(31));

    assertThat(cb.getState()).isEqualTo(State.HALF_OPEN);
}
```

### Example: Retry Backoff Calculation

```java
@Test
void calculatesExponentialBackoff() {
    var policy = RetryPolicy.builder()
        .baseDelay(Duration.ofSeconds(1))
        .maxDelay(Duration.ofSeconds(30))
        .jitterFactor(0)  // Disable jitter for deterministic test
        .build();

    assertThat(policy.calculateDelay(1)).isEqualTo(Duration.ofSeconds(1));
    assertThat(policy.calculateDelay(2)).isEqualTo(Duration.ofSeconds(2));
    assertThat(policy.calculateDelay(3)).isEqualTo(Duration.ofSeconds(4));
    assertThat(policy.calculateDelay(4)).isEqualTo(Duration.ofSeconds(8));
}

@Test
void capsAtMaxDelay() {
    var policy = RetryPolicy.builder()
        .baseDelay(Duration.ofSeconds(1))
        .maxDelay(Duration.ofSeconds(30))
        .jitterFactor(0)
        .build();

    // 2^10 = 1024 seconds, but capped at 30
    assertThat(policy.calculateDelay(10)).isEqualTo(Duration.ofSeconds(30));
}
```

---

## Layer 2: Integration Tests

### TfL API Client with WireMock

**What we test:**
- Real HTTP through Pekko HTTP client
- Real circuit breaker
- Real retry logic
- Mock only the TfL endpoint (WireMock)

```java
@ExtendWith(WireMockExtension.class)
class TflApiClientIntegrationTest {

    @RegisterExtension
    static WireMockExtension wireMock = WireMockExtension.newInstance()
        .options(wireMockConfig().dynamicPort())
        .build();

    private ActorSystem<?> system;
    private TflApiClient client;

    @BeforeEach
    void setup() {
        system = ActorSystem.create(Behaviors.empty(), "test");
        client = new TflApiClient(system, "test-node", wireMock.baseUrl());
    }

    @AfterEach
    void teardown() {
        system.terminate();
    }

    @Test
    void fetchesAllLinesSuccessfully() {
        wireMock.stubFor(get("/Line/Mode/tube/Status")
            .willReturn(okJson(SAMPLE_TFL_RESPONSE)));

        TubeStatus status = client.fetchAllLinesAsync()
            .toCompletableFuture()
            .join();

        assertThat(status.lines()).hasSize(11);
        assertThat(status.source()).isEqualTo(Source.TFL);
    }

    @Test
    void retriesOn503ThenSucceeds() {
        // First request fails
        wireMock.stubFor(get("/Line/Mode/tube/Status")
            .inScenario("retry")
            .whenScenarioStateIs(STARTED)
            .willReturn(serverError())
            .willSetStateTo("attempt-2"));

        // Second request succeeds
        wireMock.stubFor(get("/Line/Mode/tube/Status")
            .inScenario("retry")
            .whenScenarioStateIs("attempt-2")
            .willReturn(okJson(SAMPLE_TFL_RESPONSE)));

        TubeStatus status = client.fetchAllLinesAsync()
            .toCompletableFuture()
            .join();

        assertThat(status.lines()).isNotEmpty();
        wireMock.verify(2, getRequestedFor(urlPathEqualTo("/Line/Mode/tube/Status")));
    }

    @Test
    void circuitOpensAfterRepeatedFailures() {
        wireMock.stubFor(get("/Line/Mode/tube/Status")
            .willReturn(serverError()));

        // Exhaust retries multiple times to trip circuit
        for (int i = 0; i < 5; i++) {
            assertThatThrownBy(() ->
                client.fetchAllLinesAsync().toCompletableFuture().join()
            ).hasCauseInstanceOf(RuntimeException.class);
        }

        assertThat(client.getCircuitState()).isEqualTo(State.OPEN);
    }

    @Test
    void doesNotRetryOn404() {
        wireMock.stubFor(get("/Line/invalid/Status")
            .willReturn(notFound()));

        assertThatThrownBy(() ->
            client.fetchLineAsync("invalid").toCompletableFuture().join()
        ).hasCauseInstanceOf(HttpStatusException.class);

        // Should NOT retry 4xx
        wireMock.verify(1, getRequestedFor(urlPathEqualTo("/Line/invalid/Status")));
    }
}
```

### Actor Integration Tests

**What we test:**
- Real actor behavior
- Real CRDT operations
- Real message passing
- Mock only external systems

```java
class TubeStatusReplicatorIntegrationTest {

    private ActorTestKit testKit;
    private TestProbe<TubeStatusReplicator.StatusResponse> probe;

    @BeforeEach
    void setup() {
        testKit = ActorTestKit.create();
        probe = testKit.createTestProbe();
    }

    @AfterEach
    void teardown() {
        testKit.shutdownTestKit();
    }

    @Test
    void returnsCurrentStatusOnGetStatus() {
        // Create actor with mock TfL client that returns known data
        var mockTflClient = new MockTflApiClient(SAMPLE_STATUS);
        var replicator = testKit.spawn(
            TubeStatusReplicator.create(mockTflClient, "test-node",
                Duration.ofSeconds(30), Duration.ofSeconds(5)));

        // Trigger a refresh first
        Thread.sleep(100);  // Let initial refresh happen

        // Query status
        replicator.tell(new GetStatus(probe.ref()));

        var response = probe.receiveMessage(Duration.ofSeconds(5));
        assertThat(response.status().lines()).isNotEmpty();
    }

    @Test
    void coalescesMultipleRequests() {
        var fetchCounter = new AtomicInteger(0);
        var slowTflClient = new MockTflApiClient(SAMPLE_STATUS, () -> {
            fetchCounter.incrementAndGet();
            Thread.sleep(500);  // Slow fetch
        });

        var replicator = testKit.spawn(
            TubeStatusReplicator.create(slowTflClient, "test-node",
                Duration.ofSeconds(30), Duration.ofSeconds(5)));

        // Send multiple requests concurrently
        var probes = IntStream.range(0, 10)
            .mapToObj(i -> testKit.<StatusResponse>createTestProbe())
            .toList();

        probes.forEach(p -> replicator.tell(new GetStatus(p.ref())));

        // All should get responses
        probes.forEach(p -> p.expectMessageClass(StatusResponse.class));

        // But only one fetch should have happened (coalescing)
        assertThat(fetchCounter.get()).isEqualTo(1);
    }
}
```

---

## Layer 3: Multi-Node Tests

### CRDT Convergence

**What we test:**
- Multiple nodes form cluster
- CRDT replicates across nodes
- Nodes converge to same state

```java
class CrdtConvergenceTest extends PekkoMultiNodeSpec {

    @Test
    void nodesConvergeOnSameData() {
        // Start 3-node cluster
        var node1 = startNode("node-1", 2551);
        var node2 = startNode("node-2", 2552);
        var node3 = startNode("node-3", 2553);

        // Wait for cluster formation
        awaitClusterUp(node1, node2, node3);

        // Write on node1
        var status = new TubeStatus(SAMPLE_LINES, Instant.now(),
            Instant.now(), "node-1", Source.TFL);
        node1.replicator().tell(new Update(STATUS_KEY, status));

        // Read from node3 - should eventually see same data
        await().atMost(10, SECONDS).untilAsserted(() -> {
            var response = node3.replicator().ask(new Get(STATUS_KEY));
            assertThat(response.status()).isEqualTo(status);
        });
    }

    @Test
    void latestWriteWinsOnConflict() {
        var node1 = startNode("node-1", 2551);
        var node2 = startNode("node-2", 2552);
        awaitClusterUp(node1, node2);

        // Write on both nodes "simultaneously"
        var olderStatus = new TubeStatus(SAMPLE_LINES,
            Instant.now().minusSeconds(10), Instant.now().minusSeconds(10),
            "node-1", Source.TFL);
        var newerStatus = new TubeStatus(SAMPLE_LINES,
            Instant.now(), Instant.now(),
            "node-2", Source.TFL);

        node1.replicator().tell(new Update(STATUS_KEY, olderStatus));
        node2.replicator().tell(new Update(STATUS_KEY, newerStatus));

        // Both should converge to newer status (LWW)
        await().atMost(10, SECONDS).untilAsserted(() -> {
            var status1 = node1.replicator().ask(new Get(STATUS_KEY));
            var status2 = node2.replicator().ask(new Get(STATUS_KEY));

            assertThat(status1.fetchedBy()).isEqualTo("node-2");
            assertThat(status2.fetchedBy()).isEqualTo("node-2");
        });
    }
}
```

### Partition Tolerance (Chaos)

```java
class PartitionToleranceTest extends PekkoMultiNodeSpec {

    @Test
    void bothPartitionsServeDataDuringNetworkSplit() {
        var node1 = startNode("node-1");
        var node2 = startNode("node-2");
        var node3 = startNode("node-3");
        awaitClusterUp(node1, node2, node3);

        // Populate initial data
        writeStatus(node1, SAMPLE_STATUS);
        awaitConvergence(node1, node2, node3);

        // Partition: node3 isolated from node1, node2
        toxiproxy.partition("node-3");

        // Both partitions should still serve requests (AP)
        assertThat(queryStatus(node1)).isNotNull();
        assertThat(queryStatus(node3)).isNotNull();  // Serves cached

        // Heal partition
        toxiproxy.heal("node-3");

        // Update on majority side
        writeStatus(node1, UPDATED_STATUS);

        // node3 should eventually get update
        await().atMost(30, SECONDS).untilAsserted(() -> {
            var status3 = queryStatus(node3);
            assertThat(status3).isEqualTo(UPDATED_STATUS);
        });
    }
}
```

---

## Test Configuration

### build.gradle.kts additions

```kotlin
dependencies {
    // Testing
    testImplementation("org.apache.pekko:pekko-actor-testkit-typed_2.13:$pekkoVersion")
    testImplementation("org.apache.pekko:pekko-multi-node-testkit_2.13:$pekkoVersion")
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.2")
    testImplementation("org.assertj:assertj-core:3.25.3")
    testImplementation("org.awaitility:awaitility:4.2.0")
    testImplementation("org.wiremock:wiremock:3.4.2")

    // Chaos testing (optional)
    testImplementation("eu.rekawek.toxiproxy:toxiproxy-java:2.1.7")
}
```

### Test resources

```
src/test/resources/
├── application-test.conf    # Test Pekko config
├── logback-test.xml         # Quieter logging
└── fixtures/
    └── tfl-response.json    # Sample TfL API response
```

---

## What We DON'T Test

| Skip | Why |
|------|-----|
| Pekko internals | Trust the framework |
| JSON parsing edge cases | Jackson is well-tested |
| HTTP protocol details | Pekko HTTP is well-tested |
| CRDT merge algorithm | Pekko Distributed Data is well-tested |

**Test our code, not the framework.**

---

## CI Pipeline

```yaml
test:
  stage: test
  script:
    - ./gradlew test                    # Unit + Integration
    - ./gradlew multiNodeTest           # Multi-node (if separate)
  artifacts:
    reports:
      junit: build/test-results/**/*.xml
```

---

## Coverage Goals

| Layer | Target | Rationale |
|-------|--------|-----------|
| Unit | 90%+ | Fast, cheap, catch logic bugs |
| Integration | Key paths | Real HTTP, real actors |
| Multi-node | Happy + partition | Validate distributed behavior |

**Don't chase 100%** - diminishing returns. Focus on behavior, not lines.
