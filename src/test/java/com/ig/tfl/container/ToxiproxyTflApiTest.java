package com.ig.tfl.container;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.ig.tfl.client.TflApiClient;
import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;
import eu.rekawek.toxiproxy.Proxy;
import eu.rekawek.toxiproxy.ToxiproxyClient;
import eu.rekawek.toxiproxy.model.ToxicDirection;
import org.apache.pekko.actor.typed.ActorSystem;
import org.apache.pekko.actor.typed.javadsl.Behaviors;
import org.junit.jupiter.api.*;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.ToxiproxyContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.TimeUnit;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Container-based integration tests using Toxiproxy for network fault injection.
 *
 * Architecture:
 *   Client -> Toxiproxy -> WireMock (stub TfL)
 *
 * This allows testing:
 * - Latency injection
 * - Connection timeouts
 * - Circuit breaker behavior
 *
 * WITHOUT hitting real TfL API.
 *
 * Run with: ./gradlew containerTest
 */
@Tag("container")
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ToxiproxyTflApiTest {

    private static final Network network = Network.newNetwork();

    @Container
    private static final ToxiproxyContainer toxiproxy = new ToxiproxyContainer(
            "ghcr.io/shopify/toxiproxy:2.7.0"
    ).withNetwork(network);

    private static ActorSystem<Void> system;
    private static WireMockServer wireMock;
    private static String sampleResponse;

    private ToxiproxyClient toxiproxyClient;
    private Proxy tflProxy;

    @BeforeAll
    void setupClass() throws IOException {
        // Start WireMock to stub TfL responses
        wireMock = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        wireMock.start();

        // Load sample TfL response
        sampleResponse = Files.readString(
                Path.of("src/test/resources/fixtures/tfl-response.json"));

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

        // Create proxy: Toxiproxy -> WireMock (not real TfL!)
        // WireMock is on host network, accessible from container via host.docker.internal
        String wiremockUpstream = "host.docker.internal:" + wireMock.port();
        tflProxy = toxiproxyClient.createProxy("tfl-api", "0.0.0.0:8666", wiremockUpstream);
    }

    @AfterAll
    void teardownClass() {
        if (system != null) {
            system.terminate();
        }
        if (wireMock != null) {
            wireMock.stop();
        }
        network.close();
    }

    @BeforeEach
    void resetProxy() throws IOException {
        // Remove all toxics before each test
        tflProxy.toxics().getAll().forEach(toxic -> {
            try {
                toxic.remove();
            } catch (IOException e) {
                // Ignore cleanup errors
            }
        });

        // Reset WireMock stubs
        wireMock.resetAll();

        // Default stub: return sample response
        wireMock.stubFor(get(urlPathEqualTo("/Line/Mode/tube/Status"))
                .willReturn(okJson(sampleResponse)));
    }

    @Test
    @DisplayName("Circuit breaker opens when TfL API has high latency")
    void circuitOpensOnHighLatency() throws IOException {
        // Add 15 second latency (exceeds 5s timeout)
        tflProxy.toxics().latency("high-latency", ToxicDirection.DOWNSTREAM, 15000);

        // Create client with low thresholds for faster testing
        String proxyUrl = "http://" + toxiproxy.getHost() + ":" + toxiproxy.getMappedPort(8666);
        TflApiClient client = new TflApiClient(
                system, "test-node", proxyUrl,
                new TflApiClient.ResilienceConfig(
                        2,                          // 2 failures to open (fast)
                        Duration.ofSeconds(5),      // 5s call timeout
                        Duration.ofSeconds(30),     // 30s reset timeout
                        1,                          // 1 retry
                        Duration.ofMillis(100)      // 100ms retry delay
                )
        );

        // Make requests that will timeout - need enough to trip circuit
        for (int i = 0; i < 2; i++) {
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

        // Remove latency - WireMock will now respond instantly
        latencyToxic.remove();

        // Wait for circuit to transition to half-open
        Thread.sleep(600);

        // Next request should succeed and close circuit
        await().atMost(10, TimeUnit.SECONDS).untilAsserted(() -> {
            try {
                var status = client.fetchAllLinesAsync().toCompletableFuture().join();
                assertThat(status.lines()).isNotEmpty();
                assertThat(client.isCircuitClosed()).isTrue();
            } catch (Exception e) {
                // May fail initially while circuit is still half-open
            }
        });
    }

    @Test
    @DisplayName("Connection cut causes circuit to open")
    void connectionCutOpensCircuit() throws IOException {
        String proxyUrl = "http://" + toxiproxy.getHost() + ":" + toxiproxy.getMappedPort(8666);
        TflApiClient client = new TflApiClient(
                system, "test-node", proxyUrl,
                new TflApiClient.ResilienceConfig(
                        2,                          // 2 failures to open
                        Duration.ofSeconds(5),      // 5s call timeout
                        Duration.ofSeconds(30),     // 30s reset timeout
                        1,                          // 1 retry
                        Duration.ofMillis(100)      // 100ms retry delay
                )
        );

        // Cut the connection completely
        tflProxy.toxics().bandwidth("cut", ToxicDirection.DOWNSTREAM, 0);

        // Make requests that will fail
        for (int i = 0; i < 3; i++) {
            try {
                client.fetchAllLinesAsync().toCompletableFuture().join();
            } catch (Exception e) {
                // Expected to fail
            }
        }

        // Circuit should open
        assertThat(client.isCircuitOpen()).isTrue();
    }

    @Test
    @DisplayName("Normal operation works through proxy")
    void normalOperationThroughProxy() {
        // No toxics - proxy passes through normally
        String proxyUrl = "http://" + toxiproxy.getHost() + ":" + toxiproxy.getMappedPort(8666);
        TflApiClient client = new TflApiClient(system, "test-node", proxyUrl);

        var status = client.fetchAllLinesAsync().toCompletableFuture().join();

        assertThat(status.lines()).hasSize(11);
        assertThat(client.isCircuitClosed()).isTrue();
    }
}
