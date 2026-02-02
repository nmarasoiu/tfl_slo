package com.ig.tfl.client;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.github.tomakehurst.wiremock.stubbing.Scenario;
import com.ig.tfl.model.TubeStatus;
import com.ig.tfl.resilience.CircuitBreaker;
import com.ig.tfl.resilience.RetryPolicy;
import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;
import org.apache.pekko.actor.typed.ActorSystem;
import org.apache.pekko.actor.typed.javadsl.Behaviors;
import org.junit.jupiter.api.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;

/**
 * Integration tests for TflApiClient.
 *
 * Uses WireMock to simulate TfL API responses.
 * Tests real HTTP, real circuit breaker, real retry logic.
 *
 * Only the TfL endpoint is mocked (external system boundary).
 */
class TflApiClientIntegrationTest {

    private static WireMockServer wireMock;
    private static ActorSystem<Void> system;
    private static String sampleResponse;

    private TflApiClient client;

    @BeforeAll
    static void setupClass() throws IOException {
        // Start WireMock
        wireMock = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        wireMock.start();
        WireMock.configureFor("localhost", wireMock.port());

        // Create Pekko ActorSystem for HTTP client
        Config config = ConfigFactory.parseString("""
                pekko {
                    loglevel = "WARNING"
                    actor.provider = "local"
                }
                """).resolve();
        system = ActorSystem.create(Behaviors.empty(), "test-system", config);

        // Load sample TfL response
        sampleResponse = Files.readString(
                Path.of("src/test/resources/fixtures/tfl-response.json"));
    }

    @AfterAll
    static void teardownClass() {
        if (wireMock != null) {
            wireMock.stop();
        }
        if (system != null) {
            system.terminate();
        }
    }

    @BeforeEach
    void setup() {
        wireMock.resetAll();
        // Create fresh client for each test (fresh circuit breaker)
        client = new TflApiClient(system, "test-node", wireMock.baseUrl());
    }

    @Test
    void fetchesAllLinesSuccessfully() {
        wireMock.stubFor(get(urlPathEqualTo("/Line/Mode/tube/Status"))
                .willReturn(okJson(sampleResponse)));

        TubeStatus status = client.fetchAllLinesAsync()
                .toCompletableFuture()
                .join();

        assertThat(status.lines()).hasSize(11);
        assertThat(status.queriedBy()).isEqualTo("test-node");
        assertThat(status.queriedAt()).isNotNull();
    }

    @Test
    void parsesDisruptionsCorrectly() {
        wireMock.stubFor(get(urlPathEqualTo("/Line/Mode/tube/Status"))
                .willReturn(okJson(sampleResponse)));

        TubeStatus status = client.fetchAllLinesAsync()
                .toCompletableFuture()
                .join();

        // District has unplanned disruption
        var district = status.lines().stream()
                .filter(l -> l.id().equals("district"))
                .findFirst()
                .orElseThrow();

        assertThat(district.status()).isEqualTo("Minor Delays");
        assertThat(district.disruptions()).hasSize(1);
        assertThat(district.disruptions().get(0).isPlanned()).isFalse();

        // Piccadilly has planned disruption
        var piccadilly = status.lines().stream()
                .filter(l -> l.id().equals("piccadilly"))
                .findFirst()
                .orElseThrow();

        assertThat(piccadilly.disruptions().get(0).isPlanned()).isTrue();
    }

    @Test
    void retriesOn503ThenSucceeds() {
        // First request fails with 503
        wireMock.stubFor(get(urlPathEqualTo("/Line/Mode/tube/Status"))
                .inScenario("retry-test")
                .whenScenarioStateIs(Scenario.STARTED)
                .willReturn(serverError())
                .willSetStateTo("second-attempt"));

        // Second request succeeds
        wireMock.stubFor(get(urlPathEqualTo("/Line/Mode/tube/Status"))
                .inScenario("retry-test")
                .whenScenarioStateIs("second-attempt")
                .willReturn(okJson(sampleResponse)));

        TubeStatus status = client.fetchAllLinesAsync()
                .toCompletableFuture()
                .join();

        assertThat(status.lines()).isNotEmpty();

        // Verify retry happened
        wireMock.verify(2, getRequestedFor(urlPathEqualTo("/Line/Mode/tube/Status")));
    }

    @Test
    void circuitOpensAfterRepeatedFailures() {
        wireMock.stubFor(get(urlPathEqualTo("/Line/Mode/tube/Status"))
                .willReturn(serverError()));

        // Make multiple failing requests to trip circuit
        // Default circuit breaker threshold is 5 failures
        // Default retry is 3, so first request = 4 failures
        for (int i = 0; i < 2; i++) {
            try {
                client.fetchAllLinesAsync().toCompletableFuture().join();
            } catch (Exception ignored) {
                // Expected to fail
            }
        }

        // Wait a moment for circuit state to update
        await().atMost(1, TimeUnit.SECONDS).untilAsserted(() ->
                assertThat(client.getCircuitState()).isEqualTo(CircuitBreaker.State.OPEN));
    }

    @Test
    void circuitOpenRejectsImmediately() {
        wireMock.stubFor(get(urlPathEqualTo("/Line/Mode/tube/Status"))
                .willReturn(serverError()));

        // Trip the circuit
        for (int i = 0; i < 3; i++) {
            try {
                client.fetchAllLinesAsync().toCompletableFuture().join();
            } catch (Exception ignored) {}
        }

        // Clear the request count
        wireMock.resetRequests();

        // Next request should be rejected by circuit without hitting server
        assertThatThrownBy(() ->
                client.fetchAllLinesAsync().toCompletableFuture().join())
                .isInstanceOf(CompletionException.class)
                .hasCauseInstanceOf(CircuitBreaker.CircuitOpenException.class);

        // Should not have made any new requests
        wireMock.verify(0, getRequestedFor(urlPathEqualTo("/Line/Mode/tube/Status")));
    }

    @Test
    void handlesTimeoutGracefully() {
        wireMock.stubFor(get(urlPathEqualTo("/Line/Mode/tube/Status"))
                .willReturn(ok().withFixedDelay(5000)));  // 5s delay

        // Should timeout and retry, eventually fail
        assertThatThrownBy(() ->
                client.fetchAllLinesAsync()
                        .toCompletableFuture()
                        .get(15, TimeUnit.SECONDS))
                .isInstanceOf(Exception.class);
    }

}
