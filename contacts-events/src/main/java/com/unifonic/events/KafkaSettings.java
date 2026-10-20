package com.unifonic.events;

import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.apache.kafka.clients.CommonClientConfigs;
import java.util.Properties;
import java.util.Optional;

@ApplicationScoped
public class KafkaSettings {
    @ConfigProperty(name="contacts.kafka.bootstrap-servers", defaultValue="localhost:9092") String bootstrap;
    @ConfigProperty(name="contacts.kafka.topic", defaultValue="contacts.contact-created.v1") String topic;
    @ConfigProperty(name="contacts.kafka.counts-topic", defaultValue="contacts.reporter.contact-counts.v1") String countsTopic;
    @ConfigProperty(name="contacts.kafka.security-protocol", defaultValue="PLAINTEXT") String security;
    @ConfigProperty(name="contacts.kafka.sasl-mechanism", defaultValue="PLAIN") String mechanism;
    @ConfigProperty(name="contacts.kafka.sasl-jaas-config") Optional<String> jaas;
    @ConfigProperty(name="contacts.kafka.partitions", defaultValue="3") int partitions;
    @ConfigProperty(name="contacts.kafka.replication-factor", defaultValue="1") short replication;

    public String topic() { return topic; }
    public String countsTopic() { return countsTopic; }
    public int partitions() { return partitions; }
    public short replication() { return replication; }
    public Properties clientProperties() {
        var properties = new Properties();
        properties.put(CommonClientConfigs.BOOTSTRAP_SERVERS_CONFIG, bootstrap);
        properties.put(CommonClientConfigs.SECURITY_PROTOCOL_CONFIG, security);
        if (security.startsWith("SASL")) {
            properties.put("sasl.mechanism", mechanism);
            jaas.ifPresent(value -> properties.put("sasl.jaas.config", value));
        }
        return properties;
    }
}
