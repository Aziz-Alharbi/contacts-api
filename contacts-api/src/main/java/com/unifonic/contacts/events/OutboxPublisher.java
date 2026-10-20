package com.unifonic.contacts.events;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.unifonic.events.RabbitPublisher;
import com.unifonic.events.KafkaPublisher;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import jakarta.transaction.Transactional;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

@ApplicationScoped
public class OutboxPublisher {
    private static final Logger LOG = Logger.getLogger(OutboxPublisher.class);
    @Inject EntityManager em;
    @Inject ObjectMapper json;
    @Inject RabbitPublisher publisher;
    @Inject KafkaPublisher kafka;
    @ConfigProperty(name = "contacts.messaging.enabled", defaultValue = "true") boolean enabled;

    @Scheduled(every = "1s", concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
    @Transactional
    public void dispatch() {
        if (!enabled) return;
        if (em.find(OutboxRelayLock.class, 1, LockModeType.PESSIMISTIC_WRITE) == null)
            throw new IllegalStateException("Missing outbox relay lock; apply database migrations");
        dispatchBroker("rabbitmq");
        if (!Thread.currentThread().isInterrupted()) dispatchBroker("kafka");
    }

    private void dispatchBroker(String broker) {
        boolean rabbit = broker.equals("rabbitmq");
        String pending = rabbit ? "rabbitPublished" : "kafkaPublished";
        var rows = em.createQuery("from ContactOutbox where " + pending + " = false order by sequenceNumber", ContactOutbox.class)
                .setMaxResults(20).setLockMode(LockModeType.PESSIMISTIC_WRITE).getResultList();
        for (var row : rows) {
            try {
                String body = json.writeValueAsString(row.event());
                if (rabbit) publisher.publish(row.event(), body);
                else kafka.publish(row.event(), body);
                if (rabbit) row.rabbitPublished = true;
                else row.kafkaPublished = true;
                published(row, broker);
                if (row.rabbitPublished && row.kafkaPublished) em.remove(row);
            } catch (Exception failure) {
                deferred(row, broker, failure);
                // Do not let later events overtake the failed event on this broker.
                break;
            }
        }
    }

    private void published(ContactOutbox row, String broker) {
        LOG.infof("contact-created published broker=%s eventId=%s correlationId=%s contactId=%s",
                broker, row.eventId, row.correlationId, row.contactId);
    }

    private void deferred(ContactOutbox row, String broker, Exception failure) {
        if (failure instanceof InterruptedException) Thread.currentThread().interrupt();
        LOG.warnf("contact-created publication deferred broker=%s eventId=%s reason=%s",
                broker, row.eventId, failure.getClass().getSimpleName());
    }
}
