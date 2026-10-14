package com.unifonic.contacts.exception;

import io.quarkus.oidc.runtime.OidcAuthenticationMechanism;
import io.quarkus.security.identity.*;
import io.quarkus.security.identity.request.AuthenticationRequest;
import io.quarkus.vertx.http.runtime.security.*;
import io.smallrye.mutiny.Uni;
import io.vertx.ext.web.RoutingContext;
import io.vertx.core.json.Json;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.Set;

/** Delegates token validation to OIDC; formats only the HTTP challenge body. */
@ApplicationScoped
public class JsonOidcAuthenticationMechanism implements HttpAuthenticationMechanism {
    @Inject OidcAuthenticationMechanism oidc;
    @Override public Uni<SecurityIdentity> authenticate(RoutingContext context,IdentityProviderManager manager) {
        return oidc.authenticate(context,manager);
    }
    @Override public Uni<ChallengeData> getChallenge(RoutingContext context) { return oidc.getChallenge(context); }
    @Override public Uni<Boolean> sendChallenge(RoutingContext context) {
        return oidc.getChallenge(context).onItem().transform(challenge -> {
            if (challenge == null) return false;
            context.response().setStatusCode(challenge.status);
            challenge.getHeaders().forEach((key,value)->context.response().putHeader(key,value));
            context.response().putHeader("Content-Type","application/json").end(Json.encode(ErrorResponses.body(challenge.status)));
            return true;
        });
    }
    @Override public Set<Class<? extends AuthenticationRequest>> getCredentialTypes() { return oidc.getCredentialTypes(); }
    @Override public Uni<HttpCredentialTransport> getCredentialTransport(RoutingContext context) { return oidc.getCredentialTransport(context); }
    @Override public int getPriority() { return oidc.getPriority()+1; }
}
