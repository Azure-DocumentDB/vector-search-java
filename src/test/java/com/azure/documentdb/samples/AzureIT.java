package com.azure.documentdb.samples;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Explicit paid-resource gate. Selecting the profile without consent FAILS, never skips. */
class AzureIT {
    @Test void allThreeOnExplicitlyApprovedAzureResource() {
        assertEquals("true", System.getenv("RUN_AZURE_INTEGRATION"),
                "Set RUN_AZURE_INTEGRATION=true only for an authorized isolated Azure resource");
        assertEquals("true", System.getenv("VERIFY_FIXTURE_RANKING"), "Integration must verify ranks and cosine scores");
        Config config = Config.from(System.getenv());
        Fixture fixture = Fixture.load(config);
        assertDoesNotThrow(() -> assertEquals("60c08c2cdd439444ba8601d94a1f3487f4a9ee56cd48ba201d983c96722de6e4",
                java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                        .digest(java.nio.file.Files.readAllBytes(config.dataFile()))), "Pinned fixture checksum"));
        assertEquals(50, fixture.documents().size(), "Use the pinned smoke fixture");
        assertEquals(1536, fixture.query().size());
        assertEquals(fixture.documents().getFirst().get(config.field()), fixture.query(), "Use the extracted self-query");
        try (Connections connections = Connections.open(config); MongoStore store = new MongoStore(config, connections)) {
            assertEquals(0, Comparison.run(config, fixture, store, System.out, Thread::sleep), "Every one of three cases must pass");
            // Second run is idempotent. Independent inspection is not conditioned on document count.
            for (var c : Comparison.cases()) {
                var counts = store.prepare(c, fixture);
                assertEquals(0, counts.inserted()); assertTrue(counts.indexReused());
                assertEquals(fixture.documents().size(), counts.fixture());
                assertTrue(counts.total() >= counts.fixture());
            }
        }
    }
}
