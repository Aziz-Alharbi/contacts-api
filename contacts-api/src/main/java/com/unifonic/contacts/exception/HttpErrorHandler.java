package com.unifonic.contacts.exception;

import io.quarkus.security.AuthenticationFailedException;
import io.quarkus.security.UnauthorizedException;
import io.quarkus.security.ForbiddenException;
import io.vertx.ext.web.Router;
import io.vertx.core.json.Json;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.ws.rs.container.ContainerResponseContext;
import jakarta.ws.rs.core.MediaType;
import org.jboss.resteasy.reactive.server.ServerResponseFilter;
import java.util.Map;

@ApplicationScoped
public class HttpErrorHandler {
    void configure(@Observes Router router) {
        router.route().failureHandler(context -> {
            int status=context.statusCode();
            Throwable failure=context.failure();
            if (failure instanceof AuthenticationFailedException || failure instanceof UnauthorizedException) status=401;
            else if (failure instanceof ForbiddenException) status=403;
            else if (failure != null && status < 400) status=500;
            if (status < 400 || context.response().ended()) { context.next(); return; }
            if (status == 401 && !context.response().headers().contains("WWW-Authenticate"))
                context.response().putHeader("WWW-Authenticate","Bearer");
            if (status==405 && !context.response().headers().contains("Allow")) {
                String allowed=ErrorResponses.allowedMethods(context.normalizedPath());
                if (!allowed.isEmpty()) context.response().putHeader("Allow",allowed);
            }
            if (status>=500) org.jboss.logging.Logger.getLogger(HttpErrorHandler.class).error("Unexpected HTTP failure",failure);
            context.response().setStatusCode(status).putHeader("Content-Type","application/json")
                .end(Json.encode(ErrorResponses.body(status)));
        });
    }
    @ServerResponseFilter
    void normalize(jakarta.ws.rs.container.ContainerRequestContext request, ContainerResponseContext response) {
        if (response.getStatus() < 400) return;
        Object body=response.getEntity();
        if (!(body instanceof Map<?,?> map && map.containsKey("status") && map.containsKey("error") && map.containsKey("message")))
            response.setEntity(ErrorResponses.body(response.getStatus()));
        response.getHeaders().putSingle("Content-Type",MediaType.APPLICATION_JSON);
        if (response.getStatus()==405 && !response.getHeaders().containsKey("Allow")) {
            String allowed=ErrorResponses.allowedMethods(request.getUriInfo().getAbsolutePath().getPath());
            if (!allowed.isEmpty()) response.getHeaders().putSingle("Allow",allowed);
        }
    }
}
