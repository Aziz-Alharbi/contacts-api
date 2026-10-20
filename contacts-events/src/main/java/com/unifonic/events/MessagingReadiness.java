package com.unifonic.events;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.microprofile.health.*;
import java.time.Duration;
import java.util.concurrent.TimeUnit;

@Readiness
@ApplicationScoped
public class MessagingReadiness implements HealthCheck {
    @Inject RabbitConnection rabbit;
    @Inject KafkaSettings kafka;
    @ConfigProperty(name="contacts.messaging.enabled", defaultValue="true") boolean enabled;

    @Override public HealthCheckResponse call() {
        if (!enabled) return HealthCheckResponse.up("event-brokers-disabled");
        var response = HealthCheckResponse.named("event-brokers");
        try (var channel = rabbit.openChannel()) {
            var properties = kafka.clientProperties();
            properties.put(AdminClientConfig.REQUEST_TIMEOUT_MS_CONFIG, 2000);
            properties.put(AdminClientConfig.DEFAULT_API_TIMEOUT_MS_CONFIG, 3000);
            var admin = Admin.create(properties);
            try {
                boolean ready = channel.isOpen() && !admin.describeCluster().nodes().get(3, TimeUnit.SECONDS).isEmpty();
                return response.status(ready).build();
            } finally { admin.close(Duration.ofMillis(200)); }
        } catch (Exception failure) {
            if (failure instanceof InterruptedException) Thread.currentThread().interrupt();
            return response.down().build();
        }
    }
}
