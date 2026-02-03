package com.ig.tfl.client;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.ig.tfl.model.TflApiResponse;
import com.ig.tfl.model.TubeStatus;
import com.ig.tfl.model.TubeStatus.LineStatus;
import com.ig.tfl.observability.Tracing;
import org.apache.pekko.actor.typed.ActorSystem;
import org.apache.pekko.http.javadsl.Http;
import org.apache.pekko.http.javadsl.model.HttpRequest;
import org.apache.pekko.http.javadsl.model.HttpResponse;
import org.apache.pekko.pattern.CircuitBreaker;
import org.apache.pekko.stream.Materializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

import static org.apache.pekko.pattern.Patterns.retry;

/**
 * Client for TfL API using Pekko HTTP (non-blocking).
 *
 * Uses Pekko's built-in resilience patterns:
 * - CircuitBreaker from org.apache.pekko.pattern
 * - Retry from org.apache.pekko.pattern.Patterns
 */
public class TflApiClient implements TflClient {
    private static final Logger log = LoggerFactory.getLogger(TflApiClient.class);

    private static final String DEFAULT_TFL_BASE_URL = "https://api.tfl.gov.uk";
    private static final Duration DEFAULT_RESPONSE_TIMEOUT = Duration.ofSeconds(10);

    private final ActorSystem<?> system;
    private final Http http;
    private final Materializer materializer;
    private final ObjectMapper objectMapper;
    private final CircuitBreaker circuitBreaker;
    private final Tracing tracing;
    private final String nodeId;
    private final String baseUrl;
    private final Duration responseTimeout;

    // Retry configuration
    private final int maxRetries;
    private final Duration retryDelay;

    /**
     * Configuration for circuit breaker and retry.
     */
    public record ResilienceConfig(
            int cbMaxFailures,
            Duration cbCallTimeout,
            Duration cbResetTimeout,
            int maxRetries,
            Duration retryDelay
    ) {
        public static ResilienceConfig defaults() {
            return new ResilienceConfig(
                    5,                          // 5 failures to open
                    Duration.ofSeconds(10),     // 10s call timeout
                    Duration.ofSeconds(30),     // 30s before half-open
                    3,                          // 3 retries
                    Duration.ofSeconds(1)       // 1s between retries
            );
        }
    }

    public TflApiClient(ActorSystem<?> system, String nodeId) {
        this(system, nodeId, DEFAULT_TFL_BASE_URL, ResilienceConfig.defaults(),
             DEFAULT_RESPONSE_TIMEOUT, new Tracing());
    }

    public TflApiClient(ActorSystem<?> system, String nodeId, String baseUrl) {
        this(system, nodeId, baseUrl, ResilienceConfig.defaults(),
             DEFAULT_RESPONSE_TIMEOUT, new Tracing());
    }

    public TflApiClient(ActorSystem<?> system, String nodeId, String baseUrl,
                        ResilienceConfig config) {
        this(system, nodeId, baseUrl, config, DEFAULT_RESPONSE_TIMEOUT, new Tracing());
    }

    /** Creates a TflApiClient with full configuration. */
    public TflApiClient(ActorSystem<?> system, String nodeId, String baseUrl,
                        ResilienceConfig config, Duration responseTimeout, Tracing tracing) {
        this.system = system;
        this.nodeId = nodeId;
        this.baseUrl = baseUrl;
        this.http = Http.get(system);
        this.materializer = Materializer.createMaterializer(system);
        this.objectMapper = new ObjectMapper()
                .registerModule(new JavaTimeModule());
        this.responseTimeout = responseTimeout;
        this.tracing = tracing;

        // Pekko's built-in CircuitBreaker
        this.circuitBreaker = new CircuitBreaker(
                system.classicSystem().dispatcher(),
                system.classicSystem().scheduler(),
                config.cbMaxFailures(),
                config.cbCallTimeout(),
                config.cbResetTimeout()
        ).addOnOpenListener(() -> log.warn("Circuit breaker OPEN - TfL API failures exceeded threshold"))
         .addOnHalfOpenListener(() -> log.info("Circuit breaker HALF_OPEN - testing recovery"))
         .addOnCloseListener(() -> log.info("Circuit breaker CLOSED - TfL API recovered"));

        this.maxRetries = config.maxRetries();
        this.retryDelay = config.retryDelay();
    }

