package com.ig.tfl.client;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.ig.tfl.model.TubeStatus;
import com.ig.tfl.model.TubeStatus.LineStatus;
import com.ig.tfl.model.TubeStatus.TflLineResponse;
import com.ig.tfl.resilience.CircuitBreaker;
import com.ig.tfl.resilience.RetryPolicy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import static com.ig.tfl.resilience.RetryPolicy.HttpStatusException;

/**
 * Client for TfL API with resilience patterns.
 */
public class TflApiClient {
    private static final Logger log = LoggerFactory.getLogger(TflApiClient.class);

    private static final String TFL_BASE_URL = "https://api.tfl.gov.uk";
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(10);

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final CircuitBreaker circuitBreaker;
    private final RetryPolicy retryPolicy;
    private final String nodeId;

    public TflApiClient(String nodeId) {
        this.nodeId = nodeId;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();
        this.objectMapper = new ObjectMapper()
                .registerModule(new JavaTimeModule());
        this.circuitBreaker = CircuitBreaker.withDefaults("tfl-api");
        this.retryPolicy = RetryPolicy.defaults();
    }

    /**
     * Fetch all tube line statuses.
     */
    public TubeStatus fetchAllLines() {
        return retryPolicy.execute(() ->
                circuitBreaker.execute(() -> doFetchAllLines()));
    }

    /**
     * Fetch all tube line statuses (async).
     */
    public CompletableFuture<TubeStatus> fetchAllLinesAsync() {
        return retryPolicy.executeAsync(() ->
                CompletableFuture.supplyAsync(() ->
                        circuitBreaker.execute(this::doFetchAllLines)));
    }

    /**
     * Fetch status for a specific line.
     */
    public TubeStatus fetchLine(String lineId) {
        return retryPolicy.execute(() ->
                circuitBreaker.execute(() -> doFetchLine(lineId)));
    }

    /**
     * Fetch status for a specific line with date range.
     */
    public TubeStatus fetchLineWithDateRange(String lineId, LocalDate startDate, LocalDate endDate) {
        return retryPolicy.execute(() ->
                circuitBreaker.execute(() -> doFetchLineWithDateRange(lineId, startDate, endDate)));
    }

    /**
     * Fetch all lines with unplanned disruptions.
     */
    public TubeStatus fetchUnplannedDisruptions() {
        TubeStatus all = fetchAllLines();
        List<LineStatus> disrupted = all.lines().stream()
                .filter(line -> hasUnplannedDisruption(line))
                .toList();
        return new TubeStatus(disrupted, all.tflTimestamp(), all.fetchedAt(),
                all.fetchedBy(), TubeStatus.Source.TFL);
    }

    private boolean hasUnplannedDisruption(LineStatus line) {
        if (line.disruptions() == null || line.disruptions().isEmpty()) {
            return false;
        }
        // Has at least one unplanned disruption
        return line.disruptions().stream().anyMatch(d -> !d.isPlanned());
    }

    // Internal fetch methods

    private TubeStatus doFetchAllLines() {
        String url = TFL_BASE_URL + "/Line/Mode/tube/Status";
        List<TflLineResponse> responses = fetchAndParse(url, new TypeReference<>() {});
        return toTubeStatus(responses);
    }

    private TubeStatus doFetchLine(String lineId) {
        String url = TFL_BASE_URL + "/Line/" + lineId + "/Status";
        List<TflLineResponse> responses = fetchAndParse(url, new TypeReference<>() {});
        return toTubeStatus(responses);
    }

    private TubeStatus doFetchLineWithDateRange(String lineId, LocalDate startDate, LocalDate endDate) {
        String url = String.format("%s/Line/%s/Status/%s/to/%s",
                TFL_BASE_URL, lineId,
                startDate.format(DateTimeFormatter.ISO_LOCAL_DATE),
                endDate.format(DateTimeFormatter.ISO_LOCAL_DATE));
        List<TflLineResponse> responses = fetchAndParse(url, new TypeReference<>() {});
        return toTubeStatus(responses);
    }

    private <T> T fetchAndParse(String url, TypeReference<T> typeRef) {
        log.debug("Fetching from TfL: {}", url);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(REQUEST_TIMEOUT)
                .header("Accept", "application/json")
                .GET()
                .build();

        try {
            HttpResponse<String> response = httpClient.send(request,
                    HttpResponse.BodyHandlers.ofString());

            int status = response.statusCode();
            log.debug("TfL response status: {}", status);

            if (status >= 400) {
                throw new HttpStatusException(status,
                        "TfL API returned " + status + ": " + response.body());
            }

            return objectMapper.readValue(response.body(), typeRef);

        } catch (HttpStatusException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("Failed to fetch from TfL: " + e.getMessage(), e);
        }
    }

    private TubeStatus toTubeStatus(List<TflLineResponse> responses) {
        List<LineStatus> lines = responses.stream()
                .map(LineStatus::fromTflResponse)
                .toList();

        Instant now = Instant.now();
        return new TubeStatus(
                lines,
                now,           // TfL doesn't always include timestamp, use fetch time
                now,
                nodeId,
                TubeStatus.Source.TFL
        );
    }

    /**
     * Get circuit breaker state (for health checks).
     */
    public CircuitBreaker.State getCircuitState() {
        return circuitBreaker.getState();
    }
}
