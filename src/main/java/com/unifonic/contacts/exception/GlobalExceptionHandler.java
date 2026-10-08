package com.unifonic.contacts.exception;

import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.WebApplicationException;
import org.jboss.resteasy.reactive.server.ServerExceptionMapper;
import org.jboss.logging.Logger;
import jakarta.validation.ConstraintViolationException;
import jakarta.validation.Path;
import java.util.Collections;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Set;

public class GlobalExceptionHandler {

    private static final Logger LOG = Logger.getLogger(GlobalExceptionHandler.class);

    @ServerExceptionMapper
    public Response handleWebApplicationException(WebApplicationException exception) {
        return exception.getResponse();
    }

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

        var violations = exception.getConstraintViolations().stream()
                .map(violation -> Map.of(
                        "field", validationField(violation.getPropertyPath()),
                        "reason", violation.getMessage()))
                .sorted(Comparator.comparing((Map<String, String> violation) -> violation.get("field"))
                        .thenComparing(violation -> violation.get("reason")))
                .toList();

        Map<String, Object> errorResponse = Map.of(
                "status", 400,
                "error", "Bad Request",
                "message", "Validation failed",
                "violations", violations
        );

        return Response.status(Response.Status.BAD_REQUEST)
                .entity(errorResponse)
                .build();
    }

    private String validationField(Path path) {
        String field = path.toString();
        for (Path.Node node : path) {
            if (node.getName() != null) {
                field = node.getName();
            }
        }
        return field;
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

        Set<Throwable> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Throwable cause = exception; cause != null && visited.add(cause); cause = cause.getCause()) {
            if (cause instanceof org.hibernate.exception.ConstraintViolationException constraintException) {
                return handleDatabaseConstraintException(constraintException);
            }
        }

        LOG.error("An unexpected error occurred", exception);

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
