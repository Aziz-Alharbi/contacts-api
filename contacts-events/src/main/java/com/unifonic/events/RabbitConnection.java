package com.unifonic.events;

import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Connection;
import com.rabbitmq.client.ConnectionFactory;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import java.io.IOException;
import java.util.concurrent.TimeoutException;

@ApplicationScoped
public class RabbitConnection {
    @ConfigProperty(name = "rabbitmq.host", defaultValue = "localhost") String host;
    @ConfigProperty(name = "rabbitmq.port", defaultValue = "5672") int port;
    @ConfigProperty(name = "rabbitmq.username", defaultValue = "contacts") String username;
    @ConfigProperty(name = "rabbitmq.password", defaultValue = "contacts-dev-only") String password;
    @ConfigProperty(name = "rabbitmq.virtual-host", defaultValue = "/") String virtualHost;
    private Connection connection;

    public synchronized Channel openChannel() throws IOException, TimeoutException {
        if (connection == null || !connection.isOpen()) {
            ConnectionFactory factory = new ConnectionFactory();
            factory.setHost(host);
            factory.setPort(port);
            factory.setUsername(username);
            factory.setPassword(password);
            factory.setVirtualHost(virtualHost);
            factory.setConnectionTimeout(3000);
            factory.setHandshakeTimeout(3000);
            // Scheduled jobs reconnect explicitly; do not hide an unavailable connection.
            factory.setAutomaticRecoveryEnabled(false);
            connection = factory.newConnection("contacts-events");
        }
        Channel channel = connection.createChannel();
        try {
            RabbitTopology.declare(channel);
            return channel;
        } catch (IOException | RuntimeException failure) {
            channel.abort();
            throw failure;
        }
    }

    @PreDestroy
    public synchronized void close() throws IOException {
        if (connection != null && connection.isOpen()) connection.close();
    }
}
