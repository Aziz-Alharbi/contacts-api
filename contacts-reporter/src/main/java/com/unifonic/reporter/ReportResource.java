package com.unifonic.reporter;

import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

@Path("/reports/contacts/count")
@Produces(MediaType.APPLICATION_JSON)
public class ReportResource {
    @Inject
    ReportService reports;

    @GET
    public Response count() {
        return Response.ok(reports.count()).header("Cache-Control", "no-store").build();
    }
}
