package com.azure.documentdb.samples;

import com.azure.core.credential.AccessToken;
import com.azure.core.credential.TokenCredential;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.mongodb.MongoCredential;
import okhttp3.*;
import org.bson.Document;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import java.time.*;
import java.util.concurrent.TimeUnit;
import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** HTTP contract mock only; no Azure authentication or SDK-live compatibility claim. */
class WireMockTest {
    @Test void oidcCallbackConsumesHttpTokenExpiryAndRejectsExpiredResponse() throws Exception {
        WireMockServer server = new WireMockServer(wireMockConfig().dynamicPort().bindAddress("127.0.0.1"));
        server.start();
        OkHttpClient http = new OkHttpClient.Builder().followRedirects(false).followSslRedirects(false)
                .callTimeout(1, TimeUnit.SECONDS).build();
        Instant now = Instant.parse("2026-10-12T12:00:00Z");
        try {
            server.stubFor(post(urlEqualTo("/token")).willReturn(okJson("{\"access_token\":\"mock-only-token\",\"expires_in\":300}")));
            TokenCredential credential = request -> Mono.fromCallable(() -> {
                try (Response response = http.newCall(new Request.Builder().url(server.baseUrl() + "/token")
                        .post(new FormBody.Builder().add("scope", String.join(" ", request.getScopes())).build()).build()).execute()) {
                    if (!response.isSuccessful()) throw new IllegalStateException("HTTP token failure");
                    Document token = Document.parse(response.body().string());
                    return new AccessToken(token.getString("access_token"), OffsetDateTime.ofInstant(
                            now.plusSeconds(token.getInteger("expires_in")), ZoneOffset.UTC));
                }
            });
            var context = mock(MongoCredential.OidcCallbackContext.class);
            when(context.getTimeout()).thenReturn(Duration.ofSeconds(2));
            var callback = Connections.callback(credential, Duration.ofSeconds(1), Clock.fixed(now, ZoneOffset.UTC));
            assertEquals("mock-only-token", callback.onRequest(context).getAccessToken());
            server.verify(postRequestedFor(urlEqualTo("/token"))
                    .withRequestBody(containing("ossrdbms-aad.database.windows.net")));
            verify(context, never()).getIdpInfo();
            server.stubFor(post(urlEqualTo("/token")).willReturn(okJson("{\"access_token\":\"mock-only-token\",\"expires_in\":0}")));
            assertThrows(IllegalStateException.class, () -> callback.onRequest(context));
            server.stubFor(post(urlEqualTo("/token")).willReturn(temporaryRedirect("/unexpected")));
            assertThrows(IllegalStateException.class, () -> callback.onRequest(context));
            server.verify(0, getRequestedFor(urlEqualTo("/unexpected")));
        } finally {
            http.dispatcher().cancelAll(); http.connectionPool().evictAll();
            http.dispatcher().executorService().shutdownNow();
            assertTrue(http.dispatcher().executorService().awaitTermination(2, TimeUnit.SECONDS));
            server.stop();
        }
    }
}
