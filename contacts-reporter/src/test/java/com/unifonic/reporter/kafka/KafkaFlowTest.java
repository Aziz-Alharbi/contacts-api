package com.unifonic.reporter.kafka;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.unifonic.events.*;
import org.apache.kafka.clients.consumer.*;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.streams.*;
import org.apache.kafka.streams.state.QueryableStoreTypes;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.time.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@EnabledIfSystemProperty(named="kafka.integration", matches="true")
class KafkaFlowTest {
    @TempDir Path state;
    final ObjectMapper json = new ObjectMapper().findAndRegisterModules();
    final String id = "contact-integration-" + UUID.randomUUID();

    KafkaSettings settings() {
        var settings=mock(KafkaSettings.class);
        when(settings.clientProperties()).thenAnswer(call -> {
            var properties=new Properties(); properties.put("bootstrap.servers","localhost:9094"); return properties;
        });
        when(settings.topic()).thenReturn(id+".events");
        when(settings.countsTopic()).thenReturn(id+".counts");
        when(settings.partitions()).thenReturn(3); when(settings.replication()).thenReturn((short)1);
        return settings;
    }
    KafkaConsumer<String,String> reader(KafkaSettings settings, String topic) {
        var properties=settings.clientProperties();
        properties.put(ConsumerConfig.GROUP_ID_CONFIG,id+".read-"+UUID.randomUUID());
        properties.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG,false);
        properties.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG,"earliest");
        properties.put(ConsumerConfig.ISOLATION_LEVEL_CONFIG,"read_committed");
        var consumer=new KafkaConsumer<>(properties,new StringDeserializer(),new StringDeserializer());
        consumer.assign(consumer.partitionsFor(topic).stream().map(part -> new TopicPartition(topic,part.partition())).toList());
        consumer.seekToBeginning(consumer.assignment());
        return consumer;
    }
    List<ConsumerRecord<String,String>> read(KafkaConsumer<String,String> consumer,int count) {
        var records=new ArrayList<ConsumerRecord<String,String>>();
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(90);
        while(records.size()<count && System.nanoTime()<deadline)
            consumer.poll(Duration.ofMillis(200)).forEach(records::add);
        assertEquals(count,records.size(),"Timed out or received unexpected additional results");
        return records;
    }
    KafkaStreams start(KafkaSettings settings,String directory) {
        var streams=new KafkaStreams(ContactEventTopology.build(settings.topic(),settings.countsTopic(),json,new com.unifonic.events.EventTelemetry(new io.micrometer.core.instrument.simple.SimpleMeterRegistry(), io.opentelemetry.api.OpenTelemetry.noop())),
                KafkaContactConsumer.streamProperties(settings,id,state.resolve(directory).toString()));
        streams.start(); return streams;
    }
    ContactCreatedEvent event(long contact) { return new ContactCreatedEvent(UUID.randomUUID(),UUID.randomUUID(),contact,Instant.now()); }

    @Test void realBrokerPreservesOrderAndRestoresDeduplicationWithoutLocalState() throws Exception {
        var settings=settings(); KafkaTopics.ensure(settings);
        var publisher=new KafkaPublisher(settings);
        var first=event(42); var second=event(42); var third=event(42); var other=event(43);
        KafkaStreams streams=null;
        try(var results=reader(settings,settings.countsTopic()); var source=reader(settings,settings.topic())) {
            streams=start(settings,"initial");
            publisher.publish(first,json.writeValueAsString(first));
            publisher.publish(first,json.writeValueAsString(first));
            publisher.publish(other,json.writeValueAsString(other));
            publisher.publish(second,json.writeValueAsString(second));
            var before=read(results,3);
            var contactResults=before.stream().filter(record -> record.key().equals("42"))
                    .map(record -> decode(record.value())).toList();
            assertEquals(List.of(first.eventId(),second.eventId()),contactResults.stream().map(ContactEventCount::eventId).toList());
            assertEquals(List.of(1L,2L),contactResults.stream().map(ContactEventCount::count).toList());
            var sent=read(source,4).stream().filter(record -> record.key().equals("42")).toList();
            assertEquals(3,sent.size());
            assertEquals(1,sent.stream().map(ConsumerRecord::partition).distinct().count());
            assertTrue(sent.get(0).offset()<sent.get(1).offset());
            assertTrue(sent.get(1).offset()<sent.get(2).offset());
            assertEquals(List.of(first.eventId(),first.eventId(),second.eventId()),sent.stream().map(record -> {
                try { return json.readValue(record.value(),ContactCreatedEvent.class).eventId(); }
                catch(Exception failure) { throw new AssertionError(failure); }
            }).toList());
            assertTrue(streams.close(Duration.ofSeconds(20)));
            // A fresh directory forces restoration from broker changelogs, not an in-memory/local set.
            streams=start(settings,"restored");
            publisher.publish(first,json.writeValueAsString(first));
            publisher.publish(third,json.writeValueAsString(third));
            var after=read(results,1);
            var result=decode(after.getFirst().value());
            assertEquals(third.eventId(),result.eventId());
            assertEquals(third.correlationId(),result.correlationId());
            assertEquals(42L,result.contactId()); assertEquals(3,result.count());
            assertTrue(results.poll(Duration.ofSeconds(1)).isEmpty(),"Duplicate must not produce another result");
        } finally {
            if(streams!=null) streams.close(Duration.ofSeconds(20));
            publisher.close();
        }
    }
    ContactEventCount decode(String value) {
        try { return json.readValue(value,ContactEventCount.class); }
        catch(Exception failure) { throw new AssertionError(failure); }
    }
}
