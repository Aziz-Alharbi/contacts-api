package com.unifonic.contacts.events;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.unifonic.events.RabbitPublisher;
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
    @ConfigProperty(name = "contacts.messaging.enabled", defaultValue = "true") boolean enabled;

    @Scheduled(every = "1s", concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
    @Transactional
    void dispatch() {
        if (!enabled) return;
        var rows = em.createQuery("from ContactOutbox order by occurredAt, eventId", ContactOutbox.class)
                .setMaxResults(20).setLockMode(LockModeType.PESSIMISTIC_WRITE).getResultList();
        for (var row : rows) {
            try {
                publisher.publish(row.event(), json.writeValueAsString(row.event()));
                em.remove(row);
                LOG.infof("contact-created published eventId=%s correlationId=%s contactId=%s",
                        row.eventId, row.correlationId, row.contactId);
            } catch (Exception failure) {
                if (failure instanceof InterruptedException) Thread.currentThread().interrupt();
                LOG.warnf("contact-created publication deferred eventId=%s reason=%s",
                        row.eventId, failure.getClass().getSimpleName());
                // Keep the unconfirmed row; retry on the next scheduled run.
                break;
            }
        }
    }
}
