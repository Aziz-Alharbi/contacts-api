package com.unifonic.events;

import com.rabbitmq.client.Channel;
import java.io.IOException;
import java.util.Map;

public final class RabbitTopology {
    public static final String EXCHANGE = "contacts.events";
    public static final String KEY = "contact.created.v1";
    public static final String QUEUE = "contacts-reporter.contact-created.v1";
    public static final String DLX = "contacts.events.dlx";
    public static final String DLQ = QUEUE + ".dlq";

    private RabbitTopology() {}

    public static void declare(Channel channel) throws IOException {
        channel.exchangeDeclare(EXCHANGE, "direct", true);
        channel.exchangeDeclare(DLX, "direct", true);
        channel.queueDeclare(DLQ, true, false, false, Map.of("x-queue-type", "quorum"));
        channel.queueBind(DLQ, DLX, KEY);
        // Quorum at-least-once dead-lettering keeps rejected messages until the DLQ confirms them.
        channel.queueDeclare(QUEUE, true, false, false, Map.of(
                "x-queue-type", "quorum", "x-dead-letter-exchange", DLX,
                "x-dead-letter-routing-key", KEY, "x-dead-letter-strategy", "at-least-once",
                "x-overflow", "reject-publish"));
        channel.queueBind(QUEUE, EXCHANGE, KEY);
    }
}
