package com.ig.tfl.crdt;

import com.ig.tfl.client.TflGateway;
import com.ig.tfl.model.TubeStatus;
import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;
import org.apache.pekko.actor.testkit.typed.javadsl.ActorTestKit;
import org.apache.pekko.actor.testkit.typed.javadsl.TestProbe;
import org.apache.pekko.actor.typed.ActorRef;
import org.apache.pekko.actor.typed.Behavior;
import org.apache.pekko.actor.typed.javadsl.AbstractBehavior;
import org.apache.pekko.actor.typed.javadsl.ActorContext;
import org.apache.pekko.actor.typed.javadsl.Behaviors;
import org.apache.pekko.actor.typed.javadsl.Receive;
import org.junit.jupiter.api.*;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;
import java.util.concurrent.TimeUnit;

/**
 * Integration tests for TubeStatusReplicator actor.
 *
 * Uses Pekko ActorTestKit with a cluster-enabled system.
 * The TflGateway is stubbed to return known data.
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
        var gateway = testKit.spawn(StubTflGateway.create(() -> null));
        var replicator = testKit.spawn(
                TubeStatusReplicator.create(
                        gateway,
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
        var gateway = testKit.spawn(StubTflGateway.create(TubeStatusReplicatorTest::createSampleStatus));

        var replicator = testKit.spawn(
                TubeStatusReplicator.create(
                        gateway,
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
        var gateway = testKit.spawn(StubTflGateway.create(() -> {
            fetchCount.incrementAndGet();
            return createSampleStatus();
        }));

        testKit.spawn(
                TubeStatusReplicator.create(
                        gateway,
                        "test-node",
                        Duration.ofMillis(100),  // 100ms refresh
                        Duration.ofMillis(50)));  // 50ms freshness - data is always stale

        // Use awaitility to wait for at least 2 fetches (handles jitter)
        await().atMost(10, TimeUnit.SECONDS).untilAsserted(() ->
                assertThat(fetchCount.get()).isGreaterThanOrEqualTo(2));
    }

    @Test
    void handlesApiFailureGracefully() throws InterruptedException {
        var gateway = testKit.spawn(StubTflGateway.createFailing(
                new RuntimeException("TfL API unavailable")));

        var replicator = testKit.spawn(
                TubeStatusReplicator.create(
                        gateway,
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
        var gateway = testKit.spawn(StubTflGateway.create(TubeStatusReplicatorTest::createSampleStatus));

        var replicator = testKit.spawn(
                TubeStatusReplicator.create(
                        gateway,
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

    @Test
    void getStatusWithFreshness_returnsFreshDataWhenCacheIsFreshEnough() throws InterruptedException {
        var gateway = testKit.spawn(StubTflGateway.create(TubeStatusReplicatorTest::createSampleStatus));

        var replicator = testKit.spawn(
                TubeStatusReplicator.create(
                        gateway,
                        "test-node",
                        Duration.ofMillis(50),
                        Duration.ofSeconds(30)));

        // Wait for initial data
        await().atMost(5, TimeUnit.SECONDS).untilAsserted(() -> {
            TestProbe<TubeStatusReplicator.StatusResponse> p = testKit.createTestProbe();
            replicator.tell(new TubeStatusReplicator.GetStatus(p.ref()));
            assertThat(p.receiveMessage(Duration.ofSeconds(2)).status()).isNotNull();
        });

        // Request with high maxAgeMs - cache should be fresh enough
        TestProbe<TubeStatusReplicator.StatusResponse> probe = testKit.createTestProbe();
        replicator.tell(new TubeStatusReplicator.GetStatusWithFreshness(60000L, probe.ref()));

        var response = probe.receiveMessage(Duration.ofSeconds(5));
        assertThat(response.status()).isNotNull();
        assertThat(response.isStale()).isFalse();
        assertThat(response.requestedMaxAgeMs()).isEqualTo(60000L);
    }

    @Test
    void getStatusWithFreshness_returnsStaleDataWhenTflFails() throws InterruptedException {
        // Use a controllable flag instead of fetch count to avoid CRDT pollution issues
        var shouldSucceed = new AtomicBoolean(true);
        var gateway = testKit.spawn(StubTflGateway.create(() -> {
            if (shouldSucceed.get()) {
                return createSampleStatus();
            }
            throw new RuntimeException("TfL API unavailable");
        }));

        var replicator = testKit.spawn(
                TubeStatusReplicator.create(
                        gateway,
                        "test-node",
                        Duration.ofHours(1),  // Don't auto-refresh
                        Duration.ofSeconds(30)));

        // Manually trigger initial fetch to ensure we control when it happens
        replicator.tell(new TubeStatusReplicator.TriggerBackgroundRefresh());

        // Wait for initial data
        await().atMost(5, TimeUnit.SECONDS).untilAsserted(() -> {
            TestProbe<TubeStatusReplicator.StatusResponse> p = testKit.createTestProbe();
            replicator.tell(new TubeStatusReplicator.GetStatus(p.ref()));
            assertThat(p.receiveMessage(Duration.ofSeconds(2)).status()).isNotNull();
        });

        // Now make TfL fail for subsequent fetches
        shouldSucceed.set(false);

        // Wait a bit so data becomes "old"
        Thread.sleep(100);

        // Request with maxAgeMs=0 - cache is always stale, triggers fetch which fails
        TestProbe<TubeStatusReplicator.StatusResponse> probe = testKit.createTestProbe();
        replicator.tell(new TubeStatusReplicator.GetStatusWithFreshness(0L, probe.ref()));

        var response = probe.receiveMessage(Duration.ofSeconds(5));
        // Should return stale data with isStale=true
        assertThat(response.status()).isNotNull();
        assertThat(response.isStale()).isTrue();
        assertThat(response.requestedMaxAgeMs()).isEqualTo(0L);
    }

    private static TubeStatus createSampleStatus() {
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
     * Stub TflGateway actor for testing.
     */
    static class StubTflGateway extends AbstractBehavior<TflGateway.Command> {
        private final Supplier<TubeStatus> statusSupplier;
        private final Throwable failWith;

        static Behavior<TflGateway.Command> create(Supplier<TubeStatus> statusSupplier) {
            return Behaviors.setup(ctx -> new StubTflGateway(ctx, statusSupplier, null));
        }

        static Behavior<TflGateway.Command> createFailing(Throwable error) {
            return Behaviors.setup(ctx -> new StubTflGateway(ctx, () -> null, error));
        }

        private StubTflGateway(ActorContext<TflGateway.Command> context,
                               Supplier<TubeStatus> statusSupplier,
                               Throwable failWith) {
            super(context);
            this.statusSupplier = statusSupplier;
            this.failWith = failWith;
        }

        @Override
        public Receive<TflGateway.Command> createReceive() {
            return newReceiveBuilder()
                    .onMessage(TflGateway.FetchAllLines.class, this::onFetchAllLines)
                    .onMessage(TflGateway.FetchLineWithDateRange.class, this::onFetchLineWithDateRange)
                    .onMessage(TflGateway.GetCircuitState.class, this::onGetCircuitState)
                    .build();
        }

        private Behavior<TflGateway.Command> onFetchAllLines(TflGateway.FetchAllLines msg) {
            if (failWith != null) {
                msg.replyTo().tell(new TflGateway.FetchResponse(null, failWith));
            } else {
                try {
                    TubeStatus status = statusSupplier.get();
                    msg.replyTo().tell(new TflGateway.FetchResponse(status, null));
                } catch (Exception e) {
                    msg.replyTo().tell(new TflGateway.FetchResponse(null, e));
                }
            }
            return this;
        }

        private Behavior<TflGateway.Command> onFetchLineWithDateRange(TflGateway.FetchLineWithDateRange msg) {
            if (failWith != null) {
                msg.replyTo().tell(new TflGateway.FetchResponse(null, failWith));
            } else {
                TubeStatus status = statusSupplier.get();
                if (status != null) {
                    var filtered = status.lines().stream()
                            .filter(line -> line.id().equalsIgnoreCase(msg.lineId()))
                            .toList();
                    msg.replyTo().tell(new TflGateway.FetchResponse(
                            new TubeStatus(filtered, Instant.now(), status.queriedBy()), null));
                } else {
                    msg.replyTo().tell(new TflGateway.FetchResponse(null, null));
                }
            }
            return this;
        }

        private Behavior<TflGateway.Command> onGetCircuitState(TflGateway.GetCircuitState msg) {
            msg.replyTo().tell(new TflGateway.CircuitStateResponse(TflGateway.CircuitState.CLOSED));
            return this;
        }
    }
}
