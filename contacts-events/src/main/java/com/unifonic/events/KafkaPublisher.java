package com.unifonic.events;

import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.apache.kafka.clients.producer.*;
import org.apache.kafka.common.header.internals.RecordHeader;
import org.apache.kafka.common.serialization.StringSerializer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.TimeUnit;

@ApplicationScoped
public class KafkaPublisher {
    private final KafkaSettings settings;

    @Inject
    public KafkaPublisher(KafkaSettings settings) { this.settings = settings; }
    Producer<String, String> producer;

    public synchronized void publish(ContactCreatedEvent event, String json) throws Exception {
        if (producer == null) {
            KafkaTopics.ensure(settings);
            producer = new KafkaProducer<>(producerProperties(settings));
        }
        var record = new ProducerRecord<String, String>(settings.topic(), event.contactId().toString(), json);
        record.headers().add(new RecordHeader("eventId", event.eventId().toString().getBytes(StandardCharsets.UTF_8)));
        record.headers().add(new RecordHeader("correlationId", event.correlationId().toString().getBytes(StandardCharsets.UTF_8)));
        record.headers().add(new RecordHeader("type", "ContactCreatedEvent.v1".getBytes(StandardCharsets.UTF_8)));
        producer.send(record).get(12, TimeUnit.SECONDS);
    }

    public static java.util.Properties producerProperties(KafkaSettings settings) {
        var properties = settings.clientProperties();
        properties.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        properties.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        properties.put(ProducerConfig.ACKS_CONFIG, "all");
        properties.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true);
        properties.put(ProducerConfig.MAX_IN_FLIGHT_REQUESTS_PER_CONNECTION, 1);
        properties.put(ProducerConfig.MAX_BLOCK_MS_CONFIG, 3000);
        properties.put(ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG, 3000);
        properties.put(ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG, 10000);
        return properties;
    }

    @PreDestroy
    public synchronized void close() { if (producer != null) producer.close(Duration.ofSeconds(5)); }
}
