package com.ig.tfl.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.io.Serializable;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * Represents the status of all tube lines at a point in time.
 * This is the value stored in our LWW-Map CRDT.
 */
public record TubeStatus(
        List<LineStatus> lines,
        Instant tflTimestamp,    // When TfL says the data is from
        Instant fetchedAt,       // When we fetched it
        String fetchedBy,        // Which node fetched it
        Source source            // Where we got it from
) implements Serializable {

    public enum Source {
        TFL,    // Direct from TfL API
        PEER,   // From another node via CRDT
        CACHE   // From local cache (no recent fetch)
    }

    public enum Confidence {
        FRESH,      // < 5 min old
        STALE,      // 5-30 min old
        DEGRADED    // > 30 min old or circuit open
    }

    public long freshnessMs() {
        return Instant.now().toEpochMilli() - fetchedAt.toEpochMilli();
    }

    public Confidence confidence() {
        long ageMs = freshnessMs();
        if (ageMs < 5 * 60 * 1000) return Confidence.FRESH;
        if (ageMs < 30 * 60 * 1000) return Confidence.STALE;
        return Confidence.DEGRADED;
    }

    public boolean isFresherThan(TubeStatus other) {
        if (other == null) return true;
        return this.fetchedAt.isAfter(other.fetchedAt);
    }

    public TubeStatus withSource(Source newSource) {
        return new TubeStatus(lines, tflTimestamp, fetchedAt, fetchedBy, newSource);
    }

    /**
     * Status of a single tube line.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record LineStatus(
            String id,
            String name,
            String status,
            String statusSeverityDescription,
            List<Disruption> disruptions
    ) implements Serializable {

        /**
         * Parse from TfL API response format.
         */
        public static LineStatus fromTflResponse(TflLineResponse response) {
            String status = "Unknown";
            String statusDesc = "";
            List<Disruption> disruptions = List.of();

            if (response.lineStatuses() != null && !response.lineStatuses().isEmpty()) {
                var firstStatus = response.lineStatuses().get(0);
                status = firstStatus.statusSeverityDescription();
                statusDesc = firstStatus.statusSeverityDescription();

                disruptions = firstStatus.disruption() != null
                        ? List.of(new Disruption(
                        firstStatus.disruption().categoryDescription(),
                        firstStatus.disruption().description(),
                        firstStatus.disruption().isPlanned()))
                        : List.of();
            }

            return new LineStatus(
                    response.id(),
                    response.name(),
                    status,
                    statusDesc,
                    disruptions
            );
        }
    }

    public record Disruption(
            String category,
            String description,
            boolean isPlanned
    ) implements Serializable {}

    // TfL API response format
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record TflLineResponse(
            String id,
            String name,
            @JsonProperty("lineStatuses") List<TflLineStatus> lineStatuses
    ) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record TflLineStatus(
            String statusSeverityDescription,
            TflDisruption disruption
    ) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record TflDisruption(
            String categoryDescription,
            String description,
            @JsonProperty("isPlanned") boolean isPlanned
    ) {}
}
