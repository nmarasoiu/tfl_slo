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
 * Minimal E2E smoke test - ONE call to real TfL API per build.
 *
 * Purpose: Verify TfL API is reachable and returns expected format.
 * This is the ONLY test that hits real TfL - all other tests use mocks.
 *
 * Run with: ./gradlew e2eTest
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

    /**
     * Single smoke test that verifies:
     * 1. TfL API is reachable over HTTPS
     * 2. Returns valid JSON with expected structure
     * 3. Contains all expected tube lines
     *
     * This is intentionally ONE test to minimize API calls.
     */
    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    void tflApi_isReachableAndReturnsExpectedFormat() throws Exception {
        // Given: the real TfL API
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(TFL_API_BASE + "/Line/Mode/tube/Status"))
                .GET()
                .timeout(Duration.ofSeconds(10))
                .build();

        // When: we fetch all tube statuses
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        // Then: we get a successful response
        assertThat(response.statusCode())
                .as("TfL API should return 200")
                .isEqualTo(200);

        // And: the response is valid JSON array
        JsonNode lines = objectMapper.readTree(response.body());
        assertThat(lines.isArray())
                .as("Response should be a JSON array")
                .isTrue();
        assertThat(lines.size())
                .as("Should have at least 11 tube lines")
                .isGreaterThanOrEqualTo(11);

        // And: contains all expected tube lines
        Set<String> returnedLineIds = new java.util.HashSet<>();
        for (JsonNode line : lines) {
            returnedLineIds.add(line.get("id").asText());

            // Verify structure matches our model expectations
            assertThat(line.has("id")).isTrue();
            assertThat(line.has("name")).isTrue();
            assertThat(line.has("lineStatuses")).isTrue();

            JsonNode lineStatuses = line.get("lineStatuses");
            assertThat(lineStatuses.isArray()).isTrue();
            if (lineStatuses.size() > 0) {
                JsonNode status = lineStatuses.get(0);
                assertThat(status.has("statusSeverityDescription")).isTrue();
            }
        }
        assertThat(returnedLineIds)
                .as("Should contain all expected tube lines")
                .containsAll(EXPECTED_TUBE_LINES);

        System.out.println("TfL API smoke test passed - " + lines.size() + " lines returned");
    }
}
