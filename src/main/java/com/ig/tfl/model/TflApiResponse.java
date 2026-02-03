package com.ig.tfl.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * DTOs for TfL API response format.
 * Separated from domain model to maintain clear boundaries.
 */
public final class TflApiResponse {

    private TflApiResponse() {} // Non-instantiable

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record LineResponse(
            String id,
            String name,
            @JsonProperty("lineStatuses") List<LineStatus> lineStatuses
    ) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record LineStatus(
            String statusSeverityDescription,
            Disruption disruption
    ) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Disruption(
            String categoryDescription,
            String description,
            @JsonProperty("isPlanned") boolean isPlanned
    ) {}
}
