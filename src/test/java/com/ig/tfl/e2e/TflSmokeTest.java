package com.ig.tfl.e2e;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * E2E smoke tests hitting the real TfL API.
 *
 * These tests require network access to api.tfl.gov.uk.
 * Run with: ./gradlew test --tests "*SmokeTest*"
 *
 * Tagged with "e2e" so they can be excluded from regular unit test runs:
 *   ./gradlew test -PexcludeTags=e2e
 */
@Tag("e2e")
class TflSmokeTest {

    private static final String TFL_API_BASE = "https://api.tfl.gov.uk";
    private static final ObjectMapper objectMapper = new ObjectMapper();
    private static final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    // All tube lines that should exist
    private static final Set<String> EXPECTED_TUBE_LINES = Set.of(
            "bakerloo", "central", "circle", "district", "hammersmith-city",
            "jubilee", "metropolitan", "northern", "piccadilly", "victoria",
            "waterloo-city"
    );

    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    void tflApi_fetchAllTubeLines_returnsAllLines() throws Exception {
        // Given: the real TfL API
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(TFL_API_BASE + "/Line/Mode/tube/Status"))
                .GET()
                .timeout(Duration.ofSeconds(10))
                .build();

        // When: we fetch all tube statuses
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        // Then: we get a successful response
        assertThat(response.statusCode()).isEqualTo(200);

        // And: the response contains all expected tube lines
        JsonNode lines = objectMapper.readTree(response.body());
        assertThat(lines.isArray()).isTrue();
        assertThat(lines.size()).isGreaterThanOrEqualTo(11);

        // Verify we have all expected lines
        Set<String> returnedLineIds = new java.util.HashSet<>();
        for (JsonNode line : lines) {
            returnedLineIds.add(line.get("id").asText());
        }
        assertThat(returnedLineIds).containsAll(EXPECTED_TUBE_LINES);
    }

    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    void tflApi_fetchSingleLine_returnsLineStatus() throws Exception {
        // Given: request for Central line
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(TFL_API_BASE + "/Line/central/Status"))
                .GET()
                .timeout(Duration.ofSeconds(10))
                .build();

        // When: we fetch Central line status
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        // Then: we get a successful response
        assertThat(response.statusCode()).isEqualTo(200);

        // And: it's the Central line with status info
        JsonNode lines = objectMapper.readTree(response.body());
        assertThat(lines.isArray()).isTrue();
        assertThat(lines.size()).isEqualTo(1);

        JsonNode central = lines.get(0);
        assertThat(central.get("id").asText()).isEqualTo("central");
        assertThat(central.get("name").asText()).isEqualTo("Central");
        assertThat(central.has("lineStatuses")).isTrue();
        assertThat(central.get("lineStatuses").isArray()).isTrue();
        assertThat(central.get("lineStatuses").size()).isGreaterThan(0);

        // Verify status has severity description
        JsonNode status = central.get("lineStatuses").get(0);
        assertThat(status.has("statusSeverityDescription")).isTrue();
        String statusDesc = status.get("statusSeverityDescription").asText();
        assertThat(statusDesc).isNotBlank();
        System.out.println("Central line status: " + statusDesc);
    }

    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    void tflApi_fetchWithDateRange_returnsPlannedDisruptions() throws Exception {
        // Given: request for Northern line with a future date range (next 7 days)
        java.time.LocalDate from = java.time.LocalDate.now();
        java.time.LocalDate to = from.plusDays(7);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(TFL_API_BASE + "/Line/northern/Status/" + from + "/to/" + to))
                .GET()
                .timeout(Duration.ofSeconds(10))
                .build();

        // When: we fetch Northern line status with date range
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        // Then: we get a successful response
        assertThat(response.statusCode()).isEqualTo(200);

        // And: it contains the Northern line
        JsonNode lines = objectMapper.readTree(response.body());
        assertThat(lines.isArray()).isTrue();
        assertThat(lines.size()).isGreaterThanOrEqualTo(1);

        JsonNode northern = lines.get(0);
        assertThat(northern.get("id").asText()).isEqualTo("northern");
        System.out.println("Northern line date range query returned " +
                northern.get("lineStatuses").size() + " status entries");
    }

    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    void tflApi_invalidLine_returns404() throws Exception {
        // Given: request for non-existent line
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(TFL_API_BASE + "/Line/not-a-real-line/Status"))
                .GET()
                .timeout(Duration.ofSeconds(10))
                .build();

        // When: we fetch the invalid line
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        // Then: we get a 404
        assertThat(response.statusCode()).isEqualTo(404);
    }

    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    void tflApi_responseStructure_matchesExpectedSchema() throws Exception {
        // This test verifies the response structure matches what our TubeStatus model expects
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(TFL_API_BASE + "/Line/victoria/Status"))
                .GET()
                .timeout(Duration.ofSeconds(10))
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(200);

        JsonNode lines = objectMapper.readTree(response.body());
        JsonNode victoria = lines.get(0);

        // Verify structure matches our model
        assertThat(victoria.has("id")).isTrue();
        assertThat(victoria.has("name")).isTrue();
        assertThat(victoria.has("lineStatuses")).isTrue();

        JsonNode lineStatus = victoria.get("lineStatuses").get(0);
        assertThat(lineStatus.has("statusSeverity")).isTrue();
        assertThat(lineStatus.has("statusSeverityDescription")).isTrue();

        // disruptions may or may not be present
        if (lineStatus.has("disruption")) {
            JsonNode disruption = lineStatus.get("disruption");
            assertThat(disruption.has("category")).isTrue();
            assertThat(disruption.has("description")).isTrue();
        }

        System.out.println("Victoria line response structure validated");
    }
}
