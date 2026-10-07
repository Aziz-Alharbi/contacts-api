package com.unifonic.contacts.exception;

import jakarta.ws.rs.core.Response;
import org.jboss.resteasy.reactive.server.ServerExceptionMapper;
import jakarta.validation.ConstraintViolationException;
import java.util.Map;

public class GlobalExceptionHandler {

    @ServerExceptionMapper
    public Response handleApiException(ApiException exception) {

        Map<String, Object> errorResponse = Map.of(
                "status", exception.getStatus(),
                "error", exception.getError(),
                "message", exception.getMessage()
        );

        return Response.status(exception.getStatus())
                .entity(errorResponse)
                .build();
    }

    @ServerExceptionMapper
    public Response handleValidationException(
            ConstraintViolationException exception) {

        Map<String, Object> errorResponse = Map.of(
                "status", 400,
                "error", "Bad Request",
                "message", "Validation failed"
        );

        return Response.status(Response.Status.BAD_REQUEST)
                .entity(errorResponse)
                .build();
    }


    @ServerExceptionMapper
    public Response handleDatabaseConstraintException(
            org.hibernate.exception.ConstraintViolationException exception) {

    Map<String, Object> errorResponse = Map.of(
            "status", 409,
            "error", "Conflict",
            "message", "Resource already exists"
    );

    return Response.status(Response.Status.CONFLICT)
            .entity(errorResponse)
            .build();
    }


    @ServerExceptionMapper
    public Response handleUnexpectedException(Exception exception) {

        Map<String, Object> errorResponse = Map.of(
                "status", 500,
                "error", "Internal Server Error",
                "message", "An unexpected error occurred"
        );

        return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                .entity(errorResponse)
                .build();
    }

    


}
