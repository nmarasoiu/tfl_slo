package com.ig.tfl;

import com.ig.tfl.api.TubeStatusRoutes;
import com.ig.tfl.client.TflApiClient;
import com.ig.tfl.crdt.TubeStatusReplicator;
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
        private final TflApiClient tflClient;
        private final ActorRef<TubeStatusReplicator.Command> replicator;

        public static Behavior<Command> create(String nodeId, int httpPort) {
            return Behaviors.setup(context -> new GuardianActor(context, nodeId, httpPort));
        }

        private GuardianActor(ActorContext<Command> context, String nodeId, int httpPort) {
            super(context);
            this.nodeId = nodeId;
            this.httpPort = httpPort;

            // Create TfL client (needs ActorSystem for Pekko HTTP)
            this.tflClient = new TflApiClient(context.getSystem(), nodeId);

            // Create CRDT replicator actor
            this.replicator = context.spawn(
                    TubeStatusReplicator.create(
                            tflClient,
                            nodeId,
                            Duration.ofSeconds(30),  // refresh interval
                            Duration.ofSeconds(5)    // recent enough threshold
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
            TubeStatusRoutes routes = new TubeStatusRoutes(system, replicator, tflClient);

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
