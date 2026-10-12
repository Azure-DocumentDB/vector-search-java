package com.azure.documentdb.samples;

import com.mongodb.MongoException;
import org.bson.Document;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class ComparisonTest {
    @TempDir Path directory;
    static Fixture fixture() { return new Fixture(List.of(new Document("_id", "hotel:1").append("HotelId", "1").append("DescriptionVector", List.of(1.0, 2.0))), List.of(1.0, 2.0)); }
    static List<Document> hits() { return List.of(new Document("_id", "hotel:1").append("score", 0.0)); }
    static Comparison.Store passing() {
        var store = mock(Comparison.Store.class);
        when(store.prepare(any(), any())).thenReturn(new Comparison.Counts(2, 1, 0, true));
        when(store.search(any(), any())).thenReturn(hits());
        return store;
    }
    @Test void allThreePassWithRealZeroScoreNotMissingSentinel() {
        var store = passing(); var output = new ByteArrayOutputStream();
        assertEquals(0, Comparison.run(SafetyTest.config(), fixture(), store, new PrintStream(output), ms -> {}));
        verify(store, times(3)).prepare(any(), any()); verify(store, times(3)).search(any(), any());
        assertEquals(3, output.toString().lines().filter(s -> s.contains("\"status\": \"PASS\"")).count());
        assertTrue(output.toString().contains("\"score\": 0.0"));
    }
    @Test void middleFailureStillAttemptsAllThreeAndReturnsNonzero() {
        var store = passing();
        when(store.prepare(eq(Comparison.cases().get(1)), any())).thenThrow(new MongoException(67, "SECRET_PASSWORD host.invalid"));
        var output = new ByteArrayOutputStream();
        assertEquals(1, Comparison.run(SafetyTest.config(), fixture(), store, new PrintStream(output), ms -> {}));
        verify(store, times(3)).prepare(any(), any()); verify(store, times(2)).search(any(), any());
        assertTrue(output.toString().contains("\"failed\": 1"));
        assertFalse(output.toString().contains("SECRET_PASSWORD")); assertFalse(output.toString().contains("host.invalid"));
    }
    @Test void firstFailureStillAttemptsRemainingAlgorithms() {
        var store = passing();
        when(store.prepare(eq(Comparison.cases().getFirst()), any())).thenThrow(new IllegalStateException("first failure"));
        assertEquals(1, Comparison.run(SafetyTest.config(), fixture(), store, new PrintStream(new ByteArrayOutputStream()), ms -> {}));
        verify(store, times(3)).prepare(any(), any()); verify(store, times(2)).search(any(), any());
    }
    @Test void selectedModeRunsExactlyOneAndInvalidModeFails() {
        for (String mode : List.of("ivf", "hnsw", "diskann")) {
            var store = passing();
            assertEquals(0, Comparison.run(SafetyTest.config(), fixture(), store,
                    new PrintStream(new ByteArrayOutputStream()), ms -> {}, Comparison.cases(mode)));
            verify(store).prepare(eq(new Comparison.Case(mode, "COS")), any());
        }
        assertThrows(IllegalArgumentException.class, () -> Comparison.cases("DROP"));
    }
    @Test void emptyAndMissingScoreNeverReportSuccessfulZeros() {
        for (List<Document> bad : List.of(List.<Document>of(), List.of(new Document("_id", "hotel:1")),
                List.of(new Document("_id", "hotel:1").append("score", Double.NaN)))) {
            var store = passing(); when(store.search(any(), any())).thenReturn(bad);
            var output = new ByteArrayOutputStream();
            assertEquals(1, Comparison.run(SafetyTest.config(), fixture(), store, new PrintStream(output), ms -> {}));
            assertFalse(output.toString().contains("\"status\": \"PASS\""));
            assertFalse(output.toString().contains("\"score\": 0"));
        }
    }
    @Test void retriesAreBoundedAndCanRecover() throws Exception {
        var store = passing(); when(store.search(any(), any())).thenReturn(List.of()).thenThrow(new MongoException(2, "not ready")).thenReturn(hits());
        assertEquals(hits(), Comparison.search(SafetyTest.config(), fixture(), store, Comparison.cases().getFirst(), ms -> {}));
        verify(store, times(3)).search(any(), any());
        store = passing(); when(store.search(any(), any())).thenReturn(List.of());
        Comparison.Store empty = store;
        assertThrows(IllegalStateException.class, () -> Comparison.search(SafetyTest.config(), fixture(), empty, Comparison.cases().getFirst(), ms -> {}));
        verify(store, times(4)).search(any(), any());
    }
    @Test void retryDeadlineDoesNotResetAndRejectsLateSuccess() {
        var store = passing();
        var clock = new java.util.concurrent.atomic.AtomicLong();
        when(store.search(any(), any())).thenAnswer(call -> {
            clock.addAndGet(java.util.concurrent.TimeUnit.SECONDS.toNanos(81));
            return hits();
        });
        assertThrows(IllegalStateException.class, () -> Comparison.search(SafetyTest.config(), fixture(), store,
                Comparison.cases().getFirst(), ms -> {}, clock::get));
        verify(store).search(any(), any());
    }
    @Test void mainClosesOnSuccessFailureAndShutdownError() throws Exception {
        Path data = directory.resolve("data.json"), query = directory.resolve("query.json");
        Files.writeString(data, "[{\"HotelId\":\"1\",\"DescriptionVector\":[1,2]}]"); Files.writeString(query, "[1,2]");
        var env = SafetyTest.env(data, query); env.put("TOP_K", "1"); env.put("EMBEDDING_DIMENSIONS", "2");
        PrintStream sink = new PrintStream(new ByteArrayOutputStream());
        var success = passing(); assertEquals(0, Main.run(env, c -> success, sink, sink)); verify(success).close();
        var failure = passing(); when(failure.prepare(any(), any())).thenThrow(new IllegalStateException("failure"));
        assertEquals(1, Main.run(env, c -> failure, sink, sink)); verify(failure).close();
        var closeFailure = passing(); doThrow(new IllegalStateException("close failed")).when(closeFailure).close();
        assertEquals(1, Main.run(env, c -> closeFailure, sink, sink));
    }
    @Test void invalidConfigAndFixtureNeverOpenClients() {
        Main.StoreFactory factory = mock(Main.StoreFactory.class);
        PrintStream sink = new PrintStream(new ByteArrayOutputStream());
        assertEquals(2, Main.run(Map.of(), factory, sink, sink));
        assertEquals(2, Main.run(SafetyTest.env(directory.resolve("missing"), directory.resolve("missing2")), factory, sink, sink));
        verifyNoInteractions(factory);
    }
}
