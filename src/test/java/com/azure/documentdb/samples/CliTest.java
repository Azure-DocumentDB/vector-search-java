package com.azure.documentdb.samples;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

class CliTest {
    @TempDir Path directory;
    @Test void actualEntrypointsExitNonzeroWithoutLeakingInvalidCredentials() throws Exception {
        for (String entry : List.of(Main.class.getName(), IVF.class.getName(), HNSW.class.getName(), DiskAnn.class.getName())) {
            ProcessBuilder builder = process(entry);
            builder.environment().put("DOCUMENTDB_HOST", "SECRET-password@attacker.invalid");
            builder.environment().put("DOCUMENTDB_PASSWORD", "SECRET-password");
            Process process = builder.start();
            assertTrue(process.waitFor(10, TimeUnit.SECONDS), "CLI must terminate");
            String output = new String(process.getInputStream().readAllBytes());
            assertEquals(2, process.exitValue()); assertFalse(output.contains("SECRET-password"));
            assertFalse(output.contains("attacker.invalid"));
        }
    }
    @Test void realMongoBoundedFailureClosesWithoutSystemExitOrLingeringThreads() throws Exception {
        // Test-only loopback connection, no cloud, no authentication, no server or data changes.
        Process process = process(FailureProcess.class.getName()).start();
        boolean exited = process.waitFor(15, TimeUnit.SECONDS);
        if (!exited) process.destroyForcibly();
        assertTrue(exited, "Mongo failure process lingered after close");
        String output = new String(process.getInputStream().readAllBytes());
        assertEquals(0, process.exitValue(), output);
        assertTrue(output.contains("HARNESS_STATUS=1"), output);
        assertEquals(3, output.lines().filter(s -> s.contains("\"status\": \"FAIL\"")).count());
    }
    private ProcessBuilder process(String entry) {
        String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        String classpath = System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"));
        ProcessBuilder builder = new ProcessBuilder(java, "-cp", classpath, entry).redirectErrorStream(true);
        builder.environment().keySet().removeIf(k -> k.startsWith("AZURE_") || k.startsWith("DOCUMENTDB_") || k.equals("ALLOW_DATABASE_WRITES"));
        return builder;
    }
}
