package com.azure.documentdb.samples;

import com.azure.core.credential.AccessToken;
import com.azure.core.credential.TokenCredential;
import com.mongodb.MongoCredential;
import org.bson.Document;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import reactor.core.publisher.Mono;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class SafetyTest {
    @TempDir Path directory;
    static Map<String, String> env(Path data, Path query) {
        return new HashMap<>(Map.of("DOCUMENTDB_HOST", "approved.global.mongocluster.cosmos.azure.com",
                "DOCUMENTDB_DATABASE", "isolated_test", "DOCUMENTDB_COLLECTION_PREFIX", "comparison",
                "ALLOW_DATABASE_WRITES", "true", "DOCUMENTDB_AUTH", "native", "DOCUMENTDB_USERNAME", "test-user",
                "DOCUMENTDB_PASSWORD", "test-password", "DATA_FILE_WITH_VECTORS", data.toString(), "QUERY_VECTOR_FILE", query.toString()));
    }
    static Config config() {
        Map<String,String> env = env(Path.of("data.json"), Path.of("query.json"));
        env.put("TOP_K", "1"); env.put("EMBEDDING_DIMENSIONS", "2"); env.put("SEARCH_RETRY_DELAY_MS", "0");
        return Config.from(env);
    }
    @Test void hostAllowlistRejectsCredentialDestinations() {
        for (String host : List.of("localhost", "127.0.0.1", "a.evil.test", "approved.global.mongocluster.cosmos.azure.com.evil.test",
                "evil@approved.global.mongocluster.cosmos.azure.com", "approved.global.mongocluster.cosmos.azure.com:27017",
                "approved.global.mongocluster.cosmos.azure.com/", "approved.global.mongocluster.cosmos.azure.com.",
                "mongodb+srv://approved.global.mongocluster.cosmos.azure.com", "APPROVED.global.mongocluster.cosmos.azure.com"))
            assertThrows(IllegalArgumentException.class, () -> Config.validateHost(host));
        Config.validateHost("approved.global.mongocluster.cosmos.azure.com");
        Config.validateHost("approved.mongocluster.azure.com");
    }
    @Test void writeConsentNamesAndAuthFailClosed() {
        for (Map.Entry<String,String> invalid : Map.of("ALLOW_DATABASE_WRITES", "TRUE", "DOCUMENTDB_DATABASE", "admin",
                "DOCUMENTDB_COLLECTION_PREFIX", "system.test", "DOCUMENTDB_AUTH", "automatic", "TOP_K", "0",
                "SEARCH_ATTEMPTS", "100", "EMBEDDED_FIELD", "$vector", "OPERATION_TIMEOUT_SECONDS", "0").entrySet()) {
            Map<String,String> env = env(Path.of("x"), Path.of("y")); env.put(invalid.getKey(), invalid.getValue());
            assertThrows(IllegalArgumentException.class, () -> Config.from(env), invalid.getKey());
        }
        Map<String,String> env = env(Path.of("x"), Path.of("y")); env.remove("ALLOW_DATABASE_WRITES");
        assertThrows(IllegalArgumentException.class, () -> Config.from(env));
        env.put("ALLOW_DATABASE_WRITES", "true"); env.put("DOCUMENTDB_AUTH", "oidc");
        assertThrows(IllegalArgumentException.class, () -> Config.from(env));
        env.remove("DOCUMENTDB_USERNAME"); env.remove("DOCUMENTDB_PASSWORD");
        assertEquals("oidc", Config.from(env).auth());
        assertFalse(config().toString().contains("test-password"));
    }
    @Test void nativePasswordsKeepSpecialCharactersWithoutUriInterpolation() {
        var env = env(Path.of("x"), Path.of("y"));
        String password = "test:@/?#<>$%& +";
        env.put("DOCUMENTDB_PASSWORD", password);
        var credential = Connections.nativeCredential(Config.from(env));
        assertArrayEquals(password.toCharArray(), credential.getPassword());
        assertEquals("SCRAM-SHA-256", credential.getMechanism());
        assertEquals("admin", credential.getSource());
    }
    @Test void tlsAndTimeoutsAreNotOverrideable() {
        Config config = config();
        var credential = MongoCredential.createScramSha256Credential("test", "admin", "pass".toCharArray());
        var settings = Connections.settings(config, credential);
        assertTrue(settings.getSslSettings().isEnabled());
        assertFalse(settings.getSslSettings().isInvalidHostNameAllowed());
        assertFalse(settings.getRetryWrites()); assertFalse(settings.getRetryReads());
        assertEquals("SCRAM-SHA-256", settings.getCredential().getMechanism());
        assertEquals("admin", settings.getCredential().getSource());
        assertEquals(20, settings.getClusterSettings().getServerSelectionTimeout(java.util.concurrent.TimeUnit.SECONDS));
    }
    @Test void oidcUsesFixedAudienceAndRealExpiryNotServerAudience() throws Exception {
        Instant now = Instant.parse("2026-10-12T12:00:00Z");
        AtomicReference<List<String>> scopes = new AtomicReference<>();
        TokenCredential credential = request -> {
            scopes.set(request.getScopes());
            return Mono.just(new AccessToken("test-token", OffsetDateTime.ofInstant(now.plusSeconds(300), ZoneOffset.UTC)));
        };
        MongoCredential.OidcCallbackContext context = mock(MongoCredential.OidcCallbackContext.class);
        when(context.getTimeout()).thenReturn(Duration.ofSeconds(5));
        var result = Connections.callback(credential, Duration.ofSeconds(10), Clock.fixed(now, ZoneOffset.UTC)).onRequest(context);
        assertEquals(List.of(Connections.SCOPE), scopes.get());
        assertEquals("test-token", result.getAccessToken());
        var expires = result.getClass().getDeclaredField("expiresIn"); expires.setAccessible(true);
        assertEquals(Duration.ofSeconds(270), expires.get(result));
        verify(context, never()).getIdpInfo();
    }
    @Test void oidcExpiredMissingAndBoundedTokensFailClosed() {
        Instant now = Instant.parse("2026-10-12T12:00:00Z");
        var context = mock(MongoCredential.OidcCallbackContext.class);
        when(context.getTimeout()).thenReturn(Duration.ofMillis(10));
        for (int seconds : List.of(-10, 0, 30)) {
            TokenCredential credential = request -> Mono.just(new AccessToken("test", OffsetDateTime.ofInstant(now.plusSeconds(seconds), ZoneOffset.UTC)));
            assertThrows(IllegalStateException.class, () -> Connections.callback(credential, Duration.ofSeconds(1), Clock.fixed(now, ZoneOffset.UTC)).onRequest(context));
        }
        assertThrows(IllegalStateException.class, () -> Connections.callback(request -> Mono.empty(), Duration.ofSeconds(1), Clock.systemUTC()).onRequest(context));
        assertTimeoutPreemptively(Duration.ofSeconds(2), () -> assertThrows(IllegalStateException.class,
                () -> Connections.callback(request -> Mono.never(), Duration.ofSeconds(1), Clock.systemUTC()).onRequest(context)));
    }
    @Test void fixtureValidationAndDeterministicIds() throws Exception {
        Path data = directory.resolve("data.json"), query = directory.resolve("query.json");
        Files.writeString(data, "[{\"HotelId\":\"1\",\"DescriptionVector\":[1,2]}]");
        Files.writeString(query, "[1,2]");
        Map<String,String> env = env(data, query); env.put("EMBEDDING_DIMENSIONS", "2"); env.put("TOP_K", "1");
        Fixture fixture = Fixture.load(Config.from(env));
        assertEquals("hotel:1", fixture.documents().getFirst().getString("_id"));
        assertEquals(List.of(1.0, 2.0), fixture.query());
        Files.writeString(query, "[0,0]"); assertThrows(IllegalArgumentException.class, () -> Fixture.load(Config.from(env)));
        Files.writeString(query, "[1,2,3]"); assertThrows(IllegalArgumentException.class, () -> Fixture.load(Config.from(env)));
        Files.writeString(query, "[1,2]");
        Files.writeString(data, "[{\"HotelId\":\"1\",\"DescriptionVector\":[1,2]},{\"HotelId\":\"1\",\"DescriptionVector\":[2,3]}]");
        assertThrows(IllegalArgumentException.class, () -> Fixture.load(Config.from(env)));
        assertThrows(IllegalArgumentException.class, () -> Fixture.vector(List.of(Double.NaN, 1), 2));
        assertThrows(IllegalArgumentException.class, () -> Fixture.vector(List.of("1", 2), 2));
    }
    @Test void fullSizeFiftyBy1536FixtureUsesDefaultsWithoutModelSettings() throws Exception {
        Path data = directory.resolve("full-data.json"), query = directory.resolve("full-query.json");
        List<Double> vector = java.util.Collections.nCopies(1536, 0.1);
        List<Document> documents = new ArrayList<>();
        for (int i = 0; i < 50; i++) documents.add(new Document("HotelId", Integer.toString(i)).append("DescriptionVector", vector));
        Files.writeString(data, documents.stream().map(Document::toJson).collect(java.util.stream.Collectors.joining(",", "[", "]")));
        Files.writeString(query, vector.toString());
        Fixture fixture = Fixture.load(Config.from(env(data, query)));
        assertEquals(50, fixture.documents().size()); assertEquals(1536, fixture.query().size());
        assertEquals(50, fixture.documents().stream().map(d -> d.get("_id")).distinct().count());
    }
    @Test void allThreeDistinctDefinitionsAndCollections() {
        Config config = config(); Set<String> names = new HashSet<>();
        for (var c : Comparison.cases()) {
            names.add(config.collection(c));
            Document index = MongoStore.index(config, c);
            assertEquals(c.metric(), index.get("cosmosSearchOptions", Document.class).getString("similarity"));
            assertEquals("vector-" + c.algorithm(), index.get("cosmosSearchOptions", Document.class).getString("kind"));
        }
        assertEquals(3, names.size());
    }
}
