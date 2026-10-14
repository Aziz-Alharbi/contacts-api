package com.unifonic.contacts.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.test.junit.*;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.common.http.TestHTTPResource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import java.net.URI;
import java.net.http.*;
import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
@TestProfile(ContactsSecurityTest.Profile.class)
@QuarkusTestResource(OidcTestServer.class)
class HttpErrorsTest {
    @TestHTTPResource URI base;
    HttpResponse<String> request(String method,String path,String token,String body,String type) throws Exception {
        var builder=HttpRequest.newBuilder(base.resolve(path)).header("Content-Type",type);
        if (token!=null) builder.header("Authorization","Bearer "+token);
        try(var client=HttpClient.newHttpClient()) {
            return client.send(builder.method(method,body==null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body)).build(),HttpResponse.BodyHandlers.ofString());
        }
    }
    void assertError(HttpResponse<String> response,int expected) throws Exception {
        assertEquals(expected,response.statusCode(),response.body());
        assertTrue(response.headers().firstValue("Content-Type").orElse("").startsWith("application/json"));
        var json=new ObjectMapper().readTree(response.body());
        assertEquals(expected,json.path("status").asInt()); assertFalse(json.path("error").asText().isBlank()); assertFalse(json.path("message").asText().isBlank());
    }
    @ParameterizedTest
    @CsvSource({"GET,/api/v1/contacts", "POST,/api/v1/contacts", "GET,/api/v1/contacts/999999", "PUT,/api/v1/contacts/999999", "DELETE,/api/v1/contacts/999999",
        "GET,/api/v1/groups", "POST,/api/v1/groups", "GET,/api/v1/groups/999999", "PUT,/api/v1/groups/999999", "DELETE,/api/v1/groups/999999", "GET,/api/v1/groups/999999/contacts"})
    void everyApiEndpointRejectsAnonymousRequestWithUniform401(String method,String path) throws Exception {
        var response=request(method,path,null,"{}","application/json");
        assertError(response,401); assertTrue(response.headers().firstValue("WWW-Authenticate").orElse("").contains("Bearer"));
    }
    @Test void invalidTokenReturnsUniform401() throws Exception { assertError(request("GET","/api/v1/contacts","invalid-token",null,"application/json"),401); }
    @Test void forbiddenRoleReturnsUniform403() throws Exception { assertError(request("DELETE","/api/v1/contacts/999999",OidcTestServer.token("error-user","user"),null,"application/json"),403); }
    @Test void missingRecordReturnsUniform404() throws Exception { assertError(request("GET","/api/v1/contacts/999999",OidcTestServer.token("error-admin","admin"),null,"application/json"),404); }
    @Test void wrongUrlReturnsUniform404() throws Exception { assertError(request("GET","/does-not-exist",OidcTestServer.token("error-admin","admin"),null,"application/json"),404); }
    @Test void wrongMethodReturnsUniform405AndAllowHeader() throws Exception {
        var response=request("PATCH","/api/v1/contacts",OidcTestServer.token("error-admin","admin"),"{}","application/json");
        assertError(response,405); assertTrue(response.headers().firstValue("Allow").isPresent());
    }
    @Test void malformedJsonReturnsUniform400() throws Exception { assertError(request("POST","/api/v1/contacts",OidcTestServer.token("error-user","user"),"{invalid","application/json"),400); }
    @Test void unsupportedContentTypeReturnsUniform415() throws Exception { assertError(request("POST","/api/v1/contacts",OidcTestServer.token("error-user","user"),"text","text/plain"),415); }
    @Test void invalidFieldsReturnUniform400WithViolationDetails() throws Exception {
        var response=request("POST","/api/v1/contacts",OidcTestServer.token("error-user","user"),"{}","application/json");
        assertError(response,400); assertTrue(new ObjectMapper().readTree(response.body()).path("violations").size()>0);
    }
    @ParameterizedTest @CsvSource({"user,403","admin,404"})
    void contactDeleteChecksRoleBeforeMissingRecord(String role,int expected) throws Exception {
        assertError(request("DELETE","/api/v1/contacts/999999",OidcTestServer.token("error-delete",role),null,"application/json"),expected);
    }
    @ParameterizedTest
    @CsvSource({"GET,/api/v1/contacts", "POST,/api/v1/contacts", "GET,/api/v1/contacts/999999", "PUT,/api/v1/contacts/999999", "DELETE,/api/v1/contacts/999999",
        "GET,/api/v1/groups", "POST,/api/v1/groups", "GET,/api/v1/groups/999999", "PUT,/api/v1/groups/999999", "DELETE,/api/v1/groups/999999", "GET,/api/v1/groups/999999/contacts"})
    void everyApiEndpointRejectsUnrelatedRoleWithUniform403(String method,String path) throws Exception {
        assertError(request(method,path,OidcTestServer.token("unrelated-role","unrelated"),"{\"firstName\":\"Valid\",\"lastName\":\"Valid\",\"email\":\"valid@example.test\",\"name\":\"Valid\"}","application/json"),403);
    }
}
