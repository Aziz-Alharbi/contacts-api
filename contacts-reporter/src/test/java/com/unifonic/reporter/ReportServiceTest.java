package com.unifonic.reporter;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.ws.rs.WebApplicationException;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ReportServiceTest {
    ReportService service;
    ContactsClient client;
    @BeforeEach void setup() { client=mock(ContactsClient.class); service=new ReportService(); service.contacts=client; }
    @Test void countFetchesOnlyOneItemAndUsesTotalRatherThanPageLength() throws Exception {
        when(client.contacts(0,1)).thenReturn(new ObjectMapper().readTree("{\"total\":42,\"items\":[{\"id\":17}],\"page\":0,\"size\":1}"));
        assertEquals(42,service.count().contactCount()); verify(client).contacts(0,1); verifyNoMoreInteractions(client);
    }
    @Test void transportFailureReturns503WithoutSensitiveDetailsOrCount() {
        when(client.contacts(0,1)).thenThrow(new IllegalStateException("sensitive transport detail"));
        var error=assertThrows(WebApplicationException.class,()->service.count());
        assertEquals(503,error.getResponse().getStatus());
        assertFalse(error.getResponse().getEntity().toString().contains("sensitive transport detail"));
        assertFalse(error.getResponse().getEntity().toString().contains("contactCount"));
    }
    @Test void nullResponseReturns502RatherThanZero() {
        var error=assertThrows(WebApplicationException.class,()->service.count()); assertEquals(502,error.getResponse().getStatus());
    }
    @ParameterizedTest @ValueSource(strings={
        "{\"total\":1,\"items\":[{}],\"page\":1,\"size\":1}",
        "{\"total\":1,\"items\":[{}],\"page\":0,\"size\":2}",
        "{\"total\":2,\"items\":[{},{}],\"page\":0,\"size\":1}",
        "{\"total\":9223372036854775808,\"items\":[{}],\"page\":0,\"size\":1}",
        "{\"total\":1,\"items\":[{}],\"page\":4294967296,\"size\":1}",
        "{\"total\":1,\"items\":[{}],\"page\":0,\"size\":4294967297}",
        "{\"total\":1.5,\"items\":[{}],\"page\":0,\"size\":1}"})
    void invalidPageMetadataCannotProduceReport(String body) throws Exception {
        when(client.contacts(0,1)).thenReturn(new ObjectMapper().readTree(body));
        assertEquals(502,assertThrows(WebApplicationException.class,()->service.count()).getResponse().getStatus());
    }
}
