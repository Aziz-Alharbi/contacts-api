package com.unifonic.events;

import io.micrometer.core.instrument.MeterRegistry;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.trace.*;
import io.opentelemetry.context.*;
import io.opentelemetry.context.propagation.TextMapGetter;
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.MDC;
import java.util.*;

/** Bounded metric dimensions and explicit context scopes for scheduler/stream threads. */
@ApplicationScoped
public class EventTelemetry {
    private static final TextMapGetter<Map<String, String>> GETTER = new TextMapGetter<>() {
        public Iterable<String> keys(Map<String, String> carrier) { return carrier.keySet(); }
        public String get(Map<String, String> carrier, String key) { return carrier.get(key); }
    };
    private final MeterRegistry registry;
    private final Tracer tracer;

    @Inject
    public EventTelemetry(MeterRegistry registry, OpenTelemetry telemetry) {
        this.registry = registry;
        this.tracer = telemetry.getTracer("contacts.events");
    }

    public void result(String operation, String broker, String result) {
        registry.counter("contacts.events", "operation", operation, "broker", broker, "result", result).increment();
    }

    public static String traceParent() {
        Map<String, String> carrier = new HashMap<>();
        W3CTraceContextPropagator.getInstance().inject(Context.current(), carrier, Map::put);
        return carrier.get("traceparent");
    }

    public EventScope open(String parent, ContactCreatedEvent event, String name, SpanKind kind) {
        Context context = parent == null ? Context.current() : W3CTraceContextPropagator.getInstance()
                .extract(Context.root(), Map.of("traceparent", parent), GETTER);
        Span span = tracer.spanBuilder(name).setParent(context).setSpanKind(kind).startSpan();
        return new EventScope(span, event);
    }

    public static final class EventScope implements AutoCloseable {
        private final Span span;
        private final Scope scope;
        private final Map<String, Object> previous = new HashMap<>();
        private EventScope(Span span, ContactCreatedEvent event) {
            this.span = span;
            for (String key : List.of("correlationId", "eventId", "traceId", "spanId")) previous.put(key, MDC.get(key));
            scope = span.makeCurrent();
            event(event);
            if (span.getSpanContext().isValid()) {
                MDC.put("traceId", span.getSpanContext().getTraceId());
                MDC.put("spanId", span.getSpanContext().getSpanId());
            }
        }
        public void event(ContactCreatedEvent event) {
            if (event == null) {
                MDC.remove("correlationId");
                MDC.remove("eventId");
            } else {
                MDC.put("correlationId", event.correlationId().toString());
                MDC.put("eventId", event.eventId().toString());
                span.setAttribute("contacts.event_id", event.eventId().toString());
                span.setAttribute("contacts.correlation_id", event.correlationId().toString());
            }
        }
        public void failed() { span.setStatus(StatusCode.ERROR); }
        @Override public void close() {
            previous.forEach((key, value) -> { if (value == null) MDC.remove(key); else MDC.put(key, value); });
            scope.close();
            span.end();
        }
    }
}