    /**
     * Constructor with pre-built CircuitBreaker (for testing).
     */
    public TflApiClient(ActorSystem<?> system, String nodeId, String baseUrl,
                        CircuitBreaker circuitBreaker, int maxRetries, Duration retryDelay,
                        Duration responseTimeout, Tracing tracing) {
        this.system = system;
        this.nodeId = nodeId;
        this.baseUrl = baseUrl;
        this.http = Http.get(system);
        this.materializer = Materializer.createMaterializer(system);
        this.objectMapper = new ObjectMapper()
                .registerModule(new JavaTimeModule());
        this.circuitBreaker = circuitBreaker;
        this.maxRetries = maxRetries;
        this.retryDelay = retryDelay;
        this.responseTimeout = responseTimeout;
        this.tracing = tracing;
    }

    /**
     * Fetch all tube line statuses (async, non-blocking).
     * Uses Pekko's built-in circuit breaker and retry patterns.
     */
    public CompletionStage<TubeStatus> fetchAllLinesAsync() {
        String url = baseUrl + "/Line/Mode/tube/Status";
        return tracing.traceTflCallAsync("fetch-all-lines", url, () ->
                withRetry(() -> withCircuitBreaker(this::doFetchAllLines)));
    }

    /**
     * Fetch status for a specific line with date range (async, non-blocking).
     * Used for future/planned disruptions - bypasses cache, goes direct to TfL.
     */
    @Override
    public CompletionStage<TubeStatus> fetchLineStatusAsync(String lineId, LocalDate from, LocalDate to) {
        String url = String.format("%s/Line/%s/Status/%s/to/%s",
                baseUrl, lineId,
                from.format(DateTimeFormatter.ISO_LOCAL_DATE),
                to.format(DateTimeFormatter.ISO_LOCAL_DATE));
        return tracing.traceTflCallAsync("fetch-line-" + lineId, url, () ->
                withRetry(() -> withCircuitBreaker(() -> doFetchLineStatus(lineId, from, to))));
    }

    /**
     * Wrap operation with Pekko's built-in CircuitBreaker.
     */
    private CompletionStage<TubeStatus> withCircuitBreaker(
            Callable<CompletionStage<TubeStatus>> operation) {
        return circuitBreaker.callWithCircuitBreakerCS(operation);
    }

    /**
     * Wrap operation with selective retry - only retries on retryable errors.
     *
     * Retryable: 408, 429, 5xx, network errors (IOException)
     * Not retryable: 4xx client errors
     */
    private CompletionStage<TubeStatus> withRetry(
            Callable<CompletionStage<TubeStatus>> operation) {
        // BiPredicate<Result, Throwable> - returns true to retry
        java.util.function.BiPredicate<TubeStatus, Throwable> shouldRetry =
                (result, error) -> error != null && isRetryableException(error);

        return retry(
                operation,
                shouldRetry,
                maxRetries,
                retryDelay,
                system.classicSystem().scheduler(),
                system.executionContext()
        ).whenComplete((result, error) -> {
            if (error != null) {
                log.warn("All {} retry attempts exhausted: {}", maxRetries, error.getMessage());
            }
        });
    }

    /**
     * Determine if an exception should trigger a retry.
     */
    private boolean isRetryableException(Throwable t) {
        // Unwrap CompletionException if present
        Throwable cause = t;
        if (t instanceof java.util.concurrent.CompletionException && t.getCause() != null) {
            cause = t.getCause();
        }

        // HTTP errors with retryable flag
        if (cause instanceof HttpStatusException httpEx) {
            boolean retryable = httpEx.isRetryable();
            if (!retryable) {
                log.debug("Not retrying {} - client error", httpEx.getStatusCode());
            }
            return retryable;
        }

        // Network errors are always retryable
        if (cause instanceof java.io.IOException) {
            log.debug("Retrying on network error: {}", cause.getMessage());
            return true;
        }

        // Default: don't retry unknown errors
        return false;
    }

    // Internal fetch methods using Pekko HTTP

