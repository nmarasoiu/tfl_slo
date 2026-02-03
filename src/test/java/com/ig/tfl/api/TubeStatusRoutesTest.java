package com.ig.tfl.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ig.tfl.client.TflGateway;
import com.ig.tfl.crdt.TubeStatusReplicator;
import com.ig.tfl.model.TubeStatus;
import com.ig.tfl.observability.Metrics;
import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;
import org.apache.pekko.actor.testkit.typed.javadsl.ActorTestKit;
import org.apache.pekko.actor.typed.ActorRef;
import org.apache.pekko.actor.typed.Behavior;
import org.apache.pekko.actor.typed.javadsl.AbstractBehavior;
import org.apache.pekko.actor.typed.javadsl.ActorContext;
import org.apache.pekko.actor.typed.javadsl.Behaviors;
import org.apache.pekko.actor.typed.javadsl.Receive;
import org.apache.pekko.http.javadsl.Http;
import org.apache.pekko.http.javadsl.ServerBinding;
import org.apache.pekko.http.javadsl.model.HttpRequest;
import org.apache.pekko.http.javadsl.model.HttpResponse;
import org.apache.pekko.stream.Materializer;
import org.junit.jupiter.api.*;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;

/**
 * HTTP route tests for tube status endpoints.
 * Uses a real HTTP server binding for integration testing.
 */
class TubeStatusRoutesTest {

    private static ActorTestKit testKit;
    private static ActorRef<TubeStatusReplicator.Command> replicator;
    private static ActorRef<TflGateway.Command> gateway;
    private static ServerBinding serverBinding;
    private static String baseUrl;
    private static Http http;
    private static Materializer materializer;
    private static ObjectMapper objectMapper;

    @BeforeAll
    static void setupClass() throws Exception {
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

        testKit = ActorTestKit.create("routes-test-system", config);

        // Join cluster
        org.apache.pekko.cluster.typed.Cluster.get(testKit.system()).manager()
                .tell(org.apache.pekko.cluster.typed.Join.create(
                        org.apache.pekko.cluster.typed.Cluster.get(testKit.system())
                                .selfMember().address()));

        // Create stub gateway with sample data
        gateway = testKit.spawn(
                StubTflGateway.create(TubeStatusRoutesTest::createSampleStatus),
                "test-gateway");

        // Create replicator with fast refresh
        replicator = testKit.spawn(
                TubeStatusReplicator.create(
                        gateway,
                        "test-node",
                        Duration.ofMillis(100),
                        Duration.ofSeconds(30)),
                "test-replicator");

        // Wait for initial data
        await().atMost(10, TimeUnit.SECONDS).until(() -> {
            var probe = testKit.<TubeStatusReplicator.StatusResponse>createTestProbe();
            replicator.tell(new TubeStatusReplicator.GetStatus(probe.ref()));
            var response = probe.receiveMessage(Duration.ofSeconds(2));
            return response.status() != null;
        });

        // Start HTTP server
        TubeStatusRoutes routes = new TubeStatusRoutes(
                testKit.system(),
                replicator,
                gateway,
                new Metrics(),
                100,  // requests per minute
                Duration.ofSeconds(5));  // ask timeout

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
    }

    @AfterAll
    static void teardownClass() throws Exception {
        if (serverBinding != null) {
            serverBinding.unbind().toCompletableFuture().get(5, TimeUnit.SECONDS);
        }
        if (testKit != null) {
            testKit.shutdownTestKit();
        }
    }

    @Test
    void getAllStatus_returnsAllLines() throws Exception {
        HttpResponse response = get("/api/v1/tube/status");
        assertThat(response.status().intValue()).isEqualTo(200);

        String body = getBody(response);
        JsonNode json = objectMapper.readTree(body);
        assertThat(json.has("lines")).isTrue();
        assertThat(json.get("lines").size()).isEqualTo(3);
        assertThat(json.has("meta")).isTrue();
    }

    @Test
    void getLineStatus_returnsSpecificLine() throws Exception {
        HttpResponse response = get("/api/v1/tube/victoria/status");
        assertThat(response.status().intValue()).isEqualTo(200);

        String body = getBody(response);
        JsonNode json = objectMapper.readTree(body);
        assertThat(json.get("lines").size()).isEqualTo(1);
        assertThat(json.get("lines").get(0).get("id").asText()).isEqualTo("victoria");
    }

