package com.unifonic.container;

import com.fasterxml.jackson.databind.*;
import com.rabbitmq.client.*;
import com.unifonic.events.*;
import com.unifonic.reporter.kafka.ContactEventCount;
import org.apache.kafka.clients.consumer.*;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.StringDeserializer;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/** Opt-in end-to-end check run inside the Compose network, without printing credentials. */
public final class ComposeSmoke {
    private static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules();
    private static final String IDENTITY = "http://keycloak:8080/realms/contacts";
    private static final String API = "http://contacts-api:8080";

    private ComposeSmoke() {}

    public static void main(String[] args) {
        try {
            verify();
        } catch (Exception failure) {
            if (failure instanceof InterruptedException) Thread.currentThread().interrupt();
            System.err.println("Compose verification failed: " + (failure instanceof CheckFailure
                    ? failure.getMessage() : failure.getClass().getSimpleName()));
            System.exit(1);
        }
    }

    static String credential(String variable, String file) throws Exception {
        String override = System.getenv(variable);
        return override == null || override.isBlank()
                ? Files.readString(Path.of("/run/development-secrets", file)).strip() : override;
    }

    private static final class CheckFailure extends Exception {
        CheckFailure(String message) { super(message); }
    }

    static void require(boolean condition, String message) throws CheckFailure {
        if (!condition) throw new CheckFailure(message);
    }

    static String encode(String value) { return URLEncoder.encode(value, StandardCharsets.UTF_8); }

    static String form(Map<String, String> values) {
        return values.entrySet().stream().map(entry -> encode(entry.getKey()) + "=" + encode(entry.getValue()))
                .collect(java.util.stream.Collectors.joining("&"));
    }

    static Map<String, String> query(URI uri) {
        var result = new HashMap<String, String>();
        if (uri.getRawQuery() != null) {
            for (String pair : uri.getRawQuery().split("&")) {
                String[] parts = pair.split("=", 2);
                if (parts.length == 2) result.put(URLDecoder.decode(parts[0], StandardCharsets.UTF_8),
                        URLDecoder.decode(parts[1], StandardCharsets.UTF_8));
            }
        }
        return result;
    }

