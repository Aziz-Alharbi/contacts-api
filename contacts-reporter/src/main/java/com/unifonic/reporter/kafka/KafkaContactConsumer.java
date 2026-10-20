package com.unifonic.reporter.kafka;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.unifonic.events.*;
import io.quarkus.scheduler.Scheduled;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.apache.kafka.streams.*;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;
import java.time.Duration;
import java.util.Properties;

@ApplicationScoped
public class KafkaContactConsumer {
    private static final Logger LOG = Logger.getLogger(KafkaContactConsumer.class);
    @Inject KafkaSettings settings;
    @Inject ObjectMapper json;
    @ConfigProperty(name="contacts.kafka.enabled", defaultValue="true") boolean enabled;
    @ConfigProperty(name="contacts.kafka.application-id", defaultValue="contacts-reporter-v1") String applicationId;
    @ConfigProperty(name="contacts.kafka.state-dir", defaultValue=".kafka-state") String stateDir;
    private KafkaStreams streams;

    public static Properties streamProperties(KafkaSettings settings, String applicationId, String stateDir) {
        var properties = settings.clientProperties();
        properties.put(StreamsConfig.APPLICATION_ID_CONFIG, applicationId);
        properties.put(StreamsConfig.STATE_DIR_CONFIG, stateDir);
        properties.put(StreamsConfig.PROCESSING_GUARANTEE_CONFIG, StreamsConfig.EXACTLY_ONCE_V2);
        properties.put(StreamsConfig.REPLICATION_FACTOR_CONFIG, (int) settings.replication());
        properties.put(StreamsConfig.NUM_STREAM_THREADS_CONFIG, 1);
        properties.put(StreamsConfig.COMMIT_INTERVAL_MS_CONFIG, 100);
        properties.put(StreamsConfig.consumerPrefix("auto.offset.reset"), "earliest");
        return properties;
    }

    @Scheduled(every="5s", concurrentExecution=Scheduled.ConcurrentExecution.SKIP)
    synchronized void start() {
        if (!enabled || streams != null) return;
        try {
            KafkaTopics.ensure(settings);
            streams = new KafkaStreams(ContactEventTopology.build(settings.topic(), settings.countsTopic(), json),
                    streamProperties(settings, applicationId, stateDir));
            streams.setStateListener((next, previous) -> LOG.infof("kafka consumer state=%s previous=%s", next, previous));
            streams.setUncaughtExceptionHandler(failure -> {
                LOG.errorf("kafka consumer stopped reason=%s; inspect the source record before restarting",
                        failure.getClass().getSimpleName());
                return org.apache.kafka.streams.errors.StreamsUncaughtExceptionHandler.StreamThreadExceptionResponse.SHUTDOWN_CLIENT;
            });
            streams.start();
        } catch (Exception failure) {
            if (failure instanceof InterruptedException) Thread.currentThread().interrupt();
            if (streams != null) streams.close(Duration.ofSeconds(5));
            streams = null;
            LOG.warnf("kafka consumer startup deferred reason=%s", failure.getClass().getSimpleName());
        }
    }

    @PreDestroy
    public synchronized void close() { if (streams != null) streams.close(Duration.ofSeconds(15)); }
}
