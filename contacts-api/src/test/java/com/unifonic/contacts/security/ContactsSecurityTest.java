package com.unifonic.contacts.security;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.common.http.TestHTTPResource;
import org.junit.jupiter.api.Test;
import java.net.URI;
import java.net.http.*;
import java.time.Instant;
import static org.junit.jupiter.api.Assertions.*;

@io.quarkus.test.junit.TestProfile(ContactsSecurityTest.Profile.class)
@QuarkusTest
@QuarkusTestResource(OidcTestServer.class)
public class ContactsSecurityTest {
    public static class Profile implements io.quarkus.test.junit.QuarkusTestProfile {
        public java.util.Map<String,String> getConfigOverrides() {
            return java.util.Map.of("quarkus.datasource.db-kind","h2", "quarkus.datasource.jdbc.url","jdbc:h2:mem:security;MODE=MySQL;DB_CLOSE_DELAY=-1", "quarkus.datasource.username","sa", "quarkus.datasource.password","", "quarkus.flyway.migrate-at-start","false", "quarkus.hibernate-orm.schema-management.strategy","drop-and-create", "quarkus.compose.devservices.enabled","false");
        }
    }
    @TestHTTPResource URI base;
    int status(String token) throws Exception {
        var request=HttpRequest.newBuilder(base.resolve("/api/v1/contacts"));
        if(token!=null) request.header("Authorization","Bearer "+token);
        return HttpClient.newHttpClient().send(request.GET().build(),HttpResponse.BodyHandlers.discarding()).statusCode();
    }
    @Test void validServiceTokenSucceeds() throws Exception {
        assertEquals(200,status(OidcTestServer.token("contacts-api",Instant.now().getEpochSecond()+300,OidcTestServer.issuer,false)));

    }
    @Test void wrongAudienceIsRejected() throws Exception {
        assertEquals(401,status(OidcTestServer.token("other-api",Instant.now().getEpochSecond()+300,OidcTestServer.issuer,false)));
    }
    @Test void expiredTokenIsRejected() throws Exception {
        assertEquals(401,status(OidcTestServer.token("contacts-api",Instant.now().getEpochSecond()-300,OidcTestServer.issuer,false)));
    }
    @Test void wrongIssuerIsRejected() throws Exception {
        assertEquals(401,status(OidcTestServer.token("contacts-api",Instant.now().getEpochSecond()+300,"https://wrong-issuer.example",false)));
    }
    @Test void wrongSignatureIsRejected() throws Exception {
        assertEquals(401,status(OidcTestServer.token("contacts-api",Instant.now().getEpochSecond()+300,OidcTestServer.issuer,true)));
    }

