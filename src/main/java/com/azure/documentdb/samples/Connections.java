package com.azure.documentdb.samples;

import com.azure.core.credential.AccessToken;
import com.azure.core.credential.TokenCredential;
import com.azure.core.credential.TokenRequestContext;
import com.azure.core.http.okhttp.OkHttpAsyncHttpClientBuilder;
import com.azure.core.http.policy.RetryOptions;
import com.azure.core.http.policy.FixedDelayOptions;
import com.azure.identity.DefaultAzureCredentialBuilder;
import com.mongodb.MongoClientSettings;
import com.mongodb.MongoCredential;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import okhttp3.OkHttpClient;
import java.time.*;
import java.util.concurrent.*;

/** Owns every transport/executor. DefaultAzureCredential itself has no close() in 1.18.7. */
final class Connections implements AutoCloseable {
    static final String SCOPE = "https://ossrdbms-aad.database.windows.net/.default";
    private MongoClient mongo;
    private OkHttpClient http;
    private ExecutorService credentialExecutor;

    static Connections open(Config config) {
        Config.validateHost(config.host()); // Before credential discovery or any token/key use.
        Connections owned = new Connections();
        try {
            MongoCredential credential;
            if ("native".equals(config.auth())) {
                credential = nativeCredential(config);
            } else {
                Duration timeout = Duration.ofSeconds(config.timeoutSeconds());
                owned.credentialExecutor = Executors.newFixedThreadPool(2);
                owned.http = new OkHttpClient.Builder().followRedirects(false).followSslRedirects(false)
                        .retryOnConnectionFailure(false).callTimeout(timeout).connectTimeout(timeout)
                        .readTimeout(timeout).writeTimeout(timeout).build();
                TokenCredential azure = new DefaultAzureCredentialBuilder()
                        .authorityHost("https://login.microsoftonline.com/")
                        .executorService(owned.credentialExecutor).credentialProcessTimeout(timeout)
                        .httpClient(new OkHttpAsyncHttpClientBuilder(owned.http).followRedirects(false).callTimeout(timeout).build())
                        .retryOptions(new RetryOptions(new FixedDelayOptions(0, Duration.ofMillis(1))))
                        .build();
                credential = MongoCredential.createOidcCredential(null)
                        .withMechanismProperty("OIDC_CALLBACK", callback(azure, timeout, Clock.systemUTC()));
            }
            owned.mongo = MongoClients.create(settings(config, credential));
            return owned;
        } catch (RuntimeException | Error failure) {
            owned.close();
            throw failure;
        }
    }
    static MongoCredential nativeCredential(Config config) {
        Config.validateHost(config.host());
        char[] password = config.password().toCharArray();
        try { return MongoCredential.createScramSha256Credential(config.username(), "admin", password); }
        finally { java.util.Arrays.fill(password, '\0'); }
    }
    static MongoClientSettings settings(Config config, MongoCredential credential) {
        Config.validateHost(config.host());
        return MongoClientSettings.builder()
                .applyToClusterSettings(b -> b.srvHost(config.host()).serverSelectionTimeout(config.timeoutSeconds(), TimeUnit.SECONDS))
                .applyToSslSettings(b -> b.enabled(true).invalidHostNameAllowed(false))
                .applyToSocketSettings(b -> b.connectTimeout(config.timeoutSeconds(), TimeUnit.SECONDS).readTimeout(config.timeoutSeconds(), TimeUnit.SECONDS))
                .timeout(config.timeoutSeconds(), TimeUnit.SECONDS)
                .credential(credential).retryWrites(false).retryReads(false).build();
    }
    static MongoCredential.OidcCallback callback(TokenCredential azure, Duration configuredTimeout, Clock clock) {
        return context -> {
            Duration timeout = context.getTimeout().compareTo(configuredTimeout) < 0 ? context.getTimeout() : configuredTimeout;
            if (timeout.isNegative() || timeout.isZero()) throw new IllegalStateException("OIDC deadline elapsed");
            // Never use a server-supplied audience or IdP URL to obtain a token.
            AccessToken token = azure.getToken(new TokenRequestContext().addScopes(SCOPE)).block(timeout);
            if (token == null || token.getToken() == null || token.getToken().isBlank()) throw new IllegalStateException("no OIDC token");
            Duration expiresIn = Duration.between(clock.instant(), token.getExpiresAt().toInstant()).minusSeconds(30);
            // Mongo Java 5.13's API contract defines ZERO as non-expiring. Pass positive lifetime.
            // This does not claim proactive driver cache refresh; server expiry remains authoritative.
            if (expiresIn.isNegative() || expiresIn.isZero()) throw new IllegalStateException("OIDC token expired or expiring");
            return new MongoCredential.OidcCallbackResult(token.getToken(), expiresIn);
        };
    }
    MongoClient mongo() { return mongo; }
    @Override public void close() {
        try { if (mongo != null) mongo.close(); }
        finally {
            mongo = null;
            try {
                if (http != null) {
                    try {
                        http.dispatcher().cancelAll();
                        http.connectionPool().evictAll();
                        shutdown(http.dispatcher().executorService());
                    } finally { http = null; }
                }
            } finally {
                if (credentialExecutor != null) {
                    try { shutdown(credentialExecutor); } finally { credentialExecutor = null; }
                }
            }
        }
    }
    private static void shutdown(ExecutorService executor) {
        boolean interrupted = Thread.interrupted();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        executor.shutdownNow();
        try {
            while (!executor.isTerminated()) {
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0) throw new IllegalStateException("transport shutdown timed out");
                try {
                    if (!executor.awaitTermination(remaining, TimeUnit.NANOSECONDS))
                        throw new IllegalStateException("transport shutdown timed out");
                } catch (InterruptedException e) {
                    interrupted = true;
                    executor.shutdownNow();
                    // Keep waiting within the ORIGINAL deadline, then restore interruption.
                }
            }
        } finally { if (interrupted) Thread.currentThread().interrupt(); }
    }
}
