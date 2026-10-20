package com.unifonic.reporter.kafka;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.unifonic.events.ContactCreatedEvent;
import org.apache.kafka.common.serialization.*;
import org.apache.kafka.streams.*;
import org.apache.kafka.streams.errors.StreamsException;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class ContactEventTopologyTest {
    @TempDir Path state;
    ObjectMapper json = new ObjectMapper().findAndRegisterModules();
    TopologyTestDriver driver;
    TestInputTopic<String,String> input;
    TestOutputTopic<String,String> output;
    @BeforeEach void setup() {
        var properties = new Properties();
        properties.put(StreamsConfig.APPLICATION_ID_CONFIG,"topology-test");
        properties.put(StreamsConfig.BOOTSTRAP_SERVERS_CONFIG,"unused:9092");
        properties.put(StreamsConfig.STATE_DIR_CONFIG,state.toString());
        driver = new TopologyTestDriver(ContactEventTopology.build("events","counts",json),properties);
        input=driver.createInputTopic("events",new StringSerializer(),new StringSerializer());
        output=driver.createOutputTopic("counts",new StringDeserializer(),new StringDeserializer());
    }
    @AfterEach void close() { driver.close(); }
    ContactCreatedEvent event(long contact) { return new ContactCreatedEvent(UUID.randomUUID(),UUID.randomUUID(),contact,Instant.now()); }
    @Test void duplicateDoesNotChangeResultAndContactEventsKeepInputOrder() throws Exception {
        var first=event(42); var next=event(42); var other=event(43);
        input.pipeInput("42",json.writeValueAsString(first));
        input.pipeInput("43",json.writeValueAsString(other));
        input.pipeInput("42",json.writeValueAsString(first));
        input.pipeInput("42",json.writeValueAsString(next));
        var results=output.readValuesToList().stream().map(value -> {
            try { return json.readValue(value,ContactEventCount.class); }
            catch(Exception failure) { throw new AssertionError(failure); }
        }).toList();
        assertEquals(List.of(first.eventId(),other.eventId(),next.eventId()),results.stream().map(ContactEventCount::eventId).toList());
        assertEquals(List.of(1L,1L,2L),results.stream().map(ContactEventCount::count).toList());
        assertEquals(2L,driver.<String,Long>getKeyValueStore(ContactEventTopology.COUNTS).get("42"));
        assertEquals(1L,driver.<String,Long>getKeyValueStore(ContactEventTopology.COUNTS).get("43"));
    }
    @Test void invalidPayloadStopsProcessingWithoutCountingIt() {
        assertThrows(StreamsException.class,() -> input.pipeInput("42","{}"));
        assertTrue(output.isEmpty());
        assertNull(driver.<String,Long>getKeyValueStore(ContactEventTopology.COUNTS).get("42"));
    }
    @Test void wrongKeyIsRejectedBeforeAnyStateChange() throws Exception {
        var event=event(42);
        assertThrows(StreamsException.class,() -> input.pipeInput("43",json.writeValueAsString(event)));
        assertTrue(output.isEmpty());
        assertNull(driver.<String,Long>getKeyValueStore(ContactEventTopology.SEEN).get(event.eventId().toString()));
    }
}