    @Test
    void getLineStatus_caseInsensitive() throws Exception {
        HttpResponse response = get("/api/v1/tube/VICTORIA/status");
        assertThat(response.status().intValue()).isEqualTo(200);

        String body = getBody(response);
        JsonNode json = objectMapper.readTree(body);
        assertThat(json.get("lines").get(0).get("id").asText()).isEqualTo("victoria");
    }

    @Test
    void getLineStatus_returnsNotFoundForUnknownLine() throws Exception {
        HttpResponse response = get("/api/v1/tube/unknown-line/status");
        assertThat(response.status().intValue()).isEqualTo(404);
    }

    @Test
    void getLineStatusWithDateRange_returnsStatus() throws Exception {
        String from = LocalDate.now().toString();
        String to = LocalDate.now().plusDays(7).toString();
        HttpResponse response = get("/api/v1/tube/victoria/status/" + from + "/to/" + to);
        assertThat(response.status().intValue()).isEqualTo(200);

        String body = getBody(response);
        JsonNode json = objectMapper.readTree(body);
        assertThat(json.get("lines").size()).isEqualTo(1);
    }

    @Test
    void getLineStatusWithDateRange_rejectsBadDateFormat() throws Exception {
        HttpResponse response = get("/api/v1/tube/victoria/status/invalid/to/also-invalid");
        assertThat(response.status().intValue()).isEqualTo(400);

        String body = getBody(response);
        assertThat(body).contains("Invalid date format");
    }

    @Test
    void getLineStatusWithDateRange_rejectsInvertedDateRange() throws Exception {
        String from = LocalDate.now().plusDays(7).toString();
        String to = LocalDate.now().toString();
        HttpResponse response = get("/api/v1/tube/victoria/status/" + from + "/to/" + to);
        assertThat(response.status().intValue()).isEqualTo(400);

        String body = getBody(response);
        assertThat(body).contains("Start date must be before");
    }

    @Test
    void getDisruptions_returnsOnlyUnplannedDisruptions() throws Exception {
        HttpResponse response = get("/api/v1/tube/disruptions");
        assertThat(response.status().intValue()).isEqualTo(200);

        String body = getBody(response);
        JsonNode json = objectMapper.readTree(body);
        // Only central has unplanned disruption
        assertThat(json.get("lines").size()).isEqualTo(1);
        assertThat(json.get("lines").get(0).get("id").asText()).isEqualTo("central");
    }

    @Test
    void getAllStatus_withMaxAgeMsParam_returnsCachedIfFresh() throws Exception {
        // Request with very high maxAgeMs - cache should be fresh enough
        HttpResponse response = get("/api/v1/tube/status?maxAgeMs=3600000");
        assertThat(response.status().intValue()).isEqualTo(200);

        String body = getBody(response);
        JsonNode json = objectMapper.readTree(body);
        assertThat(json.get("lines").size()).isEqualTo(3);
        // No stale header since cache is fresh enough
        assertThat(response.getHeader("X-Data-Stale")).isEmpty();
    }

    @Test
    void getAllStatus_withMaxAgeMsParam_attemptsRefreshIfStale() throws Exception {
        // Request with maxAgeMs=0 - cache is always "too stale", triggers TfL fetch
        // Our stub TfL gateway returns fresh data, so this should succeed
        HttpResponse response = get("/api/v1/tube/status?maxAgeMs=0");
        assertThat(response.status().intValue()).isEqualTo(200);

        String body = getBody(response);
        JsonNode json = objectMapper.readTree(body);
        assertThat(json.has("lines")).isTrue();
    }

    @Test
    void getAllStatus_withoutMaxAgeMs_returnsWhateverWeHave() throws Exception {
        HttpResponse response = get("/api/v1/tube/status");
        assertThat(response.status().intValue()).isEqualTo(200);

        String body = getBody(response);
        JsonNode json = objectMapper.readTree(body);
        assertThat(json.get("lines").size()).isEqualTo(3);
    }

    @Test
    void getAllStatus_triggersBackgroundRefreshWhenSlightlyStale() throws Exception {
        // With high maxAgeMs, data is fresh enough to return but may trigger background refresh
        // We can't easily test the async trigger, but we verify the response is immediate
        HttpResponse response = get("/api/v1/tube/status?maxAgeMs=60000");
        assertThat(response.status().intValue()).isEqualTo(200);
        // Background refresh is fire-and-forget, no observable effect in response
    }

