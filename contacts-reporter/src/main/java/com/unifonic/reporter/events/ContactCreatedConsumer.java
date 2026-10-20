package com.unifonic.reporter.events;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.GetResponse;
import com.unifonic.events.*;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;
import java.io.IOException;

@ApplicationScoped
public class ContactCreatedConsumer {
    public static final int MAX_ATTEMPTS = 3;
    private static final Logger LOG = Logger.getLogger(ContactCreatedConsumer.class);
    @Inject RabbitConnection broker;
    @Inject ObjectMapper json;
    @Inject ContactCreatedHandler handler;
    @ConfigProperty(name = "contacts.messaging.enabled", defaultValue = "true") boolean enabled;

    @Scheduled(every = "1s", concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
    void poll() {
        if (!enabled) return;
        try (var channel = broker.openChannel()) {
            // One unacknowledged message at a time, bounded batch prevents a monopolized scheduler.
            for (int i = 0; i < 20; i++) {
                var delivery = channel.basicGet(RabbitTopology.QUEUE, false);
                if (delivery == null) break;
                process(channel, delivery);
            }
        } catch (Exception failure) {
            if (failure instanceof InterruptedException) Thread.currentThread().interrupt();
            LOG.warnf("contact-created polling deferred reason=%s", failure.getClass().getSimpleName());
        }
    }

    void process(Channel channel, GetResponse delivery) throws IOException {
        ContactCreatedEvent event = null;
        boolean processed = false;
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                event = json.readValue(delivery.getBody(), ContactCreatedEvent.class);
                if (event == null) throw new IllegalArgumentException("Missing event");
                handler.handle(event, attempt);
                processed = true;
                break;
            } catch (Exception failure) {
                LOG.warnf("contact-created failed eventId=%s correlationId=%s attempt=%d/%d reason=%s",
                        event == null ? "invalid" : event.eventId(),
                        event == null ? "invalid" : event.correlationId(), attempt, MAX_ATTEMPTS,
                        failure.getClass().getSimpleName());
            }
        }
        long tag = delivery.getEnvelope().getDeliveryTag();
        // ACK errors must never trigger handler retries or an additional NACK.
        if (processed) {
            channel.basicAck(tag, false);
        } else {
            channel.basicNack(tag, false, false);
            LOG.errorf("contact-created dead-lettered eventId=%s attempts=%d queue=%s",
                    event == null ? "invalid" : event.eventId(), MAX_ATTEMPTS, RabbitTopology.DLQ);
        }
    }
}
