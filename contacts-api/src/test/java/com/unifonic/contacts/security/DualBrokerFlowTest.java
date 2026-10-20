package com.unifonic.contacts.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.unifonic.contacts.events.*;
import com.unifonic.events.*;
import io.quarkus.test.junit.*;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.common.http.TestHTTPResource;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import org.apache.kafka.clients.consumer.*;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
@TestProfile(DualBrokerFlowTest.Profile.class)
@QuarkusTestResource(OidcTestServer.class)
@EnabledIfSystemProperty(named="kafka.integration",matches="true")
class DualBrokerFlowTest {
    public static class Profile implements QuarkusTestProfile {
        @Override public Map<String,String> getConfigOverrides() {
            var config = new HashMap<>(new ContactsSecurityTest.Profile().getConfigOverrides());
            config.putAll(Map.of("quarkus.scheduler.enabled","false", "contacts.messaging.enabled","true",
                    "rabbitmq.port","5673", "rabbitmq.username","contacts-test",
                    "rabbitmq.password","contacts-test-only", "contacts.kafka.bootstrap-servers","localhost:9094",
                    "contacts.kafka.topic","contacts.integration.created.v1",
                    "contacts.kafka.counts-topic","contacts.integration.counts.v1"));
            return config;
        }
    }
    @TestHTTPResource URI base;
    @Inject EntityManager em;
    @Inject OutboxPublisher relay;
    @Inject RabbitConnection rabbit;
    @Inject KafkaSettings kafka;
    @Inject ObjectMapper json;

    @Test void committedContactPublishesIdenticalEventToBothRealBrokers() throws Exception {
        KafkaTopics.ensure(kafka);
        io.quarkus.narayana.jta.QuarkusTransaction.requiringNew().run(() -> {
            var lock=new OutboxRelayLock(); lock.id=1; em.persist(lock);
        });
        String correlation=UUID.randomUUID().toString();
        try(var channel=rabbit.openChannel(); var client=HttpClient.newHttpClient()) {
            assertEquals(0,channel.messageCount(RabbitTopology.QUEUE),"Use an isolated empty RabbitMQ test broker");
            var request=HttpRequest.newBuilder(base.resolve("/api/v1/contacts"))
                    .header("Authorization","Bearer "+OidcTestServer.token("dual-broker-owner","user"))
                    .header("Content-Type","application/json").header("X-Correlation-ID",correlation)
                    .POST(HttpRequest.BodyPublishers.ofString("{\"firstName\":\"Broker\",\"lastName\":\"Test\",\"email\":\"dual-"+UUID.randomUUID()+"@example.test\"}")).build();
            var response=client.send(request,HttpResponse.BodyHandlers.ofString());
            assertEquals(201,response.statusCode(),response.body());
            long contactId=json.readTree(response.body()).path("id").asLong();
            var saved=io.quarkus.narayana.jta.QuarkusTransaction.requiringNew().call(() ->
                    em.createQuery("from ContactOutbox where contactId = :id",ContactOutbox.class)
                            .setParameter("id",contactId).getSingleResult().event());
            assertEquals(correlation,saved.correlationId().toString());
            relay.dispatch();
            var count=io.quarkus.narayana.jta.QuarkusTransaction.requiringNew().call(() ->
                    em.createQuery("select count(o) from ContactOutbox o",Long.class).getSingleResult());
            assertEquals(0,count,"Both broker confirmations must precede outbox removal");
            var delivery=channel.basicGet(RabbitTopology.QUEUE,false);
            assertNotNull(delivery); assertEquals(saved,json.readValue(delivery.getBody(),ContactCreatedEvent.class));
            channel.basicAck(delivery.getEnvelope().getDeliveryTag(),false);
            var properties=kafka.clientProperties();
            properties.put(ConsumerConfig.GROUP_ID_CONFIG,"dual-"+UUID.randomUUID());
            properties.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG,false);
            properties.put(ConsumerConfig.ISOLATION_LEVEL_CONFIG,"read_committed");
            try(var consumer=new KafkaConsumer<>(properties,new StringDeserializer(),new StringDeserializer())) {
                consumer.assign(consumer.partitionsFor(kafka.topic()).stream().map(part -> new TopicPartition(kafka.topic(),part.partition())).toList());
                consumer.seekToBeginning(consumer.assignment());
                boolean found=false; long deadline=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(15);
                while(!found && System.nanoTime()<deadline) {
                    for(var record:consumer.poll(Duration.ofMillis(200))) {
                        var event=json.readValue(record.value(),ContactCreatedEvent.class);
                        if(event.eventId().equals(saved.eventId())) {
                            assertEquals(saved,event); assertEquals(Long.toString(contactId),record.key()); found=true;
                        }
                    }
                }
                assertTrue(found,"Committed contact event must reach Kafka too");
            }
        }
    }
}