    static String login(HttpClient browser) throws Exception {
        String verifier = UUID.randomUUID().toString() + UUID.randomUUID();
        String challenge = Base64.getUrlEncoder().withoutPadding().encodeToString(
                MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII)));
        String state = UUID.randomUUID().toString();
        String callback = "http://localhost:8080/container-check";
        var authorization = URI.create(IDENTITY + "/protocol/openid-connect/auth?" + form(Map.of(
                "client_id", "contacts-api", "response_type", "code", "redirect_uri", callback,
                "scope", "openid", "state", state, "code_challenge", challenge, "code_challenge_method", "S256")));
        var page = browser.send(HttpRequest.newBuilder(authorization).timeout(Duration.ofSeconds(15)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        require(page.statusCode() == 200, "Login page unavailable");
        var forms = Pattern.compile("<form\\b[^>]*>", Pattern.DOTALL).matcher(page.body());
        String action = null;
        while (forms.find()) {
            if (forms.group().contains("kc-form-login")) {
                var attribute = Pattern.compile("action=\"([^\"]+)\"").matcher(forms.group());
                if (attribute.find()) action = attribute.group(1).replace("&amp;", "&");
            }
        }
        require(action != null, "Keycloak login form missing");
        URI externalAction = URI.create(action);
        URI internalAction = URI.create("http://keycloak:8080" + externalAction.getRawPath() + "?" + externalAction.getRawQuery());
        var submitted = browser.send(HttpRequest.newBuilder(internalAction).timeout(Duration.ofSeconds(15))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(form(Map.of("username", "demo-user",
                        "password", credential("CONTACTS_USER_PASSWORD", "demo-user"), "credentialId", "")))).build(),
                HttpResponse.BodyHandlers.discarding());
        require(submitted.statusCode() == 302, "User authentication failed");
        var parameters = query(URI.create(submitted.headers().firstValue("Location").orElseThrow()));
        require(state.equals(parameters.get("state")) && parameters.containsKey("code"), "Authorization code/state missing");
        var exchanged = browser.send(HttpRequest.newBuilder(URI.create(IDENTITY + "/protocol/openid-connect/token"))
                .timeout(Duration.ofSeconds(15)).header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(form(Map.of("grant_type", "authorization_code",
                        "client_id", "contacts-api", "redirect_uri", callback, "code", parameters.get("code"),
                        "code_verifier", verifier)))).build(), HttpResponse.BodyHandlers.ofString());
        require(exchanged.statusCode() == 200, "Authorization code exchange failed");
        String token = JSON.readTree(exchanged.body()).path("access_token").asText();
        require(!token.isBlank(), "Missing access token");
        String issuer = JSON.readTree(Base64.getUrlDecoder().decode(token.split("\\.")[1])).path("iss").asText();
        require(issuer.equals(System.getenv().getOrDefault("KEYCLOAK_PUBLIC_URL", "http://localhost:8081") + "/realms/contacts"),
                "Unexpected token issuer");
        return token;
    }

    static void verify() throws Exception {
        System.out.println("Checking Keycloak authorization code login with PKCE and signed API access...");
        var cookies = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
        try (var browser = HttpClient.newBuilder().cookieHandler(cookies).followRedirects(HttpClient.Redirect.NEVER)
                .connectTimeout(Duration.ofSeconds(5)).build()) {
            String token = login(browser);
            var unauthenticated = browser.send(HttpRequest.newBuilder(URI.create(API + "/api/v1/contacts")).GET().build(),
                    HttpResponse.BodyHandlers.discarding());
            require(unauthenticated.statusCode() == 401, "Anonymous API access must be rejected");
            var factory = new ConnectionFactory(); factory.setHost("rabbitmq");
            factory.setUsername(System.getenv().getOrDefault("RABBITMQ_USERNAME", "contacts"));
            factory.setPassword(credential("RABBITMQ_PASSWORD", "rabbitmq"));
            var kafkaProperties = new Properties();
            kafkaProperties.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, "kafka:19092");
            kafkaProperties.put(ConsumerConfig.GROUP_ID_CONFIG, "container-check-" + UUID.randomUUID());
            kafkaProperties.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
            kafkaProperties.put(ConsumerConfig.ISOLATION_LEVEL_CONFIG, "read_committed");
            try (var connection = factory.newConnection("container-verification"); var channel = connection.createChannel();
                 var consumer = new KafkaConsumer<>(kafkaProperties, new StringDeserializer(), new StringDeserializer())) {
                String probe = channel.queueDeclare("", false, true, true, Map.of()).getQueue();
                channel.queueBind(probe, RabbitTopology.EXCHANGE, RabbitTopology.KEY);
                String resultTopic = System.getenv().getOrDefault("KAFKA_COUNTS_TOPIC", "contacts.reporter.contact-counts.v1");
                consumer.assign(consumer.partitionsFor(resultTopic).stream().map(part -> new TopicPartition(resultTopic, part.partition())).toList());
                consumer.seekToEnd(consumer.assignment());
                // Resolve end positions before creation so results published immediately are not skipped.
                for (var partition : consumer.assignment()) consumer.position(partition);
                String correlation = UUID.randomUUID().toString();
                var request = HttpRequest.newBuilder(URI.create(API + "/api/v1/contacts"))
                        .timeout(Duration.ofSeconds(15)).header("Authorization", "Bearer " + token)
                        .header("Content-Type", "application/json").header("X-Correlation-ID", correlation)
                        .POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(Map.of("firstName", "Compose",
                                "lastName", "Verification", "email", "compose-" + UUID.randomUUID() + "@example.test")))).build();
                var created = browser.send(request, HttpResponse.BodyHandlers.ofString());
                require(created.statusCode() == 201, "Contact creation failed");
                long contactId = JSON.readTree(created.body()).path("id").asLong();
                require(contactId > 0, "Created contact ID missing");
                ContactCreatedEvent rabbitEvent = null;
                ContactEventCount kafkaResult = null;
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60);
                while ((rabbitEvent == null || kafkaResult == null) && System.nanoTime() < deadline) {
                    if (rabbitEvent == null) {
                        var delivery = channel.basicGet(probe, true);
                        if (delivery != null) {
                            var event = JSON.readValue(delivery.getBody(), ContactCreatedEvent.class);
                            if (event.contactId() == contactId) rabbitEvent = event;
                        }
                    }
                    for (var record : consumer.poll(Duration.ofMillis(200))) {
                        var result = JSON.readValue(record.value(), ContactEventCount.class);
                        if (result.contactId() == contactId) kafkaResult = result;
                    }
                }
                require(rabbitEvent != null && kafkaResult != null, "Event did not reach both brokers/consumer");
                require(rabbitEvent.eventId().equals(kafkaResult.eventId()), "Event IDs differ between brokers");
                require(correlation.equals(rabbitEvent.correlationId().toString())
                        && rabbitEvent.correlationId().equals(kafkaResult.correlationId()), "Correlation IDs differ");
                require(kafkaResult.count() == 1, "Unexpected unique-event count");
                var report = browser.send(HttpRequest.newBuilder(URI.create("http://contacts-reporter:8082/reports/contacts/count"))
                        .timeout(Duration.ofSeconds(15)).GET().build(), HttpResponse.BodyHandlers.ofString());
                require(report.statusCode() == 200, "Reporter service-account access failed");
                require(JSON.readTree(report.body()).path("contactCount").asLong(-1) == 0,
                        "Service account must not read another user's contacts");
                System.out.printf("PASS: Keycloak PKCE login, JWT validation, anonymous rejection, MySQL contact creation, "
                        + "RabbitMQ publication, Kafka consumption, service-account isolation; contactId=%d eventId=%s correlationId=%s%n",
                        contactId, rabbitEvent.eventId(), rabbitEvent.correlationId());
                System.out.println("Confirm the Reporter RabbitMQ success log for the same eventId with docker compose logs contacts-reporter.");
            }
        }
    }
}