    @Test
    void healthLive_returnsOk() throws Exception {
        HttpResponse response = get("/api/health/live");
        assertThat(response.status().intValue()).isEqualTo(200);
    }

    @Test
    void healthReady_returnsOkWhenDataAvailable() throws Exception {
        HttpResponse response = get("/api/health/ready");
        assertThat(response.status().intValue()).isEqualTo(200);

        String body = getBody(response);
        JsonNode json = objectMapper.readTree(body);
        assertThat(json.get("status").asText()).isEqualTo("ready");
        assertThat(json.has("dataAgeMs")).isTrue();
        assertThat(json.has("circuit")).isTrue();
    }

    @Test
    void metricsEndpoint_returnsPrometheusFormat() throws Exception {
        // Make a few requests first to generate metrics
        get("/api/v1/tube/status");
        get("/api/health/live");

        HttpResponse response = get("/metrics");
        assertThat(response.status().intValue()).isEqualTo(200);

        String body = getBody(response);

        // Verify Prometheus format
        assertThat(body).contains("# HELP");
        assertThat(body).contains("# TYPE");

        // Verify our custom metrics are present
        assertThat(body).contains("http_requests_total");
        assertThat(body).contains("http_request_duration_seconds");
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

    private static TubeStatus createSampleStatus() {
        return new TubeStatus(
                List.of(
                        new TubeStatus.LineStatus(
                                "victoria",
                                "Victoria",
                                "Good Service",
                                "Good Service",
                                List.of()
                        ),
                        new TubeStatus.LineStatus(
                                "central",
                                "Central",
                                "Minor Delays",
                                "Minor Delays",
                                List.of(new TubeStatus.Disruption(
                                        "Signal Failure",
                                        "Minor delays due to signal failure at Bank",
                                        false  // unplanned
                                ))
                        ),
                        new TubeStatus.LineStatus(
                                "northern",
                                "Northern",
                                "Part Closure",
                                "Part Closure",
                                List.of(new TubeStatus.Disruption(
                                        "Planned Works",
                                        "No service between High Barnet and Finchley Central",
                                        true  // planned
                                ))
                        )
                ),
                Instant.now(),
                "test-node"
        );
    }

    /**
     * Stub TflGateway actor for route testing.
     */
    static class StubTflGateway extends AbstractBehavior<TflGateway.Command> {
        private final Supplier<TubeStatus> statusSupplier;

        static Behavior<TflGateway.Command> create(Supplier<TubeStatus> statusSupplier) {
            return Behaviors.setup(ctx -> new StubTflGateway(ctx, statusSupplier));
        }

        private StubTflGateway(ActorContext<TflGateway.Command> context,
                               Supplier<TubeStatus> statusSupplier) {
            super(context);
            this.statusSupplier = statusSupplier;
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
            TubeStatus status = statusSupplier.get();
            // Return fresh status with current timestamp
            TubeStatus fresh = new TubeStatus(
                    status.lines(),
                    Instant.now(),
                    status.queriedBy()
            );
            msg.replyTo().tell(new TflGateway.FetchResponse(fresh, null));
            return this;
        }

        private Behavior<TflGateway.Command> onFetchLineWithDateRange(TflGateway.FetchLineWithDateRange msg) {
            TubeStatus status = statusSupplier.get();
            var filtered = status.lines().stream()
                    .filter(line -> line.id().equalsIgnoreCase(msg.lineId()))
                    .toList();
            if (filtered.isEmpty()) {
                msg.replyTo().tell(new TflGateway.FetchResponse(
                        new TubeStatus(List.of(), Instant.now(), status.queriedBy()), null));
            } else {
                msg.replyTo().tell(new TflGateway.FetchResponse(
                        new TubeStatus(filtered, Instant.now(), status.queriedBy()), null));
            }
            return this;
        }

        private Behavior<TflGateway.Command> onGetCircuitState(TflGateway.GetCircuitState msg) {
            msg.replyTo().tell(new TflGateway.CircuitStateResponse(TflGateway.CircuitState.CLOSED));
            return this;
        }
    }
}
