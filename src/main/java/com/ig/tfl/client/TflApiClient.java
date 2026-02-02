package com.ig.tfl.client;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.ig.tfl.model.TubeStatus;
import com.ig.tfl.model.TubeStatus.LineStatus;
import com.ig.tfl.model.TubeStatus.TflLineResponse;
import com.ig.tfl.resilience.CircuitBreaker;
import com.ig.tfl.resilience.RetryPolicy;
import org.apache.pekko.actor.typed.ActorSystem;
import org.apache.pekko.http.javadsl.Http;
import org.apache.pekko.http.javadsl.model.HttpRequest;
import org.apache.pekko.http.javadsl.model.HttpResponse;
import org.apache.pekko.http.javadsl.model.StatusCodes;
import org.apache.pekko.stream.Materializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

import static com.ig.tfl.resilience.RetryPolicy.HttpStatusException;

/**
 * Client for TfL API using Pekko HTTP (non-blocking).
 */
public class TflApiClient implements TflClient {
    private static final Logger log = LoggerFactory.getLogger(TflApiClient.class);

    private static final String DEFAULT_TFL_BASE_URL = "https://api.tfl.gov.uk";
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(10);

    private final ActorSystem<?> system;
    private final Http http;
    private final Materializer materializer;
    private final ObjectMapper objectMapper;
    private final CircuitBreaker circuitBreaker;
    private final RetryPolicy retryPolicy;
    private final String nodeId;
    private final String baseUrl;

    public TflApiClient(ActorSystem<?> system, String nodeId) {
        this(system, nodeId, DEFAULT_TFL_BASE_URL);
    }

    public TflApiClient(ActorSystem<?> system, String nodeId, String baseUrl) {
        this.system = system;
        this.nodeId = nodeId;
        this.baseUrl = baseUrl;
        this.http = Http.get(system);
        this.materializer = Materializer.createMaterializer(system);
        this.objectMapper = new ObjectMapper()
                .registerModule(new JavaTimeModule());
        this.circuitBreaker = CircuitBreaker.withDefaults("tfl-api");
        this.retryPolicy = RetryPolicy.defaults();
    }

    /**
     * Fetch all tube line statuses (async, non-blocking).
     */
    public CompletionStage<TubeStatus> fetchAllLinesAsync() {
        return retryPolicy.executeAsync(() ->
                executeWithCircuitBreaker(() -> doFetchAllLines()));
    }

    /**
     * Fetch status for a specific line (async).
     */
    public CompletionStage<TubeStatus> fetchLineAsync(String lineId) {
        return retryPolicy.executeAsync(() ->
                executeWithCircuitBreaker(() -> doFetchLine(lineId)));
    }

    /**
     * Fetch status for a specific line with date range (async).
     */
    public CompletionStage<TubeStatus> fetchLineWithDateRangeAsync(
            String lineId, LocalDate startDate, LocalDate endDate) {
        return retryPolicy.executeAsync(() ->
                executeWithCircuitBreaker(() -> doFetchLineWithDateRange(lineId, startDate, endDate)));
    }

    /**
     * Fetch all lines with unplanned disruptions (async).
     */
    public CompletionStage<TubeStatus> fetchUnplannedDisruptionsAsync() {
        return fetchAllLinesAsync().thenApply(all -> {
            List<LineStatus> disrupted = all.lines().stream()
                    .filter(this::hasUnplannedDisruption)
                    .toList();
            return new TubeStatus(disrupted, all.queriedAt(), all.queriedBy());
        });
    }

    private boolean hasUnplannedDisruption(LineStatus line) {
        if (line.disruptions() == null || line.disruptions().isEmpty()) {
            return false;
        }
        return line.disruptions().stream().anyMatch(d -> !d.isPlanned());
    }

    // Circuit breaker wrapper
    private CompletableFuture<TubeStatus> executeWithCircuitBreaker(
            java.util.function.Supplier<CompletionStage<TubeStatus>> operation) {

        CircuitBreaker.State state = circuitBreaker.getState();
        if (state == CircuitBreaker.State.OPEN) {
            return CompletableFuture.failedFuture(
                    new CircuitBreaker.CircuitOpenException("tfl-api", Duration.ofSeconds(30)));
        }

        return operation.get()
                .whenComplete((result, error) -> {
                    if (error != null) {
                        circuitBreaker.onFailure(error);
                    } else {
                        circuitBreaker.onSuccess();
                    }
                })
                .toCompletableFuture();
    }

    // Internal fetch methods using Pekko HTTP

    private CompletionStage<TubeStatus> doFetchAllLines() {
        String url = baseUrl + "/Line/Mode/tube/Status";
        return fetchAndParse(url, new TypeReference<List<TflLineResponse>>() {})
                .thenApply(this::toTubeStatus);
    }

    private CompletionStage<TubeStatus> doFetchLine(String lineId) {
        String url = baseUrl + "/Line/" + lineId + "/Status";
        return fetchAndParse(url, new TypeReference<List<TflLineResponse>>() {})
                .thenApply(this::toTubeStatus);
    }

    private CompletionStage<TubeStatus> doFetchLineWithDateRange(
            String lineId, LocalDate startDate, LocalDate endDate) {
        String url = String.format("%s/Line/%s/Status/%s/to/%s",
                baseUrl, lineId,
                startDate.format(DateTimeFormatter.ISO_LOCAL_DATE),
                endDate.format(DateTimeFormatter.ISO_LOCAL_DATE));
        return fetchAndParse(url, new TypeReference<List<TflLineResponse>>() {})
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

        if (status >= 400) {
            // Drain the entity to free connection
            response.discardEntityBytes(materializer);

            return CompletableFuture.failedFuture(
                    new HttpStatusException(status, "TfL API returned " + status));
        }

        // Collect response body
        return response.entity()
                .toStrict(REQUEST_TIMEOUT.toMillis(), materializer)
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

    private TubeStatus toTubeStatus(List<TflLineResponse> responses) {
        List<LineStatus> lines = responses.stream()
                .map(LineStatus::fromTflResponse)
                .toList();

        return new TubeStatus(lines, Instant.now(), nodeId);
    }

    /**
     * Get circuit breaker state (for health checks).
     */
    public CircuitBreaker.State getCircuitState() {
        return circuitBreaker.getState();
    }
}
