package com.ig.tfl.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.ig.tfl.client.TflGateway;
import com.ig.tfl.crdt.TubeStatusReplicator;
import com.ig.tfl.crdt.TubeStatusReplicator.GetStatus;
import com.ig.tfl.crdt.TubeStatusReplicator.GetStatusWithFreshness;
import com.ig.tfl.crdt.TubeStatusReplicator.StatusResponse;
import com.ig.tfl.model.TubeStatus;
import com.ig.tfl.observability.Metrics;
import com.ig.tfl.resilience.RateLimiter;
import org.apache.pekko.actor.typed.ActorRef;
import org.apache.pekko.actor.typed.ActorSystem;
import org.apache.pekko.actor.typed.javadsl.AskPattern;
import org.apache.pekko.http.javadsl.marshallers.jackson.Jackson;
import org.apache.pekko.http.javadsl.model.StatusCodes;
import org.apache.pekko.http.javadsl.model.headers.RawHeader;
import org.apache.pekko.http.javadsl.server.AllDirectives;
import org.apache.pekko.http.javadsl.server.ExceptionHandler;
import org.apache.pekko.http.javadsl.server.PathMatchers;
import org.apache.pekko.http.javadsl.server.RejectionHandler;
import org.apache.pekko.http.javadsl.server.Route;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletionStage;
import java.util.function.Supplier;

/**
 * HTTP routes for tube status API.
 *
 * Thin layer: delegates caching decisions to TubeStatusReplicator,
 * date-range queries to TflGateway.
 */
public class TubeStatusRoutes extends AllDirectives {
    private static final Logger log = LoggerFactory.getLogger(TubeStatusRoutes.class);

    private final ActorSystem<?> system;
    private final ActorRef<TubeStatusReplicator.Command> replicator;
    private final ActorRef<TflGateway.Command> tflGateway;
    private final RateLimiter rateLimiter;
    private final ObjectMapper objectMapper;
    private final Duration askTimeout;
    private final Metrics metrics;

    public TubeStatusRoutes(
            ActorSystem<?> system,
            ActorRef<TubeStatusReplicator.Command> replicator,
            ActorRef<TflGateway.Command> tflGateway,
            Metrics metrics) {
        this(system, replicator, tflGateway, metrics,
                system.settings().config().getInt("tfl.rate-limit.requests-per-minute"),
                system.settings().config().getDuration("tfl.http.ask-timeout"));
    }

    public TubeStatusRoutes(
            ActorSystem<?> system,
            ActorRef<TubeStatusReplicator.Command> replicator,
            ActorRef<TflGateway.Command> tflGateway,
            Metrics metrics,
            int requestsPerMinute,
            Duration askTimeout) {
        this.system = system;
        this.replicator = replicator;
        this.tflGateway = tflGateway;
        this.metrics = metrics;
        this.rateLimiter = RateLimiter.perMinute(requestsPerMinute);
        this.askTimeout = askTimeout;
        this.objectMapper = new ObjectMapper()
                .registerModule(new JavaTimeModule());
    }

    /** Defines all HTTP routes for the tube status API. */
    public Route routes() {
        return handleExceptions(exceptionHandler(),
                () -> handleRejections(rejectionHandler(),
                        () -> concat(
                                // Metrics endpoint (no rate limiting, no auth)
                                path("metrics", () ->
                                        get(() -> complete(StatusCodes.OK, metrics.scrape()))
                                ),
                                // API routes with rate limiting and metrics
                                extractClientIP(remoteAddress -> {
                                    String clientIp = remoteAddress.getAddress()
                                            .map(addr -> addr.getHostAddress())
                                            .orElse("unknown");
                                    return rateLimitCheck(clientIp, () ->
                                            pathPrefix("api", () ->
                                                    extractRequest(request ->
                                                            withTimedMetrics(request.method().value(),
                                                                    request.getUri().path(),
                                                                    () -> concat(
                                                                            statusRoutes(),
                                                                            healthRoutes()
                                                                    ))
                                                    )
                                            )
                                    );
                                })
                        )
                )
        );
    }

    /**
     * Wrap route to record request metrics.
     */
    private Route withTimedMetrics(String method, String path, Supplier<Route> inner) {
        long startTime = System.nanoTime();
        return mapResponse(response -> {
            long durationNanos = System.nanoTime() - startTime;
            Duration duration = Duration.ofNanos(durationNanos);
            metrics.recordRequest(method, path, response.status().intValue(), duration);
            return response;
        }, inner);
    }

