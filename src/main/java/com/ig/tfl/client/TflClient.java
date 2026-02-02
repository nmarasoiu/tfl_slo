package com.ig.tfl.client;

import com.ig.tfl.model.TubeStatus;
import com.ig.tfl.resilience.CircuitBreaker;

import java.time.LocalDate;
import java.util.concurrent.CompletionStage;

/**
 * Interface for TfL API client.
 */
public interface TflClient {

    /**
     * Fetch all tube line statuses (async, non-blocking).
     */
    CompletionStage<TubeStatus> fetchAllLinesAsync();

    /**
     * Fetch status for a specific line with date range (async, non-blocking).
     * Used for future/planned disruptions.
     */
    CompletionStage<TubeStatus> fetchLineStatusAsync(String lineId, LocalDate from, LocalDate to);

    /**
     * Get circuit breaker state (for health checks).
     */
    CircuitBreaker.State getCircuitState();
}
