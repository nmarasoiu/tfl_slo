package com.ig.tfl.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.ig.tfl.client.TflApiClient;
import com.ig.tfl.crdt.TubeStatusReplicator;
import com.ig.tfl.crdt.TubeStatusReplicator.GetStatus;
import com.ig.tfl.crdt.TubeStatusReplicator.StatusResponse;
import com.ig.tfl.model.TubeStatus;
import com.ig.tfl.resilience.RateLimiter;
import org.apache.pekko.actor.typed.ActorRef;
import org.apache.pekko.actor.typed.ActorSystem;
import org.apache.pekko.actor.typed.javadsl.AskPattern;
import org.apache.pekko.http.javadsl.marshallers.jackson.Jackson;
import org.apache.pekko.http.javadsl.model.HttpResponse;
import org.apache.pekko.http.javadsl.model.StatusCodes;
import org.apache.pekko.http.javadsl.model.headers.RawHeader;
import org.apache.pekko.http.javadsl.server.AllDirectives;
import org.apache.pekko.http.javadsl.server.ExceptionHandler;
import org.apache.pekko.http.javadsl.server.RejectionHandler;
import org.apache.pekko.http.javadsl.server.Route;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletionStage;

import static org.apache.pekko.http.javadsl.server.PathMatchers.*;

/**
 * HTTP routes for tube status API.
 */
public class TubeStatusRoutes extends AllDirectives {
    private static final Logger log = LoggerFactory.getLogger(TubeStatusRoutes.class);

    private final ActorSystem<?> system;
    private final ActorRef<TubeStatusReplicator.Command> replicator;
    private final TflApiClient tflClient;
    private final RateLimiter rateLimiter;
    private final ObjectMapper objectMapper;
    private final Duration askTimeout = Duration.ofSeconds(5);

    public TubeStatusRoutes(
            ActorSystem<?> system,
            ActorRef<TubeStatusReplicator.Command> replicator,
            TflApiClient tflClient) {
        this.system = system;
        this.replicator = replicator;
        this.tflClient = tflClient;
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
                                // GET /api/v1/tube/status - all lines
                                path("status", () ->
                                        get(() -> getAllStatus())
                                ),
                                // GET /api/v1/tube/disruptions - unplanned only
                                path("disruptions", () ->
                                        get(() -> getUnplannedDisruptions())
                                ),
                                // GET /api/v1/tube/{lineId}/status - specific line
                                pathPrefix(segment(), lineId ->
                                        concat(
                                                path("status", () ->
                                                        get(() -> getLineStatus(lineId))
                                                ),
                                                // GET /api/v1/tube/{lineId}/status/{from}/to/{to}
                                                pathPrefix("status", () ->
                                                        path(segment().slash("to").slash(segment()), (from, to) ->
                                                                get(() -> getLineStatusWithRange(lineId, from, to))
                                                        )
                                                )
                                        )
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
                                    var circuitState = tflClient.getCircuitState();
                                    if (circuitState == com.ig.tfl.resilience.CircuitBreaker.State.OPEN) {
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

    private Route getLineStatus(String lineId) {
        return onSuccess(tflClient.fetchLineAsync(lineId), status ->
                complete(StatusCodes.OK,
                        toApiResponse(status),
                        Jackson.marshaller(objectMapper)));
    }

    private Route getLineStatusWithRange(String lineId, String from, String to) {
        LocalDate fromDate = LocalDate.parse(from, DateTimeFormatter.ISO_LOCAL_DATE);
        LocalDate toDate = LocalDate.parse(to, DateTimeFormatter.ISO_LOCAL_DATE);
        return onSuccess(tflClient.fetchLineWithDateRangeAsync(lineId, fromDate, toDate), status ->
                complete(StatusCodes.OK,
                        toApiResponse(status),
                        Jackson.marshaller(objectMapper)));
    }

    private Route getUnplannedDisruptions() {
        return onSuccess(tflClient.fetchUnplannedDisruptionsAsync(), status ->
                complete(StatusCodes.OK,
                        toApiResponse(status),
                        Jackson.marshaller(objectMapper)));
    }

    private Route handleTflError(Exception e) {
        log.error("TfL API error: {}", e.getMessage());
        return complete(StatusCodes.BAD_GATEWAY,
                Map.of("error", "Upstream service unavailable", "message", e.getMessage()),
                Jackson.marshaller(objectMapper));
    }

    private Route rateLimitCheck(String clientIp, java.util.function.Supplier<Route> inner) {
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
                        status.tflTimestamp(),
                        status.fetchedAt(),
                        status.fetchedBy(),
                        status.freshnessMs(),
                        status.source().name(),
                        status.confidence().name()
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

    // API response wrapper
    public record ApiResponse(
            java.util.List<TubeStatus.LineStatus> lines,
            Meta meta
    ) {
        public record Meta(
                java.time.Instant dataAsOf,
                java.time.Instant fetchedAt,
                String fetchedBy,
                long freshnessMs,
                String source,
                String confidence
        ) {}
    }
}
