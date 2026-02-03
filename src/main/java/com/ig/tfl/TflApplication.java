package com.ig.tfl;

import com.ig.tfl.api.TubeStatusRoutes;
import com.ig.tfl.client.TflApiClient;
import com.ig.tfl.client.TflGateway;
import com.ig.tfl.crdt.TubeStatusReplicator;
import com.ig.tfl.observability.Metrics;
import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;
import org.apache.pekko.actor.typed.ActorRef;
import org.apache.pekko.actor.typed.ActorSystem;
import org.apache.pekko.actor.typed.Behavior;
import org.apache.pekko.actor.typed.javadsl.AbstractBehavior;
import org.apache.pekko.actor.typed.javadsl.ActorContext;
import org.apache.pekko.actor.typed.javadsl.Behaviors;
import org.apache.pekko.actor.typed.javadsl.Receive;
import org.apache.pekko.http.javadsl.Http;
import org.apache.pekko.http.javadsl.ServerBinding;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.concurrent.CompletionStage;

/**
 * Main application entry point.
 *
 * Starts:
 * - Pekko Actor System with cluster support
 * - TflGateway actor (TfL API communication)
 * - CRDT-replicated tube status actor
 * - HTTP server for REST API
 */
public class TflApplication {
    private static final Logger log = LoggerFactory.getLogger(TflApplication.class);

    public static void main(String[] args) {
        // Load configuration
        Config config = ConfigFactory.load();
        String nodeId = config.getString("tfl.node-id");
        int httpPort = config.getInt("tfl.http.port");

        // Create actor system with guardian
        ActorSystem<GuardianActor.Command> system = ActorSystem.create(
                GuardianActor.create(nodeId, httpPort),
                "tfl-cluster",
                config);

        log.info("TfL Status Service started on node {} at port {}", nodeId, httpPort);
    }

    /**
     * Guardian actor that manages the application lifecycle.
     */
    public static class GuardianActor extends AbstractBehavior<GuardianActor.Command> {

        public sealed interface Command {}
        public record StartHttpServer() implements Command {}

        private final String nodeId;
        private final int httpPort;
        private final ActorRef<TflGateway.Command> tflGateway;
        private final ActorRef<TubeStatusReplicator.Command> replicator;
        private final Metrics metrics;

        public static Behavior<Command> create(String nodeId, int httpPort) {
            return Behaviors.setup(context -> new GuardianActor(context, nodeId, httpPort));
        }

        private GuardianActor(ActorContext<Command> context, String nodeId, int httpPort) {
            super(context);
            this.nodeId = nodeId;
            this.httpPort = httpPort;

            Config config = context.getSystem().settings().config();

            // Create metrics registry
            this.metrics = new Metrics();

            // Create resilience config from application config
            int cbFailureThreshold = config.getInt("tfl.circuit-breaker.failure-threshold");
            Duration cbOpenDuration = config.getDuration("tfl.circuit-breaker.open-duration");
            Duration cbCallTimeout = config.getDuration("tfl.http.response-timeout");
            int retryMaxRetries = config.getInt("tfl.retry.max-retries");
            Duration retryBaseDelay = config.getDuration("tfl.retry.base-delay");

            TflApiClient.ResilienceConfig resilienceConfig = new TflApiClient.ResilienceConfig(
                    cbFailureThreshold,
                    cbCallTimeout,
                    cbOpenDuration,
                    retryMaxRetries,
                    retryBaseDelay
            );

            // Create TfL API client with Pekko's built-in resilience patterns
            Duration responseTimeout = config.getDuration("tfl.http.response-timeout");
            TflApiClient tflApiClient = new TflApiClient(
                    context.getSystem(), nodeId, "https://api.tfl.gov.uk",
                    resilienceConfig, responseTimeout, new com.ig.tfl.observability.Tracing());

            // Register circuit breaker metric (using the client's circuit breaker)
            metrics.registerCircuitBreaker("tfl-api", () -> {
                if (tflApiClient.isCircuitOpen()) return "OPEN";
                if (tflApiClient.isCircuitHalfOpen()) return "HALF_OPEN";
                return "CLOSED";
            });

            // Register data freshness metric
            metrics.registerDataFreshness(nodeId);

            // Create TflGateway actor - single point of contact for TfL API
            this.tflGateway = context.spawn(
                    TflGateway.create(tflApiClient),
                    "tfl-gateway");

            // Create CRDT replicator actor - uses TflGateway for fetches
            Duration refreshInterval = config.getDuration("tfl.refresh.interval");
            Duration recentEnoughThreshold = config.getDuration("tfl.refresh.recent-enough-threshold");
            Duration backgroundRefreshThreshold = config.getDuration("tfl.refresh.background-refresh-threshold");
            this.replicator = context.spawn(
                    TubeStatusReplicator.create(
                            tflGateway,
                            nodeId,
                            refreshInterval,
                            recentEnoughThreshold,
                            backgroundRefreshThreshold
                    ),
                    "tube-status-replicator");

            // Start HTTP server
            startHttpServer(context.getSystem());
        }

        @Override
        public Receive<Command> createReceive() {
            return newReceiveBuilder()
                    .onMessage(StartHttpServer.class, this::onStartHttpServer)
                    .build();
        }

        private Behavior<Command> onStartHttpServer(StartHttpServer msg) {
            return this;
        }

        private void startHttpServer(ActorSystem<?> system) {
            // Routes talk to Replicator (for cached status) and TflGateway (for date-range queries)
            TubeStatusRoutes routes = new TubeStatusRoutes(system, replicator, tflGateway, metrics);

            CompletionStage<ServerBinding> binding = Http.get(system)
                    .newServerAt("0.0.0.0", httpPort)
                    .bind(routes.routes());

            binding.whenComplete((serverBinding, error) -> {
                if (error != null) {
                    log.error("Failed to bind HTTP server", error);
                    system.terminate();
                } else {
                    log.info("HTTP server bound to {}", serverBinding.localAddress());

                    // Shutdown hook
                    Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                        log.info("Shutting down...");
                        serverBinding.unbind();
                        system.terminate();
                    }));
                }
            });
        }
    }
}
