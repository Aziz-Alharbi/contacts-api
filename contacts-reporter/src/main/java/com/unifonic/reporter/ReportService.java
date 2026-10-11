package com.unifonic.reporter;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Response;
import java.util.Map;
import org.eclipse.microprofile.rest.client.inject.RestClient;

@ApplicationScoped
public class ReportService {
    @Inject
    @RestClient
    ContactsClient contacts;

    public record ContactCount(long contactCount, String scope) {}

    public ContactCount count() {
        JsonNode page;
        try {
            // The API computes total AFTER ownership filtering. One item avoids fetching all PII.
            page = contacts.contacts(0, 1);
        } catch (WebApplicationException error) {
            int status = error.getResponse().getStatus();
            if (status == 401) throw failure(502, "Contacts API rejected the service token");
            if (status == 403) throw failure(502, "Service account is not authorized by Contacts API");
            throw failure(502, "Contacts API request failed");
        } catch (RuntimeException error) {
            // Never expose provider error bodies, credential values, or access tokens.
            throw failure(503, "Identity provider or Contacts API is unavailable");
        }
        if (!validPage(page)) throw failure(502, "Contacts API returned an invalid page");
        return new ContactCount(page.get("total").longValue(), "service-account");
    }

    private boolean validPage(JsonNode page) {
        if (page == null || !page.isObject()) return false;
        JsonNode total = page.get("total"), items = page.get("items");
        return total != null && total.isIntegralNumber() && total.canConvertToLong() && total.longValue() >= 0
                && items != null && items.isArray() && items.size() <= 1
                && page.path("page").isIntegralNumber() && page.path("page").asInt(-1) == 0
                && page.path("size").isIntegralNumber() && page.path("size").asInt(-1) == 1
                && ((total.longValue() == 0 && items.isEmpty()) || (total.longValue() > 0 && items.size() == 1));
    }

    private WebApplicationException failure(int status, String message) {
        return new WebApplicationException(Response.status(status)
                .entity(Map.of("status", status, "message", message)).build());
    }
}
