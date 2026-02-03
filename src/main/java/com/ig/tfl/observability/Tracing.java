package com.ig.tfl.observability;

import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import io.opentelemetry.exporter.logging.LoggingSpanExporter;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.Supplier;

/**
 * Lightweight OpenTelemetry tracing for TfL API calls.
 *
 * Purpose: Distinguish "our latency" from "TfL API latency" for debugging
 * and performance analysis. Exports spans to logs by default.
 *
 * This is intentionally minimal - we trace the external dependency boundary,
 * not every internal method call.
 */
public class Tracing {
    private static final Logger log = LoggerFactory.getLogger(Tracing.class);

    private static final String INSTRUMENTATION_NAME = "tfl-status-service";

    // Semantic convention attribute keys
    private static final AttributeKey<String> HTTP_METHOD = AttributeKey.stringKey("http.method");
    private static final AttributeKey<String> HTTP_URL = AttributeKey.stringKey("http.url");
    private static final AttributeKey<Long> HTTP_STATUS_CODE = AttributeKey.longKey("http.status_code");
    private static final AttributeKey<String> PEER_SERVICE = AttributeKey.stringKey("peer.service");
    private static final AttributeKey<Long> RETRY_ATTEMPT = AttributeKey.longKey("retry.attempt");

    private final Tracer tracer;
    private final OpenTelemetry openTelemetry;

    public Tracing() {
        this(createDefaultOpenTelemetry());
    }

    public Tracing(OpenTelemetry openTelemetry) {
        this.openTelemetry = openTelemetry;
        this.tracer = openTelemetry.getTracer(INSTRUMENTATION_NAME);
        log.info("OpenTelemetry tracing initialized (logging exporter)");
    }

    /**
     * Create default OpenTelemetry with logging exporter.
     * In production, this could be configured via env vars for OTLP export.
     */
    private static OpenTelemetry createDefaultOpenTelemetry() {
        SdkTracerProvider tracerProvider = SdkTracerProvider.builder()
                .addSpanProcessor(SimpleSpanProcessor.create(LoggingSpanExporter.create()))
                .build();

        return OpenTelemetrySdk.builder()
                .setTracerProvider(tracerProvider)
                .build();
    }

    /**
     * Trace a synchronous TfL API call.
     */
    public <T> T traceTflCall(String operation, String url, Supplier<T> call) {
        Span span = tracer.spanBuilder("tfl-api " + operation)
                .setSpanKind(SpanKind.CLIENT)
                .setAttribute(HTTP_METHOD, "GET")
                .setAttribute(HTTP_URL, url)
                .setAttribute(PEER_SERVICE, "tfl-api")
                .startSpan();

        try (Scope scope = span.makeCurrent()) {
            setTraceIdInMdc(span);
            T result = call.get();
            span.setStatus(StatusCode.OK);
            return result;
        } catch (Exception e) {
            span.setStatus(StatusCode.ERROR, e.getMessage());
            span.recordException(e);
            throw e;
        } finally {
            span.end();
            clearTraceIdFromMdc();
        }
    }

    /**
     * Trace an async TfL API call.
     * Returns a CompletionStage that completes when the original completes,
     * with the span ended appropriately.
     */
    public <T> CompletionStage<T> traceTflCallAsync(String operation, String url,
                                                      Supplier<CompletionStage<T>> call) {
        Span span = tracer.spanBuilder("tfl-api " + operation)
                .setSpanKind(SpanKind.CLIENT)
                .setAttribute(HTTP_METHOD, "GET")
                .setAttribute(HTTP_URL, url)
                .setAttribute(PEER_SERVICE, "tfl-api")
                .startSpan();

        Context context = Context.current().with(span);
        setTraceIdInMdc(span);

        CompletionStage<T> result;
        try (Scope scope = span.makeCurrent()) {
            result = call.get();
        } catch (Exception e) {
            span.setStatus(StatusCode.ERROR, e.getMessage());
            span.recordException(e);
            span.end();
            clearTraceIdFromMdc();
            return CompletableFuture.failedFuture(e);
        }

        return result.whenComplete((value, error) -> {
            if (error != null) {
                span.setStatus(StatusCode.ERROR, error.getMessage());
                span.recordException(error);
            } else {
                span.setStatus(StatusCode.OK);
            }
            span.end();
            clearTraceIdFromMdc();
        });
    }

    /**
     * Record HTTP status code on current span.
     */
    public void recordHttpStatus(int statusCode) {
        Span current = Span.current();
        if (current.isRecording()) {
            current.setAttribute(HTTP_STATUS_CODE, (long) statusCode);
            if (statusCode >= 400) {
                current.setStatus(StatusCode.ERROR, "HTTP " + statusCode);
            }
        }
    }

    /**
     * Record retry attempt on current span.
     */
    public void recordRetryAttempt(int attempt) {
        Span current = Span.current();
        if (current.isRecording()) {
            current.setAttribute(RETRY_ATTEMPT, (long) attempt);
            current.addEvent("retry", Attributes.of(RETRY_ATTEMPT, (long) attempt));
        }
    }

    /**
     * Get current trace ID (for logging correlation).
     */
    public String getCurrentTraceId() {
        return Span.current().getSpanContext().getTraceId();
    }

    /**
     * Get the tracer for custom span creation.
     */
    public Tracer getTracer() {
        return tracer;
    }

    private void setTraceIdInMdc(Span span) {
        String traceId = span.getSpanContext().getTraceId();
        String spanId = span.getSpanContext().getSpanId();
        MDC.put("traceId", traceId);
        MDC.put("spanId", spanId);
    }

    private void clearTraceIdFromMdc() {
        MDC.remove("traceId");
        MDC.remove("spanId");
    }
}
