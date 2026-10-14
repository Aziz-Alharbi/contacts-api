package com.unifonic.contacts.exception;

import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.HttpMethod;
import java.util.Map;
import java.util.List;
import java.util.TreeSet;
import com.unifonic.contacts.resource.ContactResource;
import com.unifonic.contacts.resource.ContactGroupResource;

public final class ErrorResponses {
    private ErrorResponses() {}
    public static Map<String,Object> body(int status) {
        var standard=Response.Status.fromStatusCode(status);
        String reason=standard == null ? "HTTP Error" : standard.getReasonPhrase();
        return Map.of("status",status,"error",reason,"message",status >= 500 ? "An unexpected error occurred" : reason);
    }
    public static String allowedMethods(String path) {
        var allowed=new TreeSet<String>();
        for (Class<?> resource : List.of(ContactResource.class,ContactGroupResource.class)) {
            String prefix=resource.getAnnotation(Path.class).value();
            for (var method : resource.getDeclaredMethods()) {
                var suffix=method.getAnnotation(Path.class);
                String template=prefix+(suffix==null ? "" : suffix.value());
                if (path.matches(template.replaceAll("\\{[^}]+\\}","[^/]+"))) {
                    for (var annotation : method.getAnnotations()) {
                        var verb=annotation.annotationType().getAnnotation(HttpMethod.class);
                        if (verb!=null) allowed.add(verb.value());
                    }
                }
            }
        }
        if (allowed.contains("GET")) allowed.add("HEAD");
        if (!allowed.isEmpty()) allowed.add("OPTIONS");
        return String.join(", ",allowed);
    }
}
