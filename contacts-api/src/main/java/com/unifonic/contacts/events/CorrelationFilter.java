package com.unifonic.contacts.events;

import jakarta.enterprise.context.RequestScoped;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.container.*;
import jakarta.ws.rs.ext.Provider;
import java.util.UUID;

@Provider
@RequestScoped
public class CorrelationFilter implements ContainerRequestFilter, ContainerResponseFilter {
    private UUID correlationId;

    public UUID id() {
        if (correlationId == null) correlationId = UUID.randomUUID();
        return correlationId;
    }

    @Override public void filter(ContainerRequestContext request) {
        String header = request.getHeaderString("X-Correlation-ID");
        if (header != null) {
            try {
                correlationId = UUID.fromString(header);
                if (!correlationId.toString().equalsIgnoreCase(header)) throw new IllegalArgumentException();
            } catch (IllegalArgumentException error) {
                throw new BadRequestException("X-Correlation-ID must be a UUID");
            }
        }
    }

    @Override public void filter(ContainerRequestContext request, ContainerResponseContext response) {
        response.getHeaders().putSingle("X-Correlation-ID", id().toString());
    }
}
