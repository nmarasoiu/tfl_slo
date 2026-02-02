package com.ig.tfl.client;

import com.ig.tfl.model.TubeStatus;
import com.ig.tfl.resilience.CircuitBreaker;

import java.time.LocalDate;
import java.util.concurrent.CompletionStage;

/**
 * Interface for TfL API client.
 *
 * Allows for easy mocking/stubbing in tests.
 */
public interface TflClient {

    /**
     * Fetch all tube line statuses (async, non-blocking).
     */
    CompletionStage<TubeStatus> fetchAllLinesAsync();

    /**
     * Fetch status for a specific line (async).
     */
    CompletionStage<TubeStatus> fetchLineAsync(String lineId);

    /**
     * Fetch status for a specific line with date range (async).
     */
    CompletionStage<TubeStatus> fetchLineWithDateRangeAsync(
            String lineId, LocalDate startDate, LocalDate endDate);

    /**
     * Fetch all lines with unplanned disruptions (async).
     */
    CompletionStage<TubeStatus> fetchUnplannedDisruptionsAsync();

    /**
     * Get circuit breaker state (for health checks).
     */
    CircuitBreaker.State getCircuitState();
}
