package com.unifonic.reporter.events;

import com.unifonic.events.ContactCreatedEvent;
import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;
import java.util.Optional;
import java.util.UUID;

@ApplicationScoped
public class ContactCreatedHandler {
    private static final Logger LOG = Logger.getLogger(ContactCreatedHandler.class);
    @ConfigProperty(name = "contacts.messaging.fail-event-id") Optional<UUID> failEventId;

    public void handle(ContactCreatedEvent event, int attempt) {
        if (failEventId.filter(event.eventId()::equals).isPresent())
            throw new IllegalStateException("Deliberate event processing failure");
        LOG.infof("contact-created consumed eventId=%s correlationId=%s contactId=%s attempt=%d result=success",
                event.eventId(), event.correlationId(), event.contactId(), attempt);
    }
}
