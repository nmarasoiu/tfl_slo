package com.ig.tfl.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.ig.tfl.crdt.TubeStatusReplicator;
import com.ig.tfl.crdt.TubeStatusReplicator.GetStatus;
import com.ig.tfl.crdt.TubeStatusReplicator.StatusResponse;
import com.ig.tfl.model.TubeStatus;
import com.ig.tfl.resilience.CircuitBreaker;
import com.ig.tfl.resilience.RateLimiter;
import org.apache.pekko.actor.typed.ActorRef;
import org.apache.pekko.actor.typed.ActorSystem;
import org.apache.pekko.actor.typed.javadsl.AskPattern;
import org.apache.pekko.http.javadsl.marshallers.jackson.Jackson;
import org.apache.pekko.http.javadsl.model.StatusCodes;
import org.apache.pekko.http.javadsl.model.headers.RawHeader;
import org.apache.pekko.http.javadsl.server.AllDirectives;
import org.apache.pekko.http.javadsl.server.ExceptionHandler;
import org.apache.pekko.http.javadsl.server.RejectionHandler;
import org.apache.pekko.http.javadsl.server.Route;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CompletionStage;
import java.util.function.Supplier;

/**
 * HTTP routes for tube status API.
 *
 * All queries go through CRDT for consistent caching.
 */
public class TubeStatusRoutes extends AllDirectives {
    private static final Logger log = LoggerFactory.getLogger(TubeStatusRoutes.class);

    private final ActorSystem<?> system;
    private final ActorRef<TubeStatusReplicator.Command> replicator;
    private final RateLimiter rateLimiter;
    private final ObjectMapper objectMapper;
    private final Supplier<CircuitBreaker.State> circuitStateSupplier;
    private final Duration askTimeout = Duration.ofSeconds(5);

    public TubeStatusRoutes(
            ActorSystem<?> system,
            ActorRef<TubeStatusReplicator.Command> replicator,
            Supplier<CircuitBreaker.State> circuitStateSupplier) {
        this.system = system;
        this.replicator = replicator;
        this.circuitStateSupplier = circuitStateSupplier;
        this.rateLimiter = RateLimiter.perMinute(100);
        this.objectMapper = new ObjectMapper()
                .registerModule(new JavaTimeModule());
    }

    public Route routes() {
        return handleExceptions(exceptionHandler(),
                () -> handleRejections(rejectionHandler(),
                        () -> extractClientIP(remoteAddress -> {
                            String clientIp = remoteAddress.getAddress()
                                    .map(addr -> addr.getHostAddress())
                                    .orElse("unknown");
                            return rateLimitCheck(clientIp, () ->
                                    pathPrefix("api", () ->
                                            concat(
                                                    statusRoutes(),
                                                    healthRoutes()
                                            )
                                    )
                            );
                        })
                )
        );
    }

    private Route statusRoutes() {
        return pathPrefix("v1", () ->
                pathPrefix("tube", () ->
                        concat(
                                // GET /api/v1/tube/status - all lines (from CRDT)
                                path("status", () ->
                                        get(this::getAllStatus)
                                ),
                                // GET /api/v1/tube/disruptions - filter from CRDT
                                path("disruptions", () ->
                                        get(this::getDisruptions)
                                )
                        )
                )
        );
    }

    private Route healthRoutes() {
        return pathPrefix("health", () ->
                concat(
                        path("live", () ->
                                get(() -> complete(StatusCodes.OK, "OK"))
                        ),
                        path("ready", () ->
                                get(() -> {
                                    var circuitState = circuitStateSupplier.get();
                                    if (circuitState == CircuitBreaker.State.OPEN) {
                                        return complete(StatusCodes.SERVICE_UNAVAILABLE,
                                                Map.of("status", "degraded", "circuit", "OPEN"),
                                                Jackson.marshaller(objectMapper));
                                    }
                                    return complete(StatusCodes.OK,
                                            Map.of("status", "ready", "circuit", circuitState.name()),
                                            Jackson.marshaller(objectMapper));
                                })
                        )
                )
        );
    }

    private Route getAllStatus() {
        CompletionStage<StatusResponse> future = AskPattern.ask(
                replicator,
                GetStatus::new,
                askTimeout,
                system.scheduler());

        return onSuccess(future, response -> {
            if (response.status() == null) {
                return complete(StatusCodes.SERVICE_UNAVAILABLE,
                        Map.of("error", "No data available"),
                        Jackson.marshaller(objectMapper));
            }
            return complete(StatusCodes.OK,
                    toApiResponse(response.status()),
                    Jackson.marshaller(objectMapper));
        });
    }

    private Route getDisruptions() {
        CompletionStage<StatusResponse> future = AskPattern.ask(
                replicator,
                GetStatus::new,
                askTimeout,
                system.scheduler());

        return onSuccess(future, response -> {
            if (response.status() == null) {
                return complete(StatusCodes.SERVICE_UNAVAILABLE,
                        Map.of("error", "No data available"),
                        Jackson.marshaller(objectMapper));
            }
            // Filter to only lines with unplanned disruptions
            var disrupted = response.status().lines().stream()
                    .filter(line -> line.disruptions() != null &&
                            line.disruptions().stream().anyMatch(d -> !d.isPlanned()))
                    .toList();
            var filtered = new TubeStatus(disrupted,
                    response.status().queriedAt(),
                    response.status().queriedBy());
            return complete(StatusCodes.OK,
                    toApiResponse(filtered),
                    Jackson.marshaller(objectMapper));
        });
    }

    private Route rateLimitCheck(String clientIp, Supplier<Route> inner) {
        var result = rateLimiter.tryAcquire(clientIp);
        if (!result.allowed()) {
            return respondWithHeader(
                    RawHeader.create("Retry-After", String.valueOf(result.retryAfter().toSeconds())),
                    () -> complete(StatusCodes.TOO_MANY_REQUESTS,
                            Map.of(
                                    "error", "Rate limit exceeded",
                                    "retryAfterSeconds", result.retryAfter().toSeconds()
                            ),
                            Jackson.marshaller(objectMapper))
            );
        }
        return respondWithHeader(
                RawHeader.create("X-RateLimit-Remaining", String.valueOf(result.remainingTokens())),
                inner
        );
    }

    private ApiResponse toApiResponse(TubeStatus status) {
        return new ApiResponse(
                status.lines(),
                new ApiResponse.Meta(
                        status.queriedAt(),
                        status.queriedBy(),
                        status.ageMs()
                )
        );
    }

    private ExceptionHandler exceptionHandler() {
        return ExceptionHandler.newBuilder()
                .match(RateLimiter.RateLimitExceededException.class, e ->
                        complete(StatusCodes.TOO_MANY_REQUESTS,
                                Map.of("error", "Rate limit exceeded"),
                                Jackson.marshaller(objectMapper)))
                .match(Exception.class, e -> {
                    log.error("Unhandled exception", e);
                    return complete(StatusCodes.INTERNAL_SERVER_ERROR,
                            Map.of("error", "Internal server error"),
                            Jackson.marshaller(objectMapper));
                })
                .build();
    }

    private RejectionHandler rejectionHandler() {
        return RejectionHandler.defaultHandler();
    }

    public record ApiResponse(
            java.util.List<TubeStatus.LineStatus> lines,
            Meta meta
    ) {
        public record Meta(
                java.time.Instant queriedAt,
                String queriedBy,
                long ageMs
        ) {}
    }
}