    HttpResponse<String> request(String method, String path, String token, String body) throws Exception {
        var builder=HttpRequest.newBuilder(base.resolve(path)).header("Authorization","Bearer "+token);
        builder.header("Content-Type","application/json");
        return HttpClient.newHttpClient().send(builder.method(method, body==null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body)).build(),HttpResponse.BodyHandlers.ofString());
    }
    com.fasterxml.jackson.databind.JsonNode json(HttpResponse<String> response) throws Exception {
        return new com.fasterxml.jackson.databind.ObjectMapper().readTree(response.body());
    }
    long contact(String subject,String role,String email,Long group) throws Exception {
        String body="{\"firstName\":\"Security\",\"lastName\":\"Test\",\"email\":\""+email+"\",\"owner\":\"spoofed-owner\",\"groupId\":"+group+"}";
        var response=request("POST","/api/v1/contacts",OidcTestServer.token(subject,role),body);
        assertEquals(201,response.statusCode(),response.body());
        assertEquals(subject,json(response).get("owner").asText());
        return json(response).get("id").asLong();
    }
    long group(String name) throws Exception {
        var response=request("POST","/api/v1/groups",OidcTestServer.token("group-admin","admin"),"{\"name\":\""+name+"\"}");
        assertEquals(201,response.statusCode(),response.body());
        return json(response).get("id").asLong();
    }
    @Test void malformedBearerTokenReturns401() throws Exception {
        assertEquals(401,status("not.a.jwt"));
    }
    @Test void blankSubjectReturns401() throws Exception {
        assertEquals(401,status(OidcTestServer.token("","user")));
    }
    @Test void validTokenWithoutApplicationRoleReturns403() throws Exception {
        assertEquals(403,status(OidcTestServer.token("no-role-user","unrelated")));
    }
    @Test void missingBearerTokenReturns401() throws Exception { assertEquals(401,status(null)); }
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({"POST,/api/v1/groups","PUT,/api/v1/groups/999999","DELETE,/api/v1/groups/999999"})
    void ordinaryUserCannotPerformAdministratorGroupOperation(String method,String path) throws Exception {
        assertEquals(403,request(method,path,OidcTestServer.token("restricted-user","user"),"{\"name\":\"Forbidden\"}").statusCode());
    }
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({"POST,/api/v1/groups","PUT,/api/v1/groups/999999","DELETE,/api/v1/groups/999999","DELETE,/api/v1/contacts/999999"})
    void serviceAccountCannotPerformAdministratorOperation(String method,String path) throws Exception {
        assertEquals(403,request(method,path,OidcTestServer.token("reporter-test","user"),"{\"name\":\"Forbidden\"}").statusCode());
    }
    @Test void ordinaryUserCannotDeleteEvenOwnedContact() throws Exception {
        long id=contact("user-delete","user","user-delete@example.test",null);
        assertEquals(403,request("DELETE","/api/v1/contacts/"+id,OidcTestServer.token("user-delete","user"),null).statusCode());
    }
    @Test void administratorCanDeleteForeignContact() throws Exception {
        long id=contact("admin-delete-owner","user","admin-delete-owner@example.test",null);
        assertEquals(204,request("DELETE","/api/v1/contacts/"+id,OidcTestServer.token("deleting-admin","admin"),null).statusCode());
    }
    @Test void userCanReadOwnedContact() throws Exception {
        long id=contact("own-reader","user","own-reader@example.test",null);
        assertEquals(200,request("GET","/api/v1/contacts/"+id,OidcTestServer.token("own-reader","user"),null).statusCode());
    }
    @Test void userCannotReadForeignContact() throws Exception {
        long id=contact("foreign-read-owner","user","foreign-read-owner@example.test",null);
        assertEquals(403,request("GET","/api/v1/contacts/"+id,OidcTestServer.token("foreign-reader","user"),null).statusCode());
    }
    @Test void userCannotUpdateForeignContact() throws Exception {
        long id=contact("foreign-update-owner","user","foreign-update-owner@example.test",null);
        assertEquals(403,request("PUT","/api/v1/contacts/"+id,OidcTestServer.token("foreign-updater","user"),"{\"firstName\":\"Changed\",\"lastName\":\"Test\",\"email\":\"changed@example.test\"}").statusCode());
    }
    @Test void administratorCanReadForeignContact() throws Exception {
        long id=contact("admin-read-owner","user","admin-read-owner@example.test",null);
        var response=request("GET","/api/v1/contacts/"+id,OidcTestServer.token("reading-admin","admin"),null);
        assertEquals(200,response.statusCode()); assertEquals("admin-read-owner",json(response).path("owner").asText());
    }
    @Test void userCannotFilterByForeignOwner() throws Exception {
        assertEquals(403,request("GET","/api/v1/contacts?owner=other",OidcTestServer.token("owner-filter-user","user"),null).statusCode());
    }
    @Test void administratorCanFilterByForeignOwner() throws Exception {
        long id=contact("admin-filter-owner","user","admin-filter-owner@example.test",null);
        var response=request("GET","/api/v1/contacts?owner=admin-filter-owner",OidcTestServer.token("filtering-admin","admin"),null);
        assertEquals(200,response.statusCode()); assertEquals(id,json(response).path("items").get(0).path("id").asLong()); assertEquals(1,json(response).path("total").asLong());
    }
    @Test void userContactListContainsOnlyOwnedRecords() throws Exception {
        long own=contact("isolated-list-user","user","isolated-list-user@example.test",null);
        contact("isolated-list-other","user","isolated-list-other@example.test",null);
        var page=json(request("GET","/api/v1/contacts",OidcTestServer.token("isolated-list-user","user"),null));
        assertEquals(1,page.path("total").asLong()); assertEquals(own,page.path("items").get(0).path("id").asLong());
    }
    @Test void administratorContactListIncludesOtherUsers() throws Exception {
        long first=contact("global-list-first","user","global-list-first@example.test",null);
        long second=contact("global-list-second","user","global-list-second@example.test",null);
        var page=json(request("GET","/api/v1/contacts?size=100",OidcTestServer.token("global-list-admin","admin"),null));
        assertTrue(page.path("total").asLong()>=2);
        assertTrue(java.util.stream.StreamSupport.stream(page.path("items").spliterator(),false).anyMatch(item->item.path("id").asLong()==first));
        assertTrue(java.util.stream.StreamSupport.stream(page.path("items").spliterator(),false).anyMatch(item->item.path("id").asLong()==second));
    }
    @Test void userGroupPageContainsOnlyOwnedRecords() throws Exception {
        long groupId=group("isolated-user-group"); long own=contact("isolated-group-user","user","isolated-group-user@example.test",groupId);
        contact("isolated-group-other","user","isolated-group-other@example.test",groupId);
        var page=json(request("GET","/api/v1/groups/"+groupId+"/contacts",OidcTestServer.token("isolated-group-user","user"),null));
        assertEquals(1,page.path("total").asLong()); assertEquals(own,page.path("items").get(0).path("id").asLong());
    }
    @Test void administratorGroupPageIncludesAllOwnersAndCorrectTotal() throws Exception {
        long groupId=group("global-admin-group"); contact("global-group-first","user","global-group-first@example.test",groupId);
        long second=contact("global-group-second","user","global-group-second@example.test",groupId);
        var page=json(request("GET","/api/v1/groups/"+groupId+"/contacts?page=1&size=1",OidcTestServer.token("global-group-admin","admin"),null));
        assertEquals(2,page.path("total").asLong()); assertEquals(second,page.path("items").get(0).path("id").asLong()); assertEquals(1,page.path("items").size());
    }
    @Test void ordinaryUserCanReadSharedGroup() throws Exception {
        long id=group("user-readable-group");
        assertEquals(200,request("GET","/api/v1/groups/"+id,OidcTestServer.token("group-reader","user"),null).statusCode());
    }
    @Test void administratorReadPrivilegeDoesNotAllowForeignUpdate() throws Exception {
        long id=contact("read-only-admin-owner","user","read-only-admin-owner@example.test",null);
        assertEquals(403,request("PUT","/api/v1/contacts/"+id,OidcTestServer.token("read-only-admin","admin"),"{\"firstName\":\"Changed\",\"lastName\":\"Test\",\"email\":\"changed-admin@example.test\"}").statusCode());
    }
}
