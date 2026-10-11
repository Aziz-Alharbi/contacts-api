package com.unifonic.contacts.security;

import com.sun.net.httpserver.HttpServer;
import io.quarkus.test.common.QuarkusTestResourceLifecycleManager;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.security.interfaces.RSAPublicKey;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;

public class OidcTestServer implements QuarkusTestResourceLifecycleManager {
    static KeyPair keys;
    static String issuer;
    HttpServer server;
    static String encode(byte[] value) { return Base64.getUrlEncoder().withoutPadding().encodeToString(value); }
    static String integer(java.math.BigInteger value) {
        byte[] bytes=value.toByteArray();
        return encode(bytes[0]==0 ? java.util.Arrays.copyOfRange(bytes,1,bytes.length) : bytes);
    }
    public Map<String,String> start() {
        try {
            var generator=KeyPairGenerator.getInstance("RSA"); generator.initialize(2048); keys=generator.generateKeyPair();
            server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
            issuer="http://127.0.0.1:"+server.getAddress().getPort();
            var publicKey=(RSAPublicKey)keys.getPublic();
            String jwks="{\"keys\":[{\"kty\":\"RSA\",\"kid\":\"test\",\"alg\":\"RS256\",\"use\":\"sig\",\"n\":\""+integer(publicKey.getModulus())+"\",\"e\":\""+integer(publicKey.getPublicExponent())+"\"}]}";
            server.createContext("/jwks", exchange -> {
                byte[] body=jwks.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type","application/json");
                exchange.sendResponseHeaders(200,body.length); exchange.getResponseBody().write(body); exchange.close();
            }); server.start();
            return Map.ofEntries(
                Map.entry("quarkus.oidc.auth-server-url",issuer), Map.entry("quarkus.oidc.discovery-enabled","false"),
                Map.entry("quarkus.oidc.jwks-path","/jwks"), Map.entry("quarkus.oidc.token.issuer",issuer),
                Map.entry("quarkus.datasource.db-kind","h2"), Map.entry("quarkus.datasource.jdbc.url","jdbc:h2:mem:security;MODE=MySQL;DB_CLOSE_DELAY=-1"),
                Map.entry("quarkus.datasource.username","sa"), Map.entry("quarkus.datasource.password",""),
                Map.entry("quarkus.flyway.migrate-at-start","false"), Map.entry("quarkus.hibernate-orm.schema-management.strategy","drop-and-create"),
                Map.entry("quarkus.compose.devservices.enabled","false"), Map.entry("quarkus.http.test-port","0"));
        } catch(Exception error) { throw new RuntimeException(error); }
    }
    public void stop() { if(server!=null) server.stop(0); }
    static String token(String audience,long expires,String tokenIssuer,boolean wrongKey) throws Exception {
        return token(audience, expires, tokenIssuer, wrongKey, "reporter-test", "user");
    }
    static String token(String subject, String role) throws Exception {
        return token("contacts-api", Instant.now().getEpochSecond()+300, issuer, false, subject, role);
    }
    static String token(String audience,long expires,String tokenIssuer,boolean wrongKey,String subject,String role) throws Exception {
        String header=encode("{\"alg\":\"RS256\",\"kid\":\"test\"}".getBytes(StandardCharsets.UTF_8));
        String claims="{\"sub\":\""+subject+"\",\"iss\":\""+tokenIssuer+"\",\"aud\":\""+audience+"\",\"exp\":"+expires+",\"iat\":"+(Instant.now().getEpochSecond()-600)+",\"roles\":[\""+role+"\"]}";
        String input=header+"."+encode(claims.getBytes(StandardCharsets.UTF_8));
        var signature=Signature.getInstance("SHA256withRSA");
        PrivateKey key=keys.getPrivate();
        if(wrongKey) { var generator=KeyPairGenerator.getInstance("RSA"); generator.initialize(2048); key=generator.generateKeyPair().getPrivate(); }
        signature.initSign(key); signature.update(input.getBytes(StandardCharsets.UTF_8));
        return input+"."+encode(signature.sign());
    }
}
