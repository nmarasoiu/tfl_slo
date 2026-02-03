package com.ig.tfl.observability;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tests for OpenTelemetry tracing integration.
 */
class TracingTest {

    private InMemorySpanExporter spanExporter;
    private Tracing tracing;

    @BeforeEach
    void setUp() {
        spanExporter = InMemorySpanExporter.create();
        SdkTracerProvider tracerProvider = SdkTracerProvider.builder()
                .addSpanProcessor(SimpleSpanProcessor.create(spanExporter))
                .build();
        OpenTelemetry openTelemetry = OpenTelemetrySdk.builder()
                .setTracerProvider(tracerProvider)
                .build();
        tracing = new Tracing(openTelemetry);
    }

    @Test
    void traceTflCall_createsSpanWithCorrectAttributes() {
        String result = tracing.traceTflCall("test-op", "https://api.tfl.gov.uk/test", () -> "success");

        assertThat(result).isEqualTo("success");

        List<SpanData> spans = spanExporter.getFinishedSpanItems();
        assertThat(spans).hasSize(1);

        SpanData span = spans.get(0);
        assertThat(span.getName()).isEqualTo("tfl-api test-op");
        assertThat(span.getAttributes().get(io.opentelemetry.api.common.AttributeKey.stringKey("http.url")))
                .isEqualTo("https://api.tfl.gov.uk/test");
        assertThat(span.getAttributes().get(io.opentelemetry.api.common.AttributeKey.stringKey("peer.service")))
                .isEqualTo("tfl-api");
    }

    @Test
    void traceTflCall_recordsExceptionOnFailure() {
        assertThatThrownBy(() ->
                tracing.traceTflCall("failing-op", "https://api.tfl.gov.uk/fail", () -> {
                    throw new RuntimeException("TfL API error");
                })
        ).isInstanceOf(RuntimeException.class);

        List<SpanData> spans = spanExporter.getFinishedSpanItems();
        assertThat(spans).hasSize(1);

        SpanData span = spans.get(0);
        assertThat(span.getStatus().getStatusCode())
                .isEqualTo(io.opentelemetry.api.trace.StatusCode.ERROR);
        assertThat(span.getEvents()).isNotEmpty();
    }

    @Test
    void traceTflCallAsync_createsSpanForAsyncOperation() throws Exception {
        CompletableFuture<String> future = tracing.traceTflCallAsync(
                "async-op",
                "https://api.tfl.gov.uk/async",
                () -> CompletableFuture.completedFuture("async-success")
        ).toCompletableFuture();

        String result = future.get();
        assertThat(result).isEqualTo("async-success");

        List<SpanData> spans = spanExporter.getFinishedSpanItems();
        assertThat(spans).hasSize(1);

        SpanData span = spans.get(0);
        assertThat(span.getName()).isEqualTo("tfl-api async-op");
        assertThat(span.getStatus().getStatusCode())
                .isEqualTo(io.opentelemetry.api.trace.StatusCode.OK);
    }

    @Test
    void traceTflCallAsync_recordsErrorOnAsyncFailure() throws Exception {
        CompletableFuture<String> future = tracing.<String>traceTflCallAsync(
                "async-fail-op",
                "https://api.tfl.gov.uk/fail",
                () -> CompletableFuture.failedFuture(new RuntimeException("Async TfL error"))
        ).toCompletableFuture();

        assertThatThrownBy(future::get).hasCauseInstanceOf(RuntimeException.class);

        List<SpanData> spans = spanExporter.getFinishedSpanItems();
        assertThat(spans).hasSize(1);

        SpanData span = spans.get(0);
        assertThat(span.getStatus().getStatusCode())
                .isEqualTo(io.opentelemetry.api.trace.StatusCode.ERROR);
    }

    @Test
    void getCurrentTraceId_returnsValidTraceId() {
        AtomicReference<String> capturedTraceId = new AtomicReference<>();

        tracing.traceTflCall("trace-id-test", "https://api.tfl.gov.uk/test", () -> {
            capturedTraceId.set(tracing.getCurrentTraceId());
            return "done";
        });

        assertThat(capturedTraceId.get()).isNotNull();
        assertThat(capturedTraceId.get()).hasSize(32); // Trace ID is 32 hex chars
        assertThat(capturedTraceId.get()).doesNotContain("0000000000000000"); // Not invalid
    }
}
