package com.azure.documentdb.samples;

import com.mongodb.MongoClientSettings;
import com.mongodb.ServerAddress;
import com.mongodb.client.MongoClients;
import java.net.ServerSocket;
import java.util.List;
import java.util.concurrent.TimeUnit;

/** Subprocess harness deliberately uses no System.exit; lingering resources make the test fail. */
public final class FailureProcess {
    public static void main(String[] args) throws Exception {
        int port;
        try (ServerSocket reserved = new ServerSocket(0, 1, java.net.InetAddress.getLoopbackAddress())) { port = reserved.getLocalPort(); }
        Config config = SafetyTest.config();
        var settings = MongoClientSettings.builder()
                .applyToClusterSettings(b -> b.hosts(List.of(new ServerAddress("127.0.0.1", port)))
                        .serverSelectionTimeout(100, TimeUnit.MILLISECONDS))
                .applyToSslSettings(b -> b.enabled(true).invalidHostNameAllowed(false))
                .applyToSocketSettings(b -> b.connectTimeout(100, TimeUnit.MILLISECONDS).readTimeout(100, TimeUnit.MILLISECONDS))
                .timeout(100, TimeUnit.MILLISECONDS).retryReads(false).retryWrites(false).build();
        int status;
        var client = MongoClients.create(settings);
        Connections connections = new Connections();
        var mongoField = Connections.class.getDeclaredField("mongo");
        mongoField.setAccessible(true);
        mongoField.set(connections, client); // Test-only loopback injection; no production host-validation bypass.
        try (var store = new MongoStore(config, connections)) {
            status = Comparison.run(config, ComparisonTest.fixture(), store, System.out, ms -> {});
        }
        System.out.println("HARNESS_STATUS=" + status);
    }
}
