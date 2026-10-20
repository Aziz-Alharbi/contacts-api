package com.unifonic.reporter.kafka;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.health.*;

@Readiness
@ApplicationScoped
public class KafkaConsumerReadiness implements HealthCheck {
    @Inject KafkaContactConsumer consumer;

    @Override public HealthCheckResponse call() {
        return HealthCheckResponse.named("kafka-consumer").status(consumer.isRunning()).build();
    }
}
