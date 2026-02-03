package com.ig.tfl.e2e;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ig.tfl.api.TubeStatusRoutes;
import com.ig.tfl.client.TflApiClient;
import com.ig.tfl.client.TflGateway;
import com.ig.tfl.crdt.TubeStatusReplicator;
import com.ig.tfl.observability.Metrics;
import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;
import org.apache.pekko.actor.typed.ActorRef;
import org.apache.pekko.actor.testkit.typed.javadsl.ActorTestKit;
import org.apache.pekko.http.javadsl.Http;
import org.apache.pekko.http.javadsl.ServerBinding;
import org.apache.pekko.http.javadsl.model.HttpRequest;
import org.apache.pekko.http.javadsl.model.HttpResponse;
import org.apache.pekko.stream.Materializer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.Timeout;

import java.time.Duration;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Full service E2E smoke test - ONE integrated test with real TfL.
 *
 * Purpose: Verify the complete service stack works end-to-end:
 *   Client -> HTTP -> Routes -> Replicator -> TflGateway -> TfL API
 *
 * This is intentionally a SINGLE test to minimize real TfL API calls.
 * Detailed endpoint testing is done in TubeStatusRoutesTest with mocks.
 *
 * Run with: ./gradlew e2eTest
 */
@Tag("e2e")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ServiceSmokeTest {

    private ActorTestKit testKit;
    private ActorRef<TflGateway.Command> tflGateway;
    private ActorRef<TubeStatusReplicator.Command> replicator;
    private ServerBinding serverBinding;
    private String baseUrl;
    private Http http;
    private Materializer materializer;
    private ObjectMapper objectMapper;

    private static final Set<String> EXPECTED_TUBE_LINES = Set.of(
            "bakerloo", "central", "circle", "district", "hammersmith-city",
            "jubilee", "metropolitan", "northern", "piccadilly", "victoria",
            "waterloo-city"
    );

    @BeforeAll
    void setupClass() throws Exception {
        // Create test actor system with cluster disabled
        Config config = ConfigFactory.parseString("""
                pekko {
                    loglevel = "INFO"
                    actor.provider = "local"
                    cluster.enabled = false
                }
                tfl {
                    node-id = "smoke-test-node"
                    http {
                        port = 0
                        ask-timeout = 30s
                        response-timeout = 30s
                    }
                    refresh {
                        interval = 60s
                        recent-enough-threshold = 30s
                        background-refresh-threshold = 20s
                    }
                    rate-limit.requests-per-minute = 100
                    circuit-breaker {
                        failure-threshold = 5
                        open-duration = 30s
                    }
                    retry {
                        max-retries = 2
                        base-delay = 500ms
                    }
                }
                """).withFallback(ConfigFactory.load()).resolve();

        testKit = ActorTestKit.create("service-smoke-test", config);
        objectMapper = new ObjectMapper();

        // Create metrics
        Metrics metrics = new Metrics();

        // Create REAL TfL API client (this is the e2e part)
        TflApiClient tflApiClient = new TflApiClient(
                testKit.system(),
                "smoke-test-node",
                "https://api.tfl.gov.uk"
        );

        // Create TflGateway actor
        tflGateway = testKit.spawn(TflGateway.create(tflApiClient), "tfl-gateway");

        // Create Replicator actor
        replicator = testKit.spawn(
                TubeStatusReplicator.create(
                        tflGateway,
                        "smoke-test-node",
                        Duration.ofSeconds(60),
                        Duration.ofSeconds(30),
                        Duration.ofSeconds(20)
                ),
                "tube-status-replicator"
        );

        // Create and bind HTTP server
        TubeStatusRoutes routes = new TubeStatusRoutes(
                testKit.system(), replicator, tflGateway, metrics);

        http = Http.get(testKit.system());
        materializer = Materializer.createMaterializer(testKit.system());

        serverBinding = http.newServerAt("localhost", 0)
                .bind(routes.routes())
                .toCompletableFuture()
                .get(10, TimeUnit.SECONDS);

        baseUrl = "http://localhost:" + serverBinding.localAddress().getPort();

        // Wait for service to fetch initial data from TfL
        await().atMost(30, TimeUnit.SECONDS).untilAsserted(() -> {
            HttpResponse response = http.singleRequest(HttpRequest.create(baseUrl + "/api/health/ready"))
                    .toCompletableFuture()
                    .get(5, TimeUnit.SECONDS);
            assertThat(response.status().intValue()).isEqualTo(200);
        });
    }

    @AfterAll
    void teardownClass() throws Exception {
        if (serverBinding != null) {
            serverBinding.unbind().toCompletableFuture().get(5, TimeUnit.SECONDS);
        }
        if (testKit != null) {
            testKit.shutdownTestKit();
        }
    }

    /**
     * Single comprehensive E2E test that verifies:
     * 1. Service starts and connects to real TfL API
     * 2. Fetches and caches tube status data
     * 3. Serves data through HTTP endpoints
     * 4. Health checks pass
     * 5. Metrics are available
     *
     * This is ONE test to minimize TfL API calls.
     * Detailed endpoint behavior is tested in TubeStatusRoutesTest with mocks.
     */
    @Test
    @Timeout(value = 60, unit = TimeUnit.SECONDS)
    void fullServiceStack_worksEndToEnd() throws Exception {
        // 1. Verify health endpoints
        HttpResponse liveResponse = http.singleRequest(HttpRequest.create(baseUrl + "/api/health/live"))
                .toCompletableFuture()
                .get(5, TimeUnit.SECONDS);
        assertThat(liveResponse.status().intValue()).isEqualTo(200);

        HttpResponse readyResponse = http.singleRequest(HttpRequest.create(baseUrl + "/api/health/ready"))
                .toCompletableFuture()
                .get(5, TimeUnit.SECONDS);
        assertThat(readyResponse.status().intValue()).isEqualTo(200);

        // 2. Verify metrics endpoint
        HttpResponse metricsResponse = http.singleRequest(HttpRequest.create(baseUrl + "/metrics"))
                .toCompletableFuture()
                .get(5, TimeUnit.SECONDS);
        assertThat(metricsResponse.status().intValue()).isEqualTo(200);

        // 3. Verify tube status endpoint returns real data
        HttpResponse statusResponse = http.singleRequest(HttpRequest.create(baseUrl + "/api/v1/tube/status"))
                .toCompletableFuture()
                .get(5, TimeUnit.SECONDS);
        assertThat(statusResponse.status().intValue()).isEqualTo(200);

        String body = statusResponse.entity()
                .toStrict(5000, materializer)
                .toCompletableFuture()
                .get(5, TimeUnit.SECONDS)
                .getData()
                .utf8String();

        JsonNode response = objectMapper.readTree(body);

        // Verify response structure
        assertThat(response.has("lines")).isTrue();
        assertThat(response.has("meta")).isTrue();

        JsonNode lines = response.get("lines");
        assertThat(lines.isArray()).isTrue();
        assertThat(lines.size()).isGreaterThanOrEqualTo(11);

        // Verify all expected tube lines are present
        Set<String> returnedLineIds = new java.util.HashSet<>();
        for (JsonNode line : lines) {
            returnedLineIds.add(line.get("id").asText());

            // Verify line structure
            assertThat(line.has("id")).isTrue();
            assertThat(line.has("name")).isTrue();
            assertThat(line.has("status")).isTrue();
        }
        assertThat(returnedLineIds).containsAll(EXPECTED_TUBE_LINES);

        // Verify meta contains freshness info
        JsonNode meta = response.get("meta");
        assertThat(meta.has("queriedAt")).isTrue();
        assertThat(meta.has("ageMs")).isTrue();
        assertThat(meta.get("ageMs").asLong()).isLessThan(60000); // Less than 60s old

        System.out.println("Full service E2E smoke test passed - " + lines.size() + " lines returned");
    }
}
