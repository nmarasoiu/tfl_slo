package com.ig.tfl.crdt;

import com.ig.tfl.client.TflClient;
import com.ig.tfl.model.TubeStatus;
import com.ig.tfl.resilience.CircuitBreaker;
import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;
import org.apache.pekko.actor.testkit.typed.javadsl.ActorTestKit;
import org.apache.pekko.actor.testkit.typed.javadsl.TestProbe;
import org.junit.jupiter.api.*;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;
import java.util.concurrent.TimeUnit;

/**
 * Integration tests for TubeStatusReplicator actor.
 *
 * Uses Pekko ActorTestKit with a cluster-enabled system.
 * The TflApiClient is stubbed to return known data.
 */
class TubeStatusReplicatorTest {

    private static ActorTestKit testKit;

    @BeforeAll
    static void setupClass() {
        // Create a minimal cluster config for single-node testing
        Config config = ConfigFactory.parseString("""
                pekko {
                    loglevel = "WARNING"
                    actor {
                        provider = "cluster"
                        allow-java-serialization = on
                    }
                    remote.artery {
                        canonical.hostname = "127.0.0.1"
                        canonical.port = 0
                    }
                    cluster {
                        seed-nodes = []
                        downing-provider-class = "org.apache.pekko.cluster.sbr.SplitBrainResolverProvider"
                    }
                }
                """).resolve();

        testKit = ActorTestKit.create("test-system", config);

        // Join cluster as single node
        org.apache.pekko.cluster.typed.Cluster.get(testKit.system()).manager()
                .tell(org.apache.pekko.cluster.typed.Join.create(
                        org.apache.pekko.cluster.typed.Cluster.get(testKit.system())
                                .selfMember().address()));
    }

    @AfterAll
    static void teardownClass() {
        if (testKit != null) {
            testKit.shutdownTestKit();
        }
    }

    @Test
    void returnsNullWhenNoDataAvailable() {
        var stubClient = new StubTflClient(null);
        var replicator = testKit.spawn(
                TubeStatusReplicator.create(
                        stubClient,
                        "test-node",
                        Duration.ofHours(1),  // Long refresh interval
                        Duration.ofSeconds(5)));

        TestProbe<TubeStatusReplicator.StatusResponse> probe =
                testKit.createTestProbe();

        replicator.tell(new TubeStatusReplicator.GetStatus(probe.ref()));

        var response = probe.receiveMessage(Duration.ofSeconds(5));
        // Initially null since no refresh has happened yet
        assertThat(response.status()).isNull();
    }

    @Test
    void returnsStatusAfterRefresh() {
        var sampleStatus = createSampleStatus();
        var stubClient = new StubTflClient(sampleStatus);

        var replicator = testKit.spawn(
                TubeStatusReplicator.create(
                        stubClient,
                        "test-node",
                        Duration.ofMillis(100),  // Fast refresh
                        Duration.ofSeconds(30)));

        // Use awaitility to poll until data is available (handles jitter)
        await().atMost(10, TimeUnit.SECONDS).untilAsserted(() -> {
            TestProbe<TubeStatusReplicator.StatusResponse> probe =
                    testKit.createTestProbe();
            replicator.tell(new TubeStatusReplicator.GetStatus(probe.ref()));
            var response = probe.receiveMessage(Duration.ofSeconds(2));
            assertThat(response.status()).isNotNull();
            assertThat(response.status().lines()).hasSize(1);
        });
    }

    @Test
    void fetchesFromTflOnRefreshTick() {
        var fetchCount = new AtomicInteger(0);
        var stubClient = new StubTflClient(createSampleStatus()) {
            @Override
            public CompletionStage<TubeStatus> fetchAllLinesAsync() {
                fetchCount.incrementAndGet();
                return super.fetchAllLinesAsync();
            }
        };

        testKit.spawn(
                TubeStatusReplicator.create(
                        stubClient,
                        "test-node",
                        Duration.ofMillis(100),  // 100ms refresh
                        Duration.ofMillis(50)));  // 50ms freshness - data is always stale

        // Use awaitility to wait for at least 2 fetches (handles jitter)
        await().atMost(10, TimeUnit.SECONDS).untilAsserted(() ->
                assertThat(fetchCount.get()).isGreaterThanOrEqualTo(2));
    }

