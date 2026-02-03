package com.ig.tfl.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.io.Serializable;
import java.time.Instant;
import java.util.List;

/**
 * Represents the status of all tube lines at a point in time.
 * This is the value stored in our LWW-Register CRDT.
 *
 * Simplicity: One timestamp (queriedAt) that never changes through replication.
 * Clients see exactly when TfL was last successfully queried.
 */
public record TubeStatus(
        List<LineStatus> lines,
        Instant queriedAt,       // When TfL API was queried (immutable through CRDT)
        String queriedBy         // Which node queried TfL (for debugging)
) implements Serializable {

    /**
     * Age of this data in milliseconds.
     */
    public long ageMs() {
        return Instant.now().toEpochMilli() - queriedAt.toEpochMilli();
    }

    /**
     * Used by LWW-Register to determine which value wins.
     */
    public boolean isFresherThan(TubeStatus other) {
        if (other == null) return true;
        return this.queriedAt.isAfter(other.queriedAt);
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
        public static LineStatus fromTflResponse(TflApiResponse.LineResponse response) {
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
}
