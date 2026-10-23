package com.unifonic.events;

import com.rabbitmq.client.AMQP;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;

@ApplicationScoped
public class RabbitPublisher {
    private final RabbitConnection broker;

    @Inject
    public RabbitPublisher(RabbitConnection broker) { this.broker = broker; }

    public void publish(ContactCreatedEvent event, String json) throws Exception {
        try (var channel = broker.openChannel()) {
            channel.confirmSelect();
            AtomicBoolean returned = new AtomicBoolean();
            channel.addReturnListener(message -> returned.set(true));
            var headers = new java.util.HashMap<String, Object>();
            String parent = EventTelemetry.traceParent();
            if (parent != null) headers.put("traceparent", parent);
            var properties = new AMQP.BasicProperties.Builder()
                    .contentType("application/json").contentEncoding("UTF-8").deliveryMode(2)
                    .headers(headers).type("ContactCreatedEvent.v1").messageId(event.eventId().toString())
                    .correlationId(event.correlationId().toString()).build();
            channel.basicPublish(RabbitTopology.EXCHANGE, RabbitTopology.KEY, true, properties,
                    json.getBytes(StandardCharsets.UTF_8));
            channel.waitForConfirmsOrDie(5000);
            if (returned.get()) throw new IOException("ContactCreatedEvent was unroutable");
        }
    }
}
