package com.ig.tfl.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.ig.tfl.client.TflClient;
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
import org.apache.pekko.http.javadsl.server.PathMatchers;
import org.apache.pekko.http.javadsl.server.RejectionHandler;
import org.apache.pekko.http.javadsl.server.Route;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import scala.util.Try;

import java.time.Duration;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
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
    private final TflClient tflClient;
    private final RateLimiter rateLimiter;
    private final ObjectMapper objectMapper;
    private final Supplier<CircuitBreaker.State> circuitStateSupplier;
    private final Duration askTimeout = Duration.ofSeconds(5);

    public TubeStatusRoutes(
            ActorSystem<?> system,
            ActorRef<TubeStatusReplicator.Command> replicator,
            TflClient tflClient,
            Supplier<CircuitBreaker.State> circuitStateSupplier) {
        this.system = system;
        this.replicator = replicator;
        this.tflClient = tflClient;
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
                                // GET /api/v1/tube/status?maxAgeMs=60000 - all lines with freshness requirement
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
                                // GET /api/v1/tube/disruptions - filter from CRDT
                                path("disruptions", () ->
                                        get(this::getDisruptions)
                                ),
                                // GET /api/v1/tube/{lineId}/status - single line (from CRDT cache)
                                pathPrefix(PathMatchers.segment(), lineId ->
                                        concat(
                                                // GET /api/v1/tube/{lineId}/status
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
                                get(() -> {
                                    // Ready = we have data to serve (even if stale)
                                    // Circuit state is informational, not a readiness gate
                                    CompletionStage<StatusResponse> future = AskPattern.ask(
                                            replicator,
                                            GetStatus::new,
                                            askTimeout,
                                            system.scheduler());

                                    return onSuccess(future, response -> {
                                        var circuitState = circuitStateSupplier.get();
                                        boolean hasData = response.status() != null;

                                        if (!hasData) {
                                            // Cold start - no data yet
                                            return complete(StatusCodes.SERVICE_UNAVAILABLE,
                                                    Map.of(
                                                            "status", "warming_up",
                                                            "reason", "No cached data yet",
                                                            "circuit", circuitState.name()
                                                    ),
                                                    Jackson.marshaller(objectMapper));
                                        }

                                        // Ready - we have data (stale or fresh, doesn't matter)
                                        return complete(StatusCodes.OK,
                                                Map.of(
                                                        "status", "ready",
                                                        "circuit", circuitState.name(),
                                                        "dataAgeMs", response.status().ageMs()
                                                ),
                                                Jackson.marshaller(objectMapper));
                                    });
                                })
                        )
                )
        );
    }

    private Route getAllStatus(Long maxAgeMs) {
        CompletionStage<StatusResponse> cacheFuture = AskPattern.ask(
                replicator,
                GetStatus::new,
                askTimeout,
                system.scheduler());

        return onSuccess(cacheFuture, cacheResponse -> {
            TubeStatus cached = cacheResponse.status();

            // No data at all
            if (cached == null) {
                return complete(StatusCodes.SERVICE_UNAVAILABLE,
                        Map.of("error", "No data available"),
                        Jackson.marshaller(objectMapper));
            }

            // No freshness requirement or cache is fresh enough
            if (maxAgeMs == null || cached.ageMs() <= maxAgeMs) {
                return complete(StatusCodes.OK,
                        toApiResponse(cached),
                        Jackson.marshaller(objectMapper));
            }

            // Cache too stale - try to fetch fresh from TfL
            log.info("Cache is {}ms old, client wants max {}ms - attempting TfL fetch",
                    cached.ageMs(), maxAgeMs);

            CompletionStage<TubeStatus> tflFuture = tflClient.fetchAllLinesAsync();

            return onComplete(tflFuture, tryResult -> {
                if (tryResult.isSuccess() && tryResult.get() != null) {
                    // Got fresh data from TfL
                    TubeStatus fresh = tryResult.get();
                    return complete(StatusCodes.OK,
                            toApiResponse(fresh),
                            Jackson.marshaller(objectMapper));
                } else {
                    // TfL failed - return stale with warning header
                    log.warn("TfL fetch failed, returning stale data: {}",
                            tryResult.isFailure() ? tryResult.failed().get().getMessage() : "null response");
                    return respondWithHeader(
                            RawHeader.create("X-Data-Stale", "true"),
                            () -> respondWithHeader(
                                    RawHeader.create("X-Requested-Max-Age-Ms", String.valueOf(maxAgeMs)),
                                    () -> respondWithHeader(
                                            RawHeader.create("X-Actual-Age-Ms", String.valueOf(cached.ageMs())),
                                            () -> complete(StatusCodes.OK,
                                                    toApiResponse(cached),
                                                    Jackson.marshaller(objectMapper))
                                    )
                            )
                    );
                }
            });
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
            // Filter to the requested line
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
        // Parse dates
        LocalDate from, to;
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

        // Date range queries go direct to TfL (can't cache future data)
        CompletionStage<TubeStatus> future = tflClient.fetchLineStatusAsync(lineId, from, to);

        return onSuccess(future, status -> {
            if (status == null || status.lines().isEmpty()) {
                return complete(StatusCodes.NOT_FOUND,
                        Map.of("error", "No status found for line: " + lineId),
                        Jackson.marshaller(objectMapper));
            }
            return complete(StatusCodes.OK,
                    toApiResponse(status),
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
