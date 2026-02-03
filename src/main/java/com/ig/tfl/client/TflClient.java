package com.ig.tfl.client;

import com.ig.tfl.model.TubeStatus;

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
     * Check if circuit breaker is open.
     */
    boolean isCircuitOpen();

    /**
     * Check if circuit breaker is half-open.
     */
    boolean isCircuitHalfOpen();

    /**
     * Check if circuit breaker is closed (healthy).
     */
    boolean isCircuitClosed();
}
