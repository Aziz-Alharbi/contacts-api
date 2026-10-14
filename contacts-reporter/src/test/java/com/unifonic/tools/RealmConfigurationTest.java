package com.unifonic.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import java.nio.file.*;
import static org.junit.jupiter.api.Assertions.*;

class RealmConfigurationTest {
    JsonNode realm;
    @BeforeEach void loadRealm() throws Exception {
        realm = OAuthTokens.JSON.readTree(Files.readString(Path.of("../identity/realm/contacts-realm.json")));
    }
    JsonNode client(String id) { return ToolsCommand.client(realm, id); }
    JsonNode user(String id) {
        return java.util.stream.StreamSupport.stream(realm.path("users").spliterator(), false)
                .filter(user -> id.equals(user.path("username").asText())).findFirst().orElseThrow();
    }
    JsonNode mapper(String id, String type) {
        return java.util.stream.StreamSupport.stream(client(id).path("protocolMappers").spliterator(), false)
                .filter(mapper -> type.equals(mapper.path("protocolMapper").asText())).findFirst().orElseThrow().path("config");
    }
    @Test void realmIsEnabledAndNamedContacts() { assertEquals("contacts", realm.path("realm").asText()); assertTrue(realm.path("enabled").asBoolean()); }
    @Test void realmDefinesUserAndAdminRoles() { assertEquals(2, realm.path("roles").path("realm").size()); assertEquals("user", realm.at("/roles/realm/0/name").asText()); assertEquals("admin", realm.at("/roles/realm/1/name").asText()); }
    @Test void reporterUsesConfidentialClientCredentials() { assertFalse(client("contacts-reporter").path("publicClient").asBoolean()); assertTrue(client("contacts-reporter").path("serviceAccountsEnabled").asBoolean()); assertFalse(client("contacts-reporter").path("standardFlowEnabled").asBoolean()); assertEquals("client-secret", client("contacts-reporter").path("clientAuthenticatorType").asText()); }
    @Test void reporterScopeIsLimitedToUserRole() { assertFalse(client("contacts-reporter").path("fullScopeAllowed").asBoolean()); assertEquals("user", realm.at("/scopeMappings/1/roles/0").asText()); assertEquals(1, realm.at("/scopeMappings/1/roles").size()); }
    @Test void serviceAccountIsBoundToReporterWithUserRole() { assertEquals("contacts-reporter", user("service-account-contacts-reporter").path("serviceAccountClientId").asText()); assertEquals("user", user("service-account-contacts-reporter").at("/realmRoles/0").asText()); assertEquals(1, user("service-account-contacts-reporter").path("realmRoles").size()); }
    @Test void apiSupportsPublicPkceLogin() { assertTrue(client("contacts-api").path("publicClient").asBoolean()); assertTrue(client("contacts-api").path("standardFlowEnabled").asBoolean()); assertEquals("S256", client("contacts-api").path("attributes").path("pkce.code.challenge.method").asText()); }
    @Test void apiEnablesDeviceAuthorizationGrant() { assertEquals("true", client("contacts-api").path("attributes").path("oauth2.device.authorization.grant.enabled").asText()); }
    @Test void reporterSecretIsEnvironmentPlaceholder() { assertEquals("${CONTACTS_REPORTER_SECRET}", client("contacts-reporter").path("secret").asText()); }
    @Test void demoUserHasOnlyUserRole() { assertEquals(1, user("demo-user").path("realmRoles").size()); assertEquals("user", user("demo-user").at("/realmRoles/0").asText()); }
    @Test void demoAdminHasAdministratorRole() { assertEquals("admin", user("demo-admin").at("/realmRoles/1").asText()); }
    @Test void tokensHaveFiveMinuteLifespan() { assertEquals(300, realm.path("accessTokenLifespan").asInt()); }
    @ParameterizedTest @ValueSource(strings={"contacts-api", "contacts-reporter"})
    void applicationClientIncludesContactsApiAudience(String id) { assertEquals("contacts-api", mapper(id, "oidc-audience-mapper").path("included.client.audience").asText()); assertEquals("true", mapper(id, "oidc-audience-mapper").path("access.token.claim").asText()); }
    @ParameterizedTest @ValueSource(strings={"contacts-api", "contacts-reporter"})
    void applicationClientMapsRolesToArray(String id) { assertEquals("roles", mapper(id, "oidc-usermodel-realm-role-mapper").path("claim.name").asText()); assertEquals("true", mapper(id, "oidc-usermodel-realm-role-mapper").path("multivalued").asText()); }
    @ParameterizedTest @ValueSource(strings={"contacts-api", "contacts-reporter"})
    void applicationClientDisablesPasswordGrant(String id) { assertFalse(client(id).path("directAccessGrantsEnabled").asBoolean()); }
    @ParameterizedTest @CsvSource({"demo-user,CONTACTS_USER_PASSWORD", "demo-admin,CONTACTS_ADMIN_PASSWORD"})
    void demoPasswordRemainsEnvironmentPlaceholder(String id, String variable) { assertEquals("${" + variable + "}", user(id).at("/credentials/0/value").asText()); }
    ObjectNode contaminated() {
        ObjectNode raw = realm.deepCopy();
        ((ObjectNode) raw.at("/clients/1")).put("secret", "fixture-live-client");
        raw.set("users", OAuthTokens.JSON.createArrayNode().addObject().put("username", "fixture-live-user").put("secretData", "fixture-password-hash"));
        raw.putObject("components").put("privateKey", "fixture-private-key");
        raw.putObject("smtpServer").put("password", "fixture-smtp-password");
        return raw;
    }
    @ParameterizedTest @ValueSource(strings={"fixture-live-client", "fixture-live-user", "fixture-password-hash", "fixture-private-key", "fixture-smtp-password"})
    void sanitizerRemovesLiveCredentialAndIdentityMaterial(String sensitive) { assertFalse(ToolsCommand.sanitize(contaminated(), realm).toString().contains(sensitive)); }
    @Test void sanitizerPreservesAllowedRealmSetting() { var raw = contaminated(); raw.put("accessTokenLifespan", 120); assertEquals(120, ToolsCommand.sanitize(raw, realm).path("accessTokenLifespan").asInt()); }
    @Test void sanitizerKeepsDeviceAndPkceSettings() { assertEquals(client("contacts-api").path("attributes"), ToolsCommand.client(ToolsCommand.sanitize(contaminated(), realm), "contacts-api").path("attributes")); }
    @Test void sanitizerRejectsWrongRealm() { var raw = contaminated(); raw.put("realm", "other"); assertThrows(IllegalArgumentException.class, () -> ToolsCommand.sanitize(raw, realm)); }
    @Test void sanitizerRejectsMissingClient() { var raw = contaminated(); raw.putArray("clients"); assertThrows(IllegalArgumentException.class, () -> ToolsCommand.sanitize(raw, realm)); }
}