    private CompletionStage<TubeStatus> doFetchAllLines() {
        String url = baseUrl + "/Line/Mode/tube/Status";
        return fetchAndParse(url, new TypeReference<List<TflApiResponse.LineResponse>>() {})
                .thenApply(this::toTubeStatus);
    }

    private CompletionStage<TubeStatus> doFetchLineStatus(String lineId, LocalDate from, LocalDate to) {
        String url = String.format("%s/Line/%s/Status/%s/to/%s",
                baseUrl, lineId,
                from.format(DateTimeFormatter.ISO_LOCAL_DATE),
                to.format(DateTimeFormatter.ISO_LOCAL_DATE));
        return fetchAndParse(url, new TypeReference<List<TflApiResponse.LineResponse>>() {})
                .thenApply(this::toTubeStatus);
    }

    private <T> CompletionStage<T> fetchAndParse(String url, TypeReference<T> typeRef) {
        log.debug("Fetching from TfL: {}", url);

        HttpRequest request = HttpRequest.create(url);

        return http.singleRequest(request)
                .thenCompose(response -> handleResponse(response, typeRef))
                .whenComplete((result, error) -> {
                    if (error != null) {
                        log.warn("TfL fetch failed: {}", error.getMessage());
                    } else {
                        log.debug("TfL fetch succeeded");
                    }
                });
    }

    private <T> CompletionStage<T> handleResponse(HttpResponse response, TypeReference<T> typeRef) {
        int status = response.status().intValue();
        log.debug("TfL response status: {}", status);

        // Record HTTP status in trace span
        tracing.recordHttpStatus(status);

        if (status >= 400) {
            // Drain the entity to free connection
            response.discardEntityBytes(materializer);

            // Determine if this error is retryable
            boolean retryable = isRetryableStatus(status);
            return CompletableFuture.failedFuture(
                    new HttpStatusException(status, "TfL API returned " + status, retryable));
        }

        // Collect response body
        return response.entity()
                .toStrict(responseTimeout.toMillis(), materializer)
                .thenApply(entity -> {
                    try {
                        String body = entity.getData().utf8String();
                        return objectMapper.readValue(body, typeRef);
                    } catch (Exception e) {
                        throw new RuntimeException("Failed to parse TfL response", e);
                    }
                })
                .toCompletableFuture();
    }

    private TubeStatus toTubeStatus(List<TflApiResponse.LineResponse> responses) {
        List<LineStatus> lines = responses.stream()
                .map(LineStatus::fromTflResponse)
                .toList();

        return new TubeStatus(lines, Instant.now(), nodeId);
    }

    /**
     * Get circuit breaker for health checks and metrics.
     */
    public CircuitBreaker getCircuitBreaker() {
        return circuitBreaker;
    }

    /**
     * Check if circuit is open.
     */
    public boolean isCircuitOpen() {
        return circuitBreaker.isOpen();
    }

    /**
     * Check if circuit is half-open.
     */
    public boolean isCircuitHalfOpen() {
        return circuitBreaker.isHalfOpen();
    }

    /**
     * Check if circuit is closed (healthy).
     */
    public boolean isCircuitClosed() {
        return circuitBreaker.isClosed();
    }

    /**
     * Determine if an HTTP status code is retryable.
     *
     * Retryable: 408 (timeout), 429 (rate limit), 5xx (server errors)
     * Not retryable: 4xx client errors (our bug or invalid request)
     */
    private boolean isRetryableStatus(int status) {
        return status == 408    // Request Timeout
            || status == 429    // Too Many Requests
            || status >= 500;   // Server errors
    }

    /**
     * HTTP status exception for retry decisions.
     */
    public static class HttpStatusException extends RuntimeException {
        private final int statusCode;
        private final boolean retryable;

        public HttpStatusException(int statusCode, String message) {
            this(statusCode, message, false);
        }

        public HttpStatusException(int statusCode, String message, boolean retryable) {
            super(message);
            this.statusCode = statusCode;
            this.retryable = retryable;
        }

        public int getStatusCode() {
            return statusCode;
        }

        public boolean isRetryable() {
            return retryable;
        }
    }
}
