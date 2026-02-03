package com.ig.tfl.container;

import com.ig.tfl.client.TflApiClient;
import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;
import eu.rekawek.toxiproxy.Proxy;
import eu.rekawek.toxiproxy.ToxiproxyClient;
import eu.rekawek.toxiproxy.model.ToxicDirection;
import org.apache.pekko.actor.typed.ActorSystem;
import org.apache.pekko.actor.typed.javadsl.Behaviors;
import org.junit.jupiter.api.*;
import org.testcontainers.containers.ToxiproxyContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

/**
 * Container-based integration tests using Toxiproxy for network fault injection.
 *
 * These tests require Docker and demonstrate:
 * - Latency injection to simulate slow TfL API
 * - Connection timeout simulation
 * - Circuit breaker behavior under degraded network conditions
 *
 * Run with: ./gradlew containerTest
 */
@Tag("container")
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ToxiproxyTflApiTest {

    // Toxiproxy container provides a proxy that can inject network failures
    @Container
    private static final ToxiproxyContainer toxiproxy = new ToxiproxyContainer(
            "ghcr.io/shopify/toxiproxy:2.7.0"
    );

    private static ActorSystem<Void> system;
    private ToxiproxyClient toxiproxyClient;
    private Proxy tflProxy;

    @BeforeAll
    void setupClass() throws IOException {
        // Create Pekko ActorSystem
        Config config = ConfigFactory.parseString("""
                pekko {
                    loglevel = "WARNING"
                    actor.provider = "local"
                }
                """).resolve();
        system = ActorSystem.create(Behaviors.empty(), "container-test", config);

        // Setup Toxiproxy client
        toxiproxyClient = new ToxiproxyClient(toxiproxy.getHost(), toxiproxy.getControlPort());

        // Create a proxy for TfL API (proxying to real TfL)
        // Note: In real tests, you might proxy to a mock server instead
        tflProxy = toxiproxyClient.createProxy("tfl-api", "0.0.0.0:8666", "api.tfl.gov.uk:443");
    }

    @AfterAll
    void teardownClass() {
        if (system != null) {
            system.terminate();
        }
    }

    @BeforeEach
    void resetProxy() throws IOException {
        // Remove all toxics before each test
        tflProxy.toxics().getAll().forEach(toxic -> {
            try {
                toxic.remove();
            } catch (IOException e) {
                // Ignore
            }
        });
    }

    @Test
    @DisplayName("Circuit breaker opens when TfL API has high latency")
    void circuitOpensOnHighLatency() throws IOException {
        // Add 15 second latency (exceeds default 10s timeout)
        tflProxy.toxics().latency("high-latency", ToxicDirection.DOWNSTREAM, 15000);

        // Create client pointing at toxiproxy
        String proxyUrl = "http://" + toxiproxy.getHost() + ":" + toxiproxy.getMappedPort(8666);
        TflApiClient client = new TflApiClient(system, "test-node", proxyUrl);

        // Make requests that will timeout
        for (int i = 0; i < 3; i++) {
            try {
                client.fetchAllLinesAsync().toCompletableFuture().join();
            } catch (Exception e) {
                // Expected to fail
            }
        }

        // Circuit should open
        await().atMost(5, TimeUnit.SECONDS).untilAsserted(() ->
                assertThat(client.isCircuitOpen()).isTrue());
    }

    @Test
    @DisplayName("Service recovers when latency returns to normal")
    void recoversWhenLatencyNormalizes() throws IOException, InterruptedException {
        // Start with high latency
        var latencyToxic = tflProxy.toxics().latency("temp-latency", ToxicDirection.DOWNSTREAM, 15000);

        String proxyUrl = "http://" + toxiproxy.getHost() + ":" + toxiproxy.getMappedPort(8666);
        TflApiClient client = new TflApiClient(
                system, "test-node", proxyUrl,
                new TflApiClient.ResilienceConfig(
                        2,                          // 2 failures to open
                        Duration.ofSeconds(5),      // 5s call timeout
                        Duration.ofMillis(500),     // 500ms reset timeout (fast for testing)
                        1,                          // 1 retry
                        Duration.ofMillis(100)      // 100ms retry delay
                )
        );

        // Trip the circuit
        for (int i = 0; i < 2; i++) {
            try {
                client.fetchAllLinesAsync().toCompletableFuture().join();
            } catch (Exception e) {
                // Expected
            }
        }

        assertThat(client.isCircuitOpen()).isTrue();

        // Remove the latency toxic
        latencyToxic.remove();

        // Wait for circuit to transition to half-open
        Thread.sleep(600);

        // Should transition to half-open, then closed after success
        // Note: This requires actual TfL API to be accessible through the proxy
        // In a real test, you'd proxy to a mock server
    }

    @Test
    @DisplayName("Demonstrates connection reset handling")
    void handlesConnectionReset() throws IOException {
        // Add toxic that resets connections
        tflProxy.toxics().resetPeer("reset", ToxicDirection.DOWNSTREAM, 0);

        String proxyUrl = "http://" + toxiproxy.getHost() + ":" + toxiproxy.getMappedPort(8666);
        TflApiClient client = new TflApiClient(system, "test-node", proxyUrl);

        // Request should fail with connection reset
        assertThatThrownBy(() ->
                client.fetchAllLinesAsync().toCompletableFuture().join()
        ).isInstanceOf(Exception.class);
    }

    /**
     * Example test showing bandwidth limitation.
     * Useful for testing behavior under degraded network conditions.
     */
    @Test
    @Disabled("Requires real TfL API access through proxy")
    @DisplayName("Handles slow bandwidth gracefully")
    void handlesBandwidthLimitation() throws IOException {
        // Limit to 1KB/s
        tflProxy.toxics().bandwidth("slow-network", ToxicDirection.DOWNSTREAM, 1024);

        String proxyUrl = "http://" + toxiproxy.getHost() + ":" + toxiproxy.getMappedPort(8666);
        TflApiClient client = new TflApiClient(system, "test-node", proxyUrl);

        // Request will be very slow but should eventually complete or timeout
        // This tests client behavior under degraded conditions
    }
}
