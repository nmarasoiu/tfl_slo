package com.ig.tfl.client;

import com.ig.tfl.model.TubeStatus;
import com.ig.tfl.resilience.CircuitBreaker;

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
     * Get circuit breaker state (for health checks).
     */
    CircuitBreaker.State getCircuitState();
}
