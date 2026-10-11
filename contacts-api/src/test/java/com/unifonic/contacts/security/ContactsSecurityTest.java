package com.unifonic.contacts.security;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.common.http.TestHTTPResource;
import org.junit.jupiter.api.Test;
import java.net.URI;
import java.net.http.*;
import java.time.Instant;
import static org.junit.jupiter.api.Assertions.assertEquals;

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
        assertEquals(401,status(null));
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
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings={"ordinary-user","reporter-test"})
    void userAndServiceCannotCreateUpdateOrDeleteGroups(String subject) throws Exception {
        long id=group("restricted-"+subject);
        String token=OidcTestServer.token(subject,"user");
        assertEquals(403,request("POST","/api/v1/groups",token,"{\"name\":\"forbidden\"}").statusCode());
        assertEquals(403,request("PUT","/api/v1/groups/"+id,token,"{\"name\":\"forbidden\"}").statusCode());
        assertEquals(403,request("DELETE","/api/v1/groups/"+id,token,null).statusCode());
        assertEquals(200,request("GET","/api/v1/groups/"+id,token,null).statusCode());
    }
    @Test void administratorCanCreateUpdateAndDeleteGroups() throws Exception {
        long id=group("admin-positive");
        String token=OidcTestServer.token("group-admin","admin");
        assertEquals(200,request("PUT","/api/v1/groups/"+id,token,"{\"name\":\"admin-updated\"}").statusCode());
        assertEquals(204,request("DELETE","/api/v1/groups/"+id,token,null).statusCode());
        assertEquals(404,request("GET","/api/v1/groups/"+id,token,null).statusCode());
    }
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({"other-user,user","other-admin,admin","other-service,user"})
    void foreignContactsCannotBeReadUpdatedDeletedOrQueried(String subject,String role) throws Exception {
        String owner="owner-for-"+subject;
        long id=contact(owner,"user",subject+"@example.test",null);
        String intruder=OidcTestServer.token(subject,role);
        assertEquals(403,request("GET","/api/v1/contacts/"+id,intruder,null).statusCode());
        assertEquals(403,request("PUT","/api/v1/contacts/"+id,intruder,"{\"firstName\":\"Changed\",\"lastName\":\"Test\",\"email\":\"changed@example.test\"}").statusCode());
        assertEquals(403,request("DELETE","/api/v1/contacts/"+id,intruder,null).statusCode());
        assertEquals(403,request("GET","/api/v1/contacts?owner="+owner,intruder,null).statusCode());
        var original=request("GET","/api/v1/contacts/"+id,OidcTestServer.token(owner,"user"),null);
        assertEquals(200,original.statusCode());
        assertEquals("Security",json(original).get("firstName").asText());
        assertEquals(owner,json(original).get("owner").asText());
    }
    @Test void listsAndGroupPagesIsolateTwoUsersAndAdministrator() throws Exception {
        long groupId=group("isolation-group");
        long a=contact("list-a","user","list-a@example.test",groupId);
        long b=contact("list-b","user","list-b@example.test",groupId);
        long admin=contact("list-admin","admin","list-admin@example.test",groupId);
        assertOwnPage("list-a","user","/api/v1/contacts",a);
        assertOwnPage("list-b","user","/api/v1/contacts",b);
        assertOwnPage("list-admin","admin","/api/v1/contacts",admin);
        assertOwnPage("list-a","user","/api/v1/groups/"+groupId+"/contacts",a);
        assertOwnPage("list-b","user","/api/v1/groups/"+groupId+"/contacts",b);
        assertOwnPage("list-admin","admin","/api/v1/groups/"+groupId+"/contacts",admin);
    }
    void assertOwnPage(String subject,String role,String path,long id) throws Exception {
        var response=request("GET",path+"?page=0&size=1",OidcTestServer.token(subject,role),null);
        assertEquals(200,response.statusCode());
        var page=json(response);
        assertEquals(1,page.get("total").asLong());
        assertEquals(1,page.get("items").size());
        assertEquals(id,page.get("items").get(0).get("id").asLong());
        assertEquals(subject,page.get("items").get(0).get("owner").asText());
    }
}
