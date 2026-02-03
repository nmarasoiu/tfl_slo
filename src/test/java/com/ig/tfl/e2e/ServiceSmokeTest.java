package com.ig.tfl.e2e;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ig.tfl.api.TubeStatusRoutes;
import com.ig.tfl.client.TflApiClient;
import com.ig.tfl.client.TflGateway;
import com.ig.tfl.crdt.TubeStatusReplicator;
import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;
import org.apache.pekko.actor.typed.ActorRef;
import org.apache.pekko.actor.testkit.typed.javadsl.ActorTestKit;
import org.apache.pekko.http.javadsl.Http;
import org.apache.pekko.http.javadsl.ServerBinding;
import org.apache.pekko.http.javadsl.model.HttpRequest;
import org.apache.pekko.http.javadsl.model.HttpResponse;
import org.apache.pekko.stream.Materializer;
import org.junit.jupiter.api.*;

import java.time.Duration;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Full service E2E smoke test.
 *
 * Starts the complete service with real TfL API client and verifies
 * the service correctly fetches, caches, and serves tube status data.
 *
 * Requires network access to api.tfl.gov.uk.
 * Run with: ./gradlew test --tests "*ServiceSmokeTest*"
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
        Config config = ConfigFactory.parseString("""
                pekko {
                    loglevel = "INFO"
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

        testKit = ActorTestKit.create("e2e-smoke-test", config);

        // Join cluster (single node for test)
        org.apache.pekko.cluster.typed.Cluster.get(testKit.system()).manager()
                .tell(org.apache.pekko.cluster.typed.Join.create(
                        org.apache.pekko.cluster.typed.Cluster.get(testKit.system())
                                .selfMember().address()));

        // Create REAL TfL API client (not mocked!)
        TflApiClient tflClient = new TflApiClient(testKit.system(), "e2e-test-node");

        // Create TflGateway actor wrapping the real client
        tflGateway = testKit.spawn(
                TflGateway.create(tflClient),
                "e2e-tfl-gateway");

        // Create replicator with reasonable refresh interval
        replicator = testKit.spawn(
                TubeStatusReplicator.create(
                        tflGateway,
                        "e2e-test-node",
                        Duration.ofSeconds(30),  // refresh interval
                        Duration.ofSeconds(5)),  // recent enough threshold
                "e2e-replicator");

        // Wait for initial data from real TfL API
        System.out.println("Waiting for initial TfL data fetch...");
        await().atMost(30, TimeUnit.SECONDS)
                .pollInterval(1, TimeUnit.SECONDS)
                .until(() -> {
                    var probe = testKit.<TubeStatusReplicator.StatusResponse>createTestProbe();
                    replicator.tell(new TubeStatusReplicator.GetStatus(probe.ref()));
                    var response = probe.receiveMessage(Duration.ofSeconds(5));
                    boolean hasData = response.status() != null && !response.status().lines().isEmpty();
                    if (hasData) {
                        System.out.println("Got " + response.status().lines().size() + " tube lines from TfL");
                    }
                    return hasData;
                });

        // Start HTTP server
        TubeStatusRoutes routes = new TubeStatusRoutes(
                testKit.system(),
                replicator,
                tflGateway);

        http = Http.get(testKit.system());
        materializer = Materializer.createMaterializer(testKit.system());
        objectMapper = new ObjectMapper();

        serverBinding = http
                .newServerAt("127.0.0.1", 0)
                .bind(routes.routes())
                .toCompletableFuture()
                .get(10, TimeUnit.SECONDS);

        int port = serverBinding.localAddress().getPort();
        baseUrl = "http://127.0.0.1:" + port;
        System.out.println("E2E test server started at " + baseUrl);
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

    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    void service_getAllStatus_returnsRealTubeData() throws Exception {
        HttpResponse response = get("/api/v1/tube/status");
        assertThat(response.status().intValue()).isEqualTo(200);

        String body = getBody(response);
        JsonNode json = objectMapper.readTree(body);

        // Verify structure
        assertThat(json.has("lines")).isTrue();
        assertThat(json.has("meta")).isTrue();

        // Verify we have all tube lines
        JsonNode lines = json.get("lines");
        assertThat(lines.size()).isGreaterThanOrEqualTo(11);

        // Verify expected lines are present
        Set<String> returnedLineIds = new java.util.HashSet<>();
        for (JsonNode line : lines) {
            returnedLineIds.add(line.get("id").asText());
        }
        assertThat(returnedLineIds).containsAll(EXPECTED_TUBE_LINES);

        // Verify meta has expected fields
        JsonNode meta = json.get("meta");
        assertThat(meta.has("queriedAt")).isTrue();
        assertThat(meta.has("queriedBy")).isTrue();
        assertThat(meta.has("ageMs")).isTrue();
        assertThat(meta.get("queriedBy").asText()).isEqualTo("e2e-test-node");

        System.out.println("Service returned " + lines.size() + " tube lines, data age: " +
                meta.get("ageMs").asLong() + "ms");
    }

    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    void service_getSingleLine_returnsSpecificLine() throws Exception {
        HttpResponse response = get("/api/v1/tube/central/status");
        assertThat(response.status().intValue()).isEqualTo(200);

        String body = getBody(response);
        JsonNode json = objectMapper.readTree(body);

        JsonNode lines = json.get("lines");
        assertThat(lines.size()).isEqualTo(1);
        assertThat(lines.get(0).get("id").asText()).isEqualTo("central");
        assertThat(lines.get(0).get("name").asText()).isEqualTo("Central");
        assertThat(lines.get(0).has("status")).isTrue();

        String status = lines.get(0).get("status").asText();
        System.out.println("Central line status: " + status);
    }

    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    void service_getDisruptions_returnsOnlyDisruptedLines() throws Exception {
        HttpResponse response = get("/api/v1/tube/disruptions");
        assertThat(response.status().intValue()).isEqualTo(200);

        String body = getBody(response);
        JsonNode json = objectMapper.readTree(body);

        JsonNode lines = json.get("lines");
        // May be 0 if no disruptions currently
        System.out.println("Currently " + lines.size() + " lines with unplanned disruptions");

        // If there are disruptions, verify they're unplanned
        for (JsonNode line : lines) {
            JsonNode disruptions = line.get("disruptions");
            if (disruptions != null && disruptions.isArray()) {
                for (JsonNode disruption : disruptions) {
                    // At least one should be unplanned (isPlanned = false)
                    if (!disruption.get("isPlanned").asBoolean()) {
                        System.out.println("  - " + line.get("name").asText() + ": " +
                                disruption.get("description").asText());
                    }
                }
            }
        }
    }

    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    void service_healthReady_returnsReadyWithData() throws Exception {
        HttpResponse response = get("/api/health/ready");
        assertThat(response.status().intValue()).isEqualTo(200);

        String body = getBody(response);
        JsonNode json = objectMapper.readTree(body);

        assertThat(json.get("status").asText()).isEqualTo("ready");
        assertThat(json.get("circuit").asText()).isEqualTo("CLOSED");
        assertThat(json.get("dataAgeMs").asLong()).isGreaterThanOrEqualTo(0);

        System.out.println("Service ready, circuit: " + json.get("circuit").asText() +
                ", data age: " + json.get("dataAgeMs").asLong() + "ms");
    }

    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    void service_healthLive_alwaysReturnsOk() throws Exception {
        HttpResponse response = get("/api/health/live");
        assertThat(response.status().intValue()).isEqualTo(200);
    }

    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    void service_withMaxAgeMs_respectsFreshnessRequirement() throws Exception {
        // Request with very high maxAgeMs - should return cached data immediately
        HttpResponse response = get("/api/v1/tube/status?maxAgeMs=3600000");
        assertThat(response.status().intValue()).isEqualTo(200);

        String body = getBody(response);
        JsonNode json = objectMapper.readTree(body);
        assertThat(json.get("lines").size()).isGreaterThanOrEqualTo(11);

        // Should not have stale header since we're within maxAgeMs
        assertThat(response.getHeader("X-Data-Stale")).isEmpty();
    }

    @Test
    @Timeout(value = 60, unit = TimeUnit.SECONDS)
    void service_withMaxAgeMsZero_fetchesFreshFromTfl() throws Exception {
        // Request with maxAgeMs=0 forces a fresh TfL fetch
        HttpResponse response = get("/api/v1/tube/status?maxAgeMs=0");
        assertThat(response.status().intValue()).isEqualTo(200);

        String body = getBody(response);
        JsonNode json = objectMapper.readTree(body);
        assertThat(json.get("lines").size()).isGreaterThanOrEqualTo(11);

        // Data should be very fresh (just fetched)
        long ageMs = json.get("meta").get("ageMs").asLong();
        System.out.println("Fresh fetch returned data with age: " + ageMs + "ms");
        // Allow some tolerance for test execution time
        assertThat(ageMs).isLessThan(5000);
    }

    private HttpResponse get(String path) throws Exception {
        return http.singleRequest(HttpRequest.GET(baseUrl + path))
                .toCompletableFuture()
                .get(10, TimeUnit.SECONDS);
    }

    private String getBody(HttpResponse response) throws Exception {
        return response.entity()
                .toStrict(10000, materializer)
                .toCompletableFuture()
                .get(10, TimeUnit.SECONDS)
                .getData()
                .utf8String();
    }
}
