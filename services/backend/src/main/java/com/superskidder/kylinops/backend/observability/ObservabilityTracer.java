package com.superskidder.kylinops.backend.observability;

import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanBuilder;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import io.opentelemetry.context.propagation.TextMapGetter;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.stereotype.Component;

@Component
public class ObservabilityTracer {

    private final Tracer tracer;

    public ObservabilityTracer() {
        this.tracer = GlobalOpenTelemetry.getTracer("kylinops-backend");
    }

    public String currentTraceIdOrNew() {
        String traceId = Span.current().getSpanContext().getTraceId();
        if (traceId != null && !traceId.isBlank() && !"00000000000000000000000000000000".equals(traceId)) {
            return traceId;
        }
        return UUID.randomUUID().toString().replace("-", "");
    }

    public boolean currentTraceIdEquals(String traceId) {
        var spanContext = Span.current().getSpanContext();
        return spanContext.isValid() && TraceContext.w3cTraceId(traceId).equals(spanContext.getTraceId());
    }

    public <T> T inSpan(String name, Map<String, ?> attributes, Supplier<T> supplier) {
        return inSpan(name, attributes, "", supplier);
    }

    public <T> T inSpan(String name, Map<String, ?> attributes, String traceparent, Supplier<T> supplier) {
        Span span = startSpan(name, attributes, traceparent);
        try (Scope ignored = span.makeCurrent()) {
            return supplier.get();
        } catch (RuntimeException exception) {
            span.recordException(exception);
            span.setStatus(StatusCode.ERROR, exception.getMessage() == null ? exception.getClass().getSimpleName() : exception.getMessage());
            throw exception;
        } finally {
            span.end();
        }
    }

    public void inSpan(String name, Map<String, ?> attributes, Runnable runnable) {
        inSpan(name, attributes, "", runnable);
    }

    public void inSpan(String name, Map<String, ?> attributes, String traceparent, Runnable runnable) {
        inSpan(name, attributes, traceparent, () -> {
            runnable.run();
            return null;
        });
    }

    public Span startSpan(String name, Map<String, ?> attributes) {
        return startSpan(name, attributes, "");
    }

    public Span startSpan(String name, Map<String, ?> attributes, String traceparent) {
        SpanBuilder builder = tracer.spanBuilder(name);
        Context parent = extractTraceparent(traceparent);
        if (parent != null && Span.fromContext(parent).getSpanContext().isValid()) {
            builder.setParent(parent);
        }
        Span span = builder.startSpan();
        applyAttributes(span, attributes);
        return span;
    }

    public void annotateCurrent(String eventName, Map<String, ?> attributes) {
        Span span = Span.current();
        if (!span.getSpanContext().isValid()) {
            return;
        }
        if (attributes == null || attributes.isEmpty()) {
            span.addEvent(eventName);
            return;
        }
        io.opentelemetry.api.common.AttributesBuilder builder = io.opentelemetry.api.common.Attributes.builder();
        attributes.forEach((key, value) -> putAttribute(builder, key, value));
        span.addEvent(eventName, builder.build());
    }

    public void setCurrentAttributes(Map<String, ?> attributes) {
        Span span = Span.current();
        if (!span.getSpanContext().isValid()) {
            return;
        }
        applyAttributes(span, attributes);
    }

    private void applyAttributes(Span span, Map<String, ?> attributes) {
        if (attributes == null || attributes.isEmpty()) {
            return;
        }
        attributes.forEach((key, value) -> {
            if (value instanceof Long longValue) {
                span.setAttribute(key, longValue);
            } else if (value instanceof Integer intValue) {
                span.setAttribute(key, intValue.longValue());
            } else if (value instanceof Double doubleValue) {
                span.setAttribute(key, doubleValue);
            } else if (value instanceof Float floatValue) {
                span.setAttribute(key, floatValue.doubleValue());
            } else if (value instanceof Boolean booleanValue) {
                span.setAttribute(key, booleanValue);
            } else if (value != null) {
                span.setAttribute(key, String.valueOf(value));
            }
        });
    }

    private void putAttribute(io.opentelemetry.api.common.AttributesBuilder builder, String key, Object value) {
        if (value instanceof Long longValue) {
            builder.put(key, longValue);
        } else if (value instanceof Integer intValue) {
            builder.put(key, intValue.longValue());
        } else if (value instanceof Double doubleValue) {
            builder.put(key, doubleValue);
        } else if (value instanceof Float floatValue) {
            builder.put(key, floatValue.doubleValue());
        } else if (value instanceof Boolean booleanValue) {
            builder.put(key, booleanValue);
        } else if (value != null) {
            builder.put(key, String.valueOf(value));
        }
    }

    private Context extractTraceparent(String traceparent) {
        if (traceparent == null || traceparent.isBlank()) {
            return null;
        }
        return W3CTraceContextPropagator.getInstance().extract(
                Context.current(),
                Map.of("traceparent", traceparent.trim()),
                new TextMapGetter<Map<String, String>>() {
                    @Override
                    public Iterable<String> keys(Map<String, String> carrier) {
                        return carrier.keySet();
                    }

                    @Override
                    public String get(Map<String, String> carrier, String key) {
                        return carrier.get(key);
                    }
                }
        );
    }
}