    private Route statusRoutes() {
        return pathPrefix("v1", () ->
                pathPrefix("tube", () ->
                        concat(
                                // GET /api/v1/tube/status?maxAgeMs=60000
                                path("status", () ->
                                        get(() -> parameterOptional("maxAgeMs", maxAgeStr -> {
                                            Optional<Long> maxAgeMs = maxAgeStr.map(s -> {
                                                try {
                                                    return Long.parseLong(s);
                                                } catch (NumberFormatException e) {
                                                    return null;
                                                }
                                            });
                                            return getAllStatus(maxAgeMs.orElse(null));
                                        }))
                                ),
                                // GET /api/v1/tube/disruptions
                                path("disruptions", () ->
                                        get(this::getDisruptions)
                                ),
                                // GET /api/v1/tube/{lineId}/status
                                pathPrefix(PathMatchers.segment(), lineId ->
                                        concat(
                                                path("status", () ->
                                                        get(() -> getLineStatus(lineId))
                                                ),
                                                // GET /api/v1/tube/{lineId}/status/{from}/to/{to}
                                                pathPrefix("status", () ->
                                                        path(PathMatchers.segment().slash("to").slash(PathMatchers.segment()), (from, to) ->
                                                                get(() -> getLineStatusWithDateRange(lineId, from, to))
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
                                get(this::readinessCheck)
                        )
                )
        );
    }

    private Route readinessCheck() {
        // Ask replicator for status and gateway for circuit state in parallel
        CompletionStage<StatusResponse> statusFuture = AskPattern.ask(
                replicator,
                GetStatus::new,
                askTimeout,
                system.scheduler());

        CompletionStage<TflGateway.CircuitStateResponse> circuitFuture = AskPattern.ask(
                tflGateway,
                TflGateway.GetCircuitState::new,
                askTimeout,
                system.scheduler());

        return onSuccess(statusFuture.thenCombine(circuitFuture, (status, circuit) ->
                Map.of("status", status, "circuit", circuit)
        ), combined -> {
            @SuppressWarnings("unchecked")
            var statusResp = (StatusResponse) combined.get("status");
            @SuppressWarnings("unchecked")
            var circuitResp = (TflGateway.CircuitStateResponse) combined.get("circuit");

            boolean hasData = statusResp.status() != null;

            if (!hasData) {
                return complete(StatusCodes.SERVICE_UNAVAILABLE,
                        Map.of(
                                "status", "warming_up",
                                "reason", "No cached data yet",
                                "circuit", circuitResp.state().name()
                        ),
                        Jackson.marshaller(objectMapper));
            }

            return complete(StatusCodes.OK,
                    Map.of(
                            "status", "ready",
                            "circuit", circuitResp.state().name(),
                            "dataAgeMs", statusResp.status().ageMs()
                    ),
                    Jackson.marshaller(objectMapper));
        });
    }

    private Route getAllStatus(Long maxAgeMs) {
        // Delegate freshness decision to Replicator
        CompletionStage<StatusResponse> future = AskPattern.ask(
                replicator,
                ref -> new GetStatusWithFreshness(maxAgeMs, ref),
                askTimeout,
                system.scheduler());

        return onSuccess(future, response -> {
            TubeStatus status = response.status();

            if (status == null) {
                return complete(StatusCodes.SERVICE_UNAVAILABLE,
                        Map.of("error", "No data available"),
                        Jackson.marshaller(objectMapper));
            }

            // Record data freshness metric
            metrics.updateDataFreshness(status.ageMs());

            Route result = complete(StatusCodes.OK,
                    toApiResponse(status),
                    Jackson.marshaller(objectMapper));

            // Add staleness headers if data is older than requested
            if (response.isStale()) {
                return respondWithHeader(RawHeader.create("X-Data-Stale", "true"), () ->
                        respondWithHeader(RawHeader.create("X-Requested-Max-Age-Ms",
                                String.valueOf(response.requestedMaxAgeMs())), () ->
                                respondWithHeader(RawHeader.create("X-Actual-Age-Ms",
                                        String.valueOf(status.ageMs())), () -> result)));
            }

            return result;
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

    private Route getLineStatus(String lineId) {
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
            var lineStatus = response.status().lines().stream()
                    .filter(line -> line.id().equalsIgnoreCase(lineId))
                    .findFirst();

            if (lineStatus.isEmpty()) {
                return complete(StatusCodes.NOT_FOUND,
                        Map.of("error", "Line not found: " + lineId),
                        Jackson.marshaller(objectMapper));
            }

            var filtered = new TubeStatus(
                    java.util.List.of(lineStatus.get()),
                    response.status().queriedAt(),
                    response.status().queriedBy());
            return complete(StatusCodes.OK,
                    toApiResponse(filtered),
                    Jackson.marshaller(objectMapper));
        });
    }

    private Route getLineStatusWithDateRange(String lineId, String fromStr, String toStr) {
        LocalDate from;
        LocalDate to;
        try {
            from = LocalDate.parse(fromStr, DateTimeFormatter.ISO_LOCAL_DATE);
            to = LocalDate.parse(toStr, DateTimeFormatter.ISO_LOCAL_DATE);
        } catch (DateTimeParseException e) {
            return complete(StatusCodes.BAD_REQUEST,
                    Map.of("error", "Invalid date format. Use YYYY-MM-DD"),
                    Jackson.marshaller(objectMapper));
        }

        if (from.isAfter(to)) {
            return complete(StatusCodes.BAD_REQUEST,
                    Map.of("error", "Start date must be before or equal to end date"),
                    Jackson.marshaller(objectMapper));
        }

        // Date range queries bypass cache - ask TflGateway directly
        CompletionStage<TflGateway.FetchResponse> future = AskPattern.ask(
                tflGateway,
                ref -> new TflGateway.FetchLineWithDateRange(lineId, from, to, ref),
                askTimeout,
                system.scheduler());

        return onSuccess(future, response -> {
            if (response.error() != null) {
                log.warn("TfL fetch failed for date range query: {}", response.error().getMessage());
                return complete(StatusCodes.SERVICE_UNAVAILABLE,
                        Map.of("error", "Failed to fetch from TfL"),
                        Jackson.marshaller(objectMapper));
            }

            if (response.status() == null || response.status().lines().isEmpty()) {
                return complete(StatusCodes.NOT_FOUND,
                        Map.of("error", "No status found for line: " + lineId),
                        Jackson.marshaller(objectMapper));
            }
            return complete(StatusCodes.OK,
                    toApiResponse(response.status()),
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
