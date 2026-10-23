package com.unifonic.contacts.events;

import io.micrometer.core.instrument.MeterRegistry;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import java.util.concurrent.atomic.AtomicLong;

/** Cached database snapshots; use max, not sum, across API replicas. */
@ApplicationScoped
public class OutboxMetrics {
    private final AtomicLong rabbit = new AtomicLong();
    private final AtomicLong kafka = new AtomicLong();
    @Inject EntityManager em;
    @ConfigProperty(name="contacts.messaging.enabled", defaultValue="true") boolean enabled;

    @Inject
    public OutboxMetrics(MeterRegistry registry) {
        registry.gauge("contacts.outbox.pending", java.util.List.of(io.micrometer.core.instrument.Tag.of("broker", "rabbitmq")), rabbit);
        registry.gauge("contacts.outbox.pending", java.util.List.of(io.micrometer.core.instrument.Tag.of("broker", "kafka")), kafka);
    }
    @Scheduled(every="10s", concurrentExecution=Scheduled.ConcurrentExecution.SKIP)
    @Transactional
    void update() {
        if (!enabled) return;
        rabbit.set(em.createQuery("select count(o) from ContactOutbox o where o.rabbitPublished = false", Long.class).getSingleResult());
        kafka.set(em.createQuery("select count(o) from ContactOutbox o where o.kafkaPublished = false", Long.class).getSingleResult());
    }
}