    @Test
    void handlesApiFailureGracefully() throws InterruptedException {
        var failingClient = new StubTflClient(null) {
            @Override
            public CompletionStage<TubeStatus> fetchAllLinesAsync() {
                return CompletableFuture.failedFuture(
                        new RuntimeException("TfL API unavailable"));
            }
        };

        var replicator = testKit.spawn(
                TubeStatusReplicator.create(
                        failingClient,
                        "test-node",
                        Duration.ofMillis(100),
                        Duration.ofSeconds(5)));

        // Wait for failed refresh attempt
        Thread.sleep(200);

        // Actor should still be alive and respond
        TestProbe<TubeStatusReplicator.StatusResponse> probe =
                testKit.createTestProbe();

        replicator.tell(new TubeStatusReplicator.GetStatus(probe.ref()));

        var response = probe.receiveMessage(Duration.ofSeconds(5));
        // Returns null status (no data) but doesn't crash
        assertThat(response).isNotNull();
    }

    @Test
    void respondsToMultipleConcurrentRequests() throws InterruptedException {
        var stubClient = new StubTflClient(createSampleStatus());

        var replicator = testKit.spawn(
                TubeStatusReplicator.create(
                        stubClient,
                        "test-node",
                        Duration.ofMillis(50),
                        Duration.ofSeconds(30)));

        // Wait for initial data
        Thread.sleep(100);

        // Send multiple concurrent requests
        var probes = java.util.stream.IntStream.range(0, 5)
                .<TestProbe<TubeStatusReplicator.StatusResponse>>mapToObj(
                        i -> testKit.createTestProbe())
                .toList();

        probes.forEach(probe ->
                replicator.tell(new TubeStatusReplicator.GetStatus(probe.ref())));

        // All should get responses
        for (var probe : probes) {
            var response = probe.receiveMessage(Duration.ofSeconds(5));
            assertThat(response).isNotNull();
        }
    }

    private TubeStatus createSampleStatus() {
        return new TubeStatus(
                List.of(new TubeStatus.LineStatus(
                        "victoria",
                        "Victoria",
                        "Good Service",
                        "Good Service",
                        List.of()
                )),
                Instant.now(),
                "test-node"
        );
    }

    /**
     * Stub TflClient for testing.
     */
    static class StubTflClient implements TflClient {
        private final TubeStatus stubStatus;

        StubTflClient(TubeStatus stubStatus) {
            this.stubStatus = stubStatus;
        }

        @Override
        public CompletionStage<TubeStatus> fetchAllLinesAsync() {
            if (stubStatus == null) {
                return CompletableFuture.completedFuture(null);
            }
            // Return fresh status with current timestamp
            return CompletableFuture.completedFuture(new TubeStatus(
                    stubStatus.lines(),
                    Instant.now(),
                    stubStatus.queriedBy()
            ));
        }

        @Override
        public CompletionStage<TubeStatus> fetchLineStatusAsync(String lineId, LocalDate from, LocalDate to) {
            if (stubStatus == null) {
                return CompletableFuture.completedFuture(null);
            }
            // Filter to matching line
            var filtered = stubStatus.lines().stream()
                    .filter(line -> line.id().equalsIgnoreCase(lineId))
                    .toList();
            return CompletableFuture.completedFuture(new TubeStatus(
                    filtered,
                    Instant.now(),
                    stubStatus.queriedBy()
            ));
        }

        @Override
        public CircuitBreaker.State getCircuitState() {
            return CircuitBreaker.State.CLOSED;
        }
    }
}
