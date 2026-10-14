package com.unifonic.contacts.security;

import com.fasterxml.jackson.databind.*;
import io.quarkus.test.junit.*;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.common.http.TestHTTPResource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;
import java.net.URI;
import java.net.http.*;
import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
@TestProfile(ContactsSecurityTest.Profile.class)
@QuarkusTestResource(OidcTestServer.class)
class ApiEndpointsTest {
    @TestHTTPResource URI base;
    final ObjectMapper mapper=new ObjectMapper();
    HttpResponse<String> request(String method,String path,String subject,String body) throws Exception {
        String token=OidcTestServer.token(subject,"admin");
        var request=HttpRequest.newBuilder(base.resolve(path)).header("Authorization","Bearer "+token).header("Content-Type","application/json")
            .method(method,body==null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body)).build();
        try(var client=HttpClient.newHttpClient()) { return client.send(request,HttpResponse.BodyHandlers.ofString()); }
    }
    JsonNode json(HttpResponse<String> response) throws Exception { return mapper.readTree(response.body()); }
    String contactBody(String email,Long group) {
        return "{\"firstName\":\"Alice\",\"lastName\":\"Example\",\"email\":\""+email+"\",\"phone\":\"12345\",\"groupId\":"+group+"}";
    }
    long createContact(String subject,String email,Long group) throws Exception {
        var response=request("POST","/api/v1/contacts",subject,contactBody(email,group));
        assertEquals(201,response.statusCode(),response.body());
        long id=json(response).path("id").asLong();
        assertTrue(id>0); assertEquals(base.resolve("/api/v1/contacts/"+id).toString(),response.headers().firstValue("Location").orElseThrow());
        assertEquals(subject,json(response).path("owner").asText()); return id;
    }
    long createGroup(String name) throws Exception {
        var response=request("POST","/api/v1/groups","endpoint-admin","{\"name\":\""+name+"\",\"description\":\"Description\"}");
        assertEquals(201,response.statusCode(),response.body()); long id=json(response).path("id").asLong();
        assertEquals(base.resolve("/api/v1/groups/"+id).toString(),response.headers().firstValue("Location").orElseThrow()); return id;
    }
    @Test void contactCreateAndReadReturnPersistedFields() throws Exception {
        long id=createContact("endpoint-read","endpoint-read@example.test",null);
        var response=request("GET","/api/v1/contacts/"+id,"endpoint-read",null);
        assertEquals(200,response.statusCode()); var body=json(response);
        assertEquals("Alice",body.path("firstName").asText()); assertEquals("Example",body.path("lastName").asText());
        assertEquals("endpoint-read@example.test",body.path("email").asText()); assertEquals("12345",body.path("phone").asText()); assertFalse(body.path("createdAt").asText().isBlank());
    }
    @Test void contactUpdateChangesDataAndGroupAndPreservesOwner() throws Exception {
        long group=createGroup("endpoint-contact-update-group"); long id=createContact("endpoint-update","endpoint-update@example.test",null);
        var response=request("PUT","/api/v1/contacts/"+id,"endpoint-update",contactBody("updated@example.test",group));
        assertEquals(200,response.statusCode()); assertEquals(group,json(response).path("groupId").asLong()); assertEquals("endpoint-update",json(response).path("owner").asText());
        var saved=json(request("GET","/api/v1/contacts/"+id,"endpoint-update",null)); assertEquals("updated@example.test",saved.path("email").asText()); assertEquals(group,saved.path("groupId").asLong());
    }
    @Test void contactDeleteReturns204AndRemovesRecord() throws Exception {
        long id=createContact("endpoint-delete","endpoint-delete@example.test",null);
        var response=request("DELETE","/api/v1/contacts/"+id,"endpoint-delete",null);
        assertEquals(204,response.statusCode()); assertEquals("",response.body()); assertEquals(404,request("GET","/api/v1/contacts/"+id,"endpoint-delete",null).statusCode());
    }
    @Test void contactPagesReturnStableIdsAndFullOwnedTotal() throws Exception {
        long first=createContact("endpoint-pages","page-first@example.test",null); long second=createContact("endpoint-pages","page-second@example.test",null);
        var response=request("GET","/api/v1/contacts?page=1&size=1&owner=endpoint-pages","endpoint-pages",null);
        assertEquals(200,response.statusCode()); var page=json(response);
        assertEquals(1,page.path("page").asInt()); assertEquals(1,page.path("size").asInt()); assertEquals(2,page.path("total").asLong()); assertEquals(1,page.path("items").size()); assertEquals(second,page.path("items").get(0).path("id").asLong()); assertNotEquals(first,second);
        var empty=json(request("GET","/api/v1/contacts?page=2&size=1&owner=endpoint-pages","endpoint-pages",null)); assertEquals(0,empty.path("items").size()); assertEquals(2,empty.path("total").asLong());
    }
    @ParameterizedTest @ValueSource(strings={"GET","PUT","DELETE"})
    void missingContactReturns404(String method) throws Exception {
        var response=request(method,"/api/v1/contacts/999999","endpoint-missing",contactBody("missing@example.test",null));
        assertEquals(404,response.statusCode()); assertTrue(json(response).path("message").asText().contains("999999"));
    }
    @ParameterizedTest @ValueSource(strings={"POST","PUT"})
    void contactReferencesToMissingGroupReturn404(String method) throws Exception {
        long id=createContact("missing-group-"+method,"missing-group-"+method+"@example.test",null);
        String path="/api/v1/contacts"+java.util.Map.of("POST","","PUT","/"+id).get(method);
        assertEquals(404,request(method,path,"missing-group-"+method,contactBody("bad-group-"+method+"@example.test",999999L)).statusCode());
    }
    @Test void duplicateContactCreateReturns409WithoutAddingRow() throws Exception {
        createContact("duplicate-create","duplicate-create@example.test",null);
        assertEquals(409,request("POST","/api/v1/contacts","duplicate-create",contactBody("duplicate-create@example.test",null)).statusCode());
        assertEquals(1,json(request("GET","/api/v1/contacts?owner=duplicate-create","duplicate-create",null)).path("total").asLong());
    }
    @Test void duplicateContactUpdateReturns409AndRollsBack() throws Exception {
        createContact("duplicate-update","duplicate-update-first@example.test",null); long second=createContact("duplicate-update","duplicate-update-second@example.test",null);
        assertEquals(409,request("PUT","/api/v1/contacts/"+second,"duplicate-update",contactBody("duplicate-update-first@example.test",null)).statusCode());
        assertEquals("duplicate-update-second@example.test",json(request("GET","/api/v1/contacts/"+second,"duplicate-update",null)).path("email").asText());
    }
    @ParameterizedTest @ValueSource(strings={"POST","PUT"})
    void invalidContactFieldsReturn400WithReasons(String method) throws Exception {
        long id=createContact("validation-"+method,"validation-"+method+"@example.test",null);
        String path="/api/v1/contacts"+java.util.Map.of("POST","","PUT","/"+id).get(method);
        var response=request(method,path,"validation-"+method,"{\"firstName\":\"\",\"lastName\":\"Valid\",\"email\":\"bad-email\"}");
        assertEquals(400,response.statusCode()); assertTrue(response.body().contains("firstName")); assertTrue(response.body().contains("email")); assertTrue(response.body().contains("reason"));
    }
    @Test void groupCreateAndReadReturnFields() throws Exception {
        long id=createGroup("endpoint-group-read"); var response=request("GET","/api/v1/groups/"+id,"endpoint-admin",null);
        assertEquals(200,response.statusCode()); assertEquals("endpoint-group-read",json(response).path("name").asText()); assertEquals("Description",json(response).path("description").asText()); assertFalse(json(response).path("createdAt").asText().isBlank());
    }
    @Test void groupUpdatePersistsFieldsWith200() throws Exception {
        long id=createGroup("endpoint-group-update"); var response=request("PUT","/api/v1/groups/"+id,"endpoint-admin","{\"name\":\"endpoint-group-updated\",\"description\":\"Changed\"}");
        assertEquals(200,response.statusCode()); assertEquals("endpoint-group-updated",json(response).path("name").asText()); assertEquals("Changed",json(request("GET","/api/v1/groups/"+id,"endpoint-admin",null)).path("description").asText());
    }
    @Test void groupDeleteReturns204AndRemovesRecord() throws Exception {
        long id=createGroup("endpoint-group-delete"); var response=request("DELETE","/api/v1/groups/"+id,"endpoint-admin",null);
        assertEquals(204,response.statusCode()); assertEquals("",response.body()); assertEquals(404,request("GET","/api/v1/groups/"+id,"endpoint-admin",null).statusCode());
    }
    @Test void groupListsSupportExactNameFilterAndPagination() throws Exception {
        long id=createGroup("endpoint-group-filter");
        var response=request("GET","/api/v1/groups?name=endpoint-group-filter&page=0&size=1","endpoint-admin",null);
        assertEquals(200,response.statusCode()); var page=json(response); assertEquals(1,page.path("total").asLong()); assertEquals(id,page.path("items").get(0).path("id").asLong());
        var empty=json(request("GET","/api/v1/groups?name=endpoint-group-filter&page=1&size=1","endpoint-admin",null)); assertEquals(0,empty.path("items").size()); assertEquals(1,empty.path("total").asLong());
        var all=request("GET","/api/v1/groups?page=0&size=1","endpoint-admin",null); assertEquals(200,all.statusCode()); assertEquals(1,json(all).path("items").size()); assertTrue(json(all).path("total").asLong()>=1);
    }
    @ParameterizedTest @ValueSource(strings={"GET","PUT","DELETE"})
    void missingGroupReturns404(String method) throws Exception {
        assertEquals(404,request(method,"/api/v1/groups/999999","endpoint-admin","{\"name\":\"missing\"}").statusCode());
    }
    @Test void missingGroupContactPageReturns404() throws Exception { assertEquals(404,request("GET","/api/v1/groups/999999/contacts","endpoint-admin",null).statusCode()); }
    @Test void groupContactsArePagedAfterOwnershipFiltering() throws Exception {
        long group=createGroup("endpoint-group-contacts"); createContact("group-pages","group-page-first@example.test",group); long second=createContact("group-pages","group-page-second@example.test",group);
        var response=request("GET","/api/v1/groups/"+group+"/contacts?page=1&size=1","group-pages",null);
        assertEquals(200,response.statusCode()); var page=json(response); assertEquals(2,page.path("total").asLong()); assertEquals(1,page.path("page").asInt()); assertEquals(second,page.path("items").get(0).path("id").asLong());
    }
    @Test void duplicateGroupCreateReturns409() throws Exception {
        createGroup("duplicate-group-create"); assertEquals(409,request("POST","/api/v1/groups","endpoint-admin","{\"name\":\"duplicate-group-create\"}").statusCode());
    }
    @Test void duplicateGroupUpdateReturns409AndRollsBack() throws Exception {
        createGroup("duplicate-group-first"); long second=createGroup("duplicate-group-second");
        assertEquals(409,request("PUT","/api/v1/groups/"+second,"endpoint-admin","{\"name\":\"duplicate-group-first\"}").statusCode());
        assertEquals("duplicate-group-second",json(request("GET","/api/v1/groups/"+second,"endpoint-admin",null)).path("name").asText());
    }
    @ParameterizedTest @ValueSource(strings={"POST","PUT"})
    void blankGroupNameReturns400(String method) throws Exception {
        long id=createGroup("blank-group-"+method); String path="/api/v1/groups"+java.util.Map.of("POST","","PUT","/"+id).get(method);
        var response=request(method,path,"endpoint-admin","{\"name\":\"\"}"); assertEquals(400,response.statusCode()); assertTrue(response.body().contains("name")); assertTrue(response.body().contains("reason"));
    }
    @ParameterizedTest @ValueSource(strings={"/api/v1/contacts?page=-1", "/api/v1/contacts?size=0", "/api/v1/groups?size=101", "/api/v1/groups?page=-1"})
    void invalidPaginationReturns400(String path) throws Exception { assertEquals(400,request("GET",path,"endpoint-admin",null).statusCode()); }
    @Test void groupWithContactsCannotBeDeletedAndRemainsReadable() throws Exception {
        long id=createGroup("group-in-use"); createContact("group-in-use-owner","group-in-use@example.test",id);
        assertEquals(409,request("DELETE","/api/v1/groups/"+id,"endpoint-admin",null).statusCode());
        assertEquals(200,request("GET","/api/v1/groups/"+id,"endpoint-admin",null).statusCode());
    }
}
