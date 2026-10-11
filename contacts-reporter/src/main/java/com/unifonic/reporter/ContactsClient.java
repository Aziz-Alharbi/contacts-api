package com.unifonic.reporter;

import com.fasterxml.jackson.databind.JsonNode;
import io.quarkus.oidc.client.filter.OidcClientFilter;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import org.eclipse.microprofile.rest.client.inject.RegisterRestClient;

@RegisterRestClient(configKey = "contacts-api")
@OidcClientFilter
@Path("/api/v1/contacts")
@Produces(MediaType.APPLICATION_JSON)
public interface ContactsClient {
    @GET
    JsonNode contacts(@QueryParam("page") int page, @QueryParam("size") int size);
}
