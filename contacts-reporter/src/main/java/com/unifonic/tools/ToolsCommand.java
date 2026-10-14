package com.unifonic.tools;

import io.quarkus.runtime.QuarkusApplication;
import io.quarkus.runtime.annotations.QuarkusMain;
import java.nio.file.*;
import java.util.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

@QuarkusMain(name = "tools")
public class ToolsCommand implements QuarkusApplication {
    @Override
    public int run(String... args) {
        try {
            if (args.length == 0 || Arrays.asList(args).contains("--help")) { help(); return 0; }
            var options = new HashMap<String, String>();
            var positional = new ArrayList<String>();
            for (int i = 1; i < args.length; i++) {
                if (args[i].startsWith("--")) {
                    if (!Set.of("--root", "--issuer", "--save").contains(args[i]) || i + 1 == args.length || args[i + 1].startsWith("--")) {
                        throw new IllegalArgumentException("Unknown option or missing option value");
                    }
                    String name = args[i++];
                    if (options.put(name, args[i]) != null) throw new IllegalArgumentException("Duplicate option");
                } else positional.add(args[i]);
            }
            Path root = Path.of(options.getOrDefault("--root", ".")).toAbsolutePath().normalize();
            var env = LocalFiles.environment(root);
            String issuer = options.getOrDefault("--issuer", env.getOrDefault("KEYCLOAK_URL", "http://localhost:8081").replaceAll("/$", "") + "/realms/contacts");
            if (options.containsKey("--save") && !args[0].equals("token")) throw new IllegalArgumentException("--save requires token");
            switch (args[0]) {
                case "init-env" -> {
                    exact(positional, 0); LocalFiles.init(root);
                    System.out.println("Created private .env with generated local credentials; keep it outside Git.");
                }
                case "token" -> {
                    exact(positional, 1);
                    String identity = positional.getFirst();
                    var oauth = new OAuthTokens(issuer);
                    String token = switch (identity) {
                        case "reporter" -> oauth.reporter(env);
                        case "user", "demo-user" -> oauth.device("demo-user", System.out::println);
                        case "admin", "demo-admin" -> oauth.device("demo-admin", System.out::println);
                        default -> throw new IllegalArgumentException("Use demo-user, demo-admin, user, admin, or reporter");
                    };
                    System.out.println(OAuthTokens.JSON.writerWithDefaultPrettyPrinter().writeValueAsString(OAuthTokens.selectedClaims(OAuthTokens.decode(token))));
                    System.out.println("Decoded claims only; signature validation is performed by Contacts API.");
                    if (options.containsKey("--save")) {
                        LocalFiles.privateFile(Path.of(options.get("--save")), token);
                        System.out.println("Saved access token to a new private file.");
                    }
                }
                case "check-identity" -> {
                    exact(positional, 0); new OAuthTokens(issuer).checkReporter(env);
                    System.out.println("PASS: discovery and reporter sub, iss, aud, exp, roles, and client identity. Signature validation is a separate API check.");
                }
                case "sanitize-export" -> {
                    exact(positional, 2);
                    var source = OAuthTokens.JSON.readTree(Files.readString(Path.of(positional.get(0))));
                    var template = OAuthTokens.JSON.readTree(Files.readString(root.resolve("identity/realm/contacts-realm.json")));
                    String sanitized = OAuthTokens.JSON.writerWithDefaultPrettyPrinter().writeValueAsString(sanitize(source, template));
                    LocalFiles.privateFile(Path.of(positional.get(1)), sanitized + "\n");
                    System.out.println("Wrote configuration with reviewed demo placeholders only.");
                }
                default -> throw new IllegalArgumentException("Unknown command; use --help");
            }
            return 0;
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt(); System.err.println("Operation interrupted"); return 1;
        } catch (java.nio.file.FileAlreadyExistsException error) {
            System.err.println("Output file already exists; choose a new file. Existing files are never overwritten."); return 1;
        } catch (IllegalArgumentException | IllegalStateException error) {
            // All messages originate in this tool; provider bodies and credential values are never interpolated.
            System.err.println(error.getMessage()); return 1;
        } catch (Exception error) {
            System.err.println("Operation failed. Check local files, services and permissions; credentials and provider details are withheld."); return 1;
        }
    }

    static ObjectNode sanitize(JsonNode source, JsonNode template) {
        if (!template.path("realm").asText().equals(source.path("realm").asText())) {
            throw new IllegalArgumentException("Expected the contacts realm");
        }
        ObjectNode result = template.deepCopy();
        for (String key : List.of("enabled", "displayName", "registrationAllowed", "resetPasswordAllowed",
                "rememberMe", "sslRequired", "accessTokenLifespan")) {
            if (source.has(key)) result.set(key, source.get(key));
        }
        for (JsonNode target : result.path("clients")) {
            JsonNode original = client(source, target.path("clientId").asText());
            for (String key : List.of("enabled", "publicClient", "standardFlowEnabled", "directAccessGrantsEnabled",
                    "serviceAccountsEnabled", "fullScopeAllowed", "redirectUris", "webOrigins")) {
                if (original.has(key)) ((ObjectNode) target).set(key, original.get(key));
            }
        }
        // Credentials, users, scopes, mappers and device/PKCE settings remain reviewed template values.
        return result;
    }

    static JsonNode client(JsonNode realm, String id) {
        for (JsonNode client : realm.path("clients")) if (id.equals(client.path("clientId").asText())) return client;
        throw new IllegalArgumentException("Missing application client: " + id);
    }

    private static void exact(List<String> values, int count) {
        if (values.size() != count) throw new IllegalArgumentException("Wrong number of arguments; use --help");
    }

    private static void help() {
        System.out.println("""
                Contacts tools (Java 21 / Quarkus)
                Run from repository root, or specify --root /path/to/repository.
                  init-env
                  token demo-user|demo-admin|reporter [--save /path/to/new.jwt] [--issuer URL]
                  check-identity [--issuer URL]
                  sanitize-export RAW_JSON NEW_JSON
                Secrets are read from the environment or local .env, never command-line arguments.
                """);
    }
}
