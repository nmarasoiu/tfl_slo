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
import org.apache.pekko.cluster.MemberStatus;
import org.apache.pekko.cluster.typed.Cluster;
import org.apache.pekko.cluster.typed.Join;
import org.junit.jupiter.api.*;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.StreamSupport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Two-node cluster integration test for CRDT replication.
 *
 * Verifies that tube status data written on one node propagates
 * to another node within the expected SLO timeframe (~300ms).
 *
 * This demonstrates:
 * 1. Pekko Distributed Data gossip propagation
 * 2. WriteMajority consistency guarantees
 * 3. Cross-node cache coherence for the tube status service
 */
@Tag("integration")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TwoNodeReplicationTest {

    private ActorTestKit node1;
    private ActorTestKit node2;
    private ActorRef<TubeStatusReplicator.Command> replicator1;
    private ActorRef<TubeStatusReplicator.Command> replicator2;

    // Ports for the two nodes (using high ports to avoid conflicts)
    private static final int NODE1_PORT = 25521;
    private static final int NODE2_PORT = 25522;

    @BeforeAll
    void setupCluster() {
        // Node 1 config - will be the seed node
        Config node1Config = ConfigFactory.parseString("""
                pekko {
                    loglevel = "INFO"
                    actor {
                        provider = "cluster"
                        allow-java-serialization = on
                    }
                    remote.artery {
                        canonical.hostname = "127.0.0.1"
                        canonical.port = %d
                    }
                    cluster {
                        seed-nodes = ["pekko://two-node-test@127.0.0.1:%d"]
                        downing-provider-class = "org.apache.pekko.cluster.sbr.SplitBrainResolverProvider"
                        split-brain-resolver.active-strategy = keep-majority
                    }
                }
                """.formatted(NODE1_PORT, NODE1_PORT)).resolve();

        // Node 2 config - joins node 1
        Config node2Config = ConfigFactory.parseString("""
                pekko {
                    loglevel = "INFO"
                    actor {
                        provider = "cluster"
                        allow-java-serialization = on
                    }
                    remote.artery {
                        canonical.hostname = "127.0.0.1"
                        canonical.port = %d
                    }
                    cluster {
                        seed-nodes = ["pekko://two-node-test@127.0.0.1:%d"]
                        downing-provider-class = "org.apache.pekko.cluster.sbr.SplitBrainResolverProvider"
                        split-brain-resolver.active-strategy = keep-majority
                    }
                }
                """.formatted(NODE2_PORT, NODE1_PORT)).resolve();

        // Start both nodes with SAME system name (required for cluster)
        node1 = ActorTestKit.create("two-node-test", node1Config);
        node2 = ActorTestKit.create("two-node-test", node2Config);

        // Join cluster - node1 joins itself (becomes leader), node2 joins via seed
        Cluster.get(node1.system()).manager().tell(Join.create(
                Cluster.get(node1.system()).selfMember().address()));

        // Wait for both nodes to be UP in the cluster
        await().atMost(30, TimeUnit.SECONDS)
                .pollInterval(500, TimeUnit.MILLISECONDS)
                .until(() -> {
                    var cluster1 = Cluster.get(node1.system());
                    var cluster2 = Cluster.get(node2.system());

                    boolean node1Up = cluster1.selfMember().status() == MemberStatus.up();
                    boolean node2Up = cluster2.selfMember().status() == MemberStatus.up();
                    long memberCount = StreamSupport.stream(
                            cluster1.state().getMembers().spliterator(), false).count();
                    boolean seeEachOther = memberCount >= 2;

                    if (node1Up && node2Up && seeEachOther) {
                        System.out.println("Cluster formed: " + memberCount + " members");
                        return true;
                    }
                    return false;
                });

        // Create stub gateways for each node
        var gateway1 = node1.spawn(ControllableGateway.create(), "gateway1");
        var gateway2 = node2.spawn(ControllableGateway.create(), "gateway2");

        // Create replicators on each node - long refresh interval (we control fetches manually)
        replicator1 = node1.spawn(
                TubeStatusReplicator.create(
                        gateway1,
                        "node-1",
                        Duration.ofHours(1),  // No auto-refresh
                        Duration.ofSeconds(30)),
                "replicator1");

        replicator2 = node2.spawn(
                TubeStatusReplicator.create(
                        gateway2,
                        "node-2",
                        Duration.ofHours(1),  // No auto-refresh
                        Duration.ofSeconds(30)),
                "replicator2");

        System.out.println("Two-node cluster ready for testing");
    }

    @AfterAll
    void teardownCluster() {
        if (node2 != null) {
            node2.shutdownTestKit();
        }
        if (node1 != null) {
            node1.shutdownTestKit();
        }
    }

    @Test
    @Timeout(value = 60, unit = TimeUnit.SECONDS)
    void dataWrittenOnNode1_propagatesToNode2_withinSloTimeframe() throws InterruptedException {
        // 1. Initially, node2 has no data
        TestProbe<TubeStatusReplicator.StatusResponse> probe2Initial = node2.createTestProbe();
        replicator2.tell(new TubeStatusReplicator.GetStatus(probe2Initial.ref()));
        var initialResponse = probe2Initial.receiveMessage(Duration.ofSeconds(5));
        assertThat(initialResponse.status()).isNull();
        System.out.println("Node 2 initially has no data (expected)");

        // 2. Trigger fetch on node1 - this writes to CRDT with WriteMajority
        replicator1.tell(new TubeStatusReplicator.TriggerBackgroundRefresh());

        // 3. Wait for node1 to have the data
        await().atMost(10, TimeUnit.SECONDS).untilAsserted(() -> {
            TestProbe<TubeStatusReplicator.StatusResponse> p = node1.createTestProbe();
            replicator1.tell(new TubeStatusReplicator.GetStatus(p.ref()));
            var resp = p.receiveMessage(Duration.ofSeconds(2));
            assertThat(resp.status()).isNotNull();
        });
        System.out.println("Node 1 has data after TfL fetch");

        // 4. KEY TEST: Wait 300ms (our SLO for CRDT propagation)
        // This simulates a request hitting node2 shortly after node1 wrote
        long propagationDelayMs = 300;
        Thread.sleep(propagationDelayMs);

        // 5. Query node2 via its RefreshTick (which reads from CRDT)
        // This triggers: read from CRDT -> see peer's fresh data -> update local cache
        replicator2.tell(new TubeStatusReplicator.RefreshTick());

        // Small wait for the CRDT read to complete
        Thread.sleep(100);

        // 6. Now node2 should have the data from CRDT gossip
        TestProbe<TubeStatusReplicator.StatusResponse> probe2Final = node2.createTestProbe();
        replicator2.tell(new TubeStatusReplicator.GetStatus(probe2Final.ref()));
        var finalResponse = probe2Final.receiveMessage(Duration.ofSeconds(5));

        assertThat(finalResponse.status())
                .as("Node 2 should have received data via CRDT within %dms", propagationDelayMs)
                .isNotNull();
        assertThat(finalResponse.status().lines()).hasSize(1);
        assertThat(finalResponse.status().lines().get(0).id()).isEqualTo("victoria");

        System.out.println("SUCCESS: Data propagated from node1 to node2 within " + propagationDelayMs + "ms");
        System.out.println("  - Line: " + finalResponse.status().lines().get(0).name());
        System.out.println("  - Originally fetched by: " + finalResponse.status().queriedBy());
        System.out.println("  - Data age: " + finalResponse.status().ageMs() + "ms");
    }

    @Test
    @Timeout(value = 60, unit = TimeUnit.SECONDS)
    void writeMajority_ensuresDataReachesMajorityBeforeReturning() throws InterruptedException {
        // This test verifies that WriteMajority (used in TubeStatusReplicator)
        // ensures at least 2/2 = 1+ nodes acknowledge the write

        // Start fresh - trigger fetch on node1
        replicator1.tell(new TubeStatusReplicator.TriggerBackgroundRefresh());

        // Wait for node1 to complete the fetch
        await().atMost(10, TimeUnit.SECONDS).untilAsserted(() -> {
            TestProbe<TubeStatusReplicator.StatusResponse> p = node1.createTestProbe();
            replicator1.tell(new TubeStatusReplicator.GetStatus(p.ref()));
            assertThat(p.receiveMessage(Duration.ofSeconds(2)).status()).isNotNull();
        });

        // Since we use WriteMajority, the CRDT update waits for majority (both nodes in a 2-node cluster)
        // This means by the time node1's fetch completes, gossip should already be in flight

        // Trigger CRDT read on node2 immediately (no artificial delay)
        replicator2.tell(new TubeStatusReplicator.RefreshTick());
        Thread.sleep(50); // Minimal wait for message processing

        // Verify node2 sees the data
        TestProbe<TubeStatusReplicator.StatusResponse> probe = node2.createTestProbe();
        replicator2.tell(new TubeStatusReplicator.GetStatus(probe.ref()));
        var response = probe.receiveMessage(Duration.ofSeconds(5));

        assertThat(response.status())
                .as("WriteMajority should ensure data reaches node2 quickly")
                .isNotNull();

        System.out.println("WriteMajority consistency verified - data available on node2 immediately after node1 write");
    }

    @Test
    @Timeout(value = 60, unit = TimeUnit.SECONDS)
    void node2_doesNotFetchFromTfl_whenPeerDataIsFresh() throws InterruptedException {
        // This tests the "fetch avoidance" optimization:
        // If peer data is fresh enough, don't call TfL

        // 1. Ensure node1 has fresh data
        replicator1.tell(new TubeStatusReplicator.TriggerBackgroundRefresh());
        await().atMost(10, TimeUnit.SECONDS).untilAsserted(() -> {
            TestProbe<TubeStatusReplicator.StatusResponse> p = node1.createTestProbe();
            replicator1.tell(new TubeStatusReplicator.GetStatus(p.ref()));
            assertThat(p.receiveMessage(Duration.ofSeconds(2)).status()).isNotNull();
        });

        // 2. Wait for propagation
        Thread.sleep(300);

        // 3. Trigger refresh tick on node2 - should read from CRDT, see fresh data, NOT call TfL
        replicator2.tell(new TubeStatusReplicator.RefreshTick());
        Thread.sleep(100);

        // 4. Verify node2 has data (from peer, not from TfL)
        TestProbe<TubeStatusReplicator.StatusResponse> probe = node2.createTestProbe();
        replicator2.tell(new TubeStatusReplicator.GetStatus(probe.ref()));
        var response = probe.receiveMessage(Duration.ofSeconds(5));

        assertThat(response.status()).isNotNull();
        // The data should show it was originally fetched by node-1
        assertThat(response.status().queriedBy()).isEqualTo("node-1");

        System.out.println("Node 2 correctly used peer data instead of fetching from TfL");
        System.out.println("  - Data originally from: " + response.status().queriedBy());
    }

    /**
     * Controllable gateway that returns predictable data.
     * Used instead of real TfL API for deterministic testing.
     */
    static class ControllableGateway extends AbstractBehavior<TflGateway.Command> {

        static Behavior<TflGateway.Command> create() {
            return Behaviors.setup(ControllableGateway::new);
        }

        private ControllableGateway(ActorContext<TflGateway.Command> context) {
            super(context);
        }

        @Override
        public Receive<TflGateway.Command> createReceive() {
            return newReceiveBuilder()
                    .onMessage(TflGateway.FetchAllLines.class, this::onFetchAllLines)
                    .onMessage(TflGateway.FetchLineWithDateRange.class, this::onFetchLine)
                    .onMessage(TflGateway.GetCircuitState.class, this::onGetCircuitState)
                    .build();
        }

        private Behavior<TflGateway.Command> onFetchAllLines(TflGateway.FetchAllLines msg) {
            // Simulate TfL API response
            TubeStatus status = new TubeStatus(
                    List.of(new TubeStatus.LineStatus(
                            "victoria",
                            "Victoria",
                            "Good Service",
                            "Good Service",
                            List.of()
                    )),
                    Instant.now(),
                    getContext().getSelf().path().name().contains("1") ? "node-1" : "node-2"
            );
            msg.replyTo().tell(new TflGateway.FetchResponse(status, null));
            return this;
        }

        private Behavior<TflGateway.Command> onFetchLine(TflGateway.FetchLineWithDateRange msg) {
            msg.replyTo().tell(new TflGateway.FetchResponse(null, null));
            return this;
        }

        private Behavior<TflGateway.Command> onGetCircuitState(TflGateway.GetCircuitState msg) {
            msg.replyTo().tell(new TflGateway.CircuitStateResponse(TflGateway.CircuitState.CLOSED));
            return this;
        }
    }
}
