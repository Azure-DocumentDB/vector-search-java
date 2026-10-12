package com.azure.documentdb.samples;

import com.mongodb.client.MongoClient;
import okhttp3.OkHttpClient;
import org.junit.jupiter.api.Test;
import java.lang.reflect.Field;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ResourceTest {
    @Test void allOwnedResourcesCloseEvenIfMongoCloseFails() throws Exception {
        Connections connections = new Connections();
        MongoClient mongo = mock(MongoClient.class);
        doThrow(new IllegalStateException("test close failure")).when(mongo).close();
        OkHttpClient http = new OkHttpClient.Builder().followRedirects(false).followSslRedirects(false).build();
        ExecutorService executor = Executors.newFixedThreadPool(1);
        ExecutorService httpExecutor = http.dispatcher().executorService();
        executor.submit(() -> {}).get(1, TimeUnit.SECONDS);
        httpExecutor.submit(() -> {}).get(1, TimeUnit.SECONDS);
        set(connections, "mongo", mongo); set(connections, "http", http); set(connections, "credentialExecutor", executor);
        assertThrows(IllegalStateException.class, connections::close);
        assertTrue(executor.isTerminated()); assertTrue(httpExecutor.isTerminated());
        assertEquals(0, http.connectionPool().connectionCount());
        assertDoesNotThrow(connections::close); // idempotent ownership cleanup
        verify(mongo).close();
    }
    @Test void interruptedCloseWaitsForTerminationAndPreservesInterrupt() throws Exception {
        Connections connections = new Connections();
        ExecutorService executor = Executors.newSingleThreadExecutor();
        CountDownLatch running = new CountDownLatch(1);
        executor.submit(() -> {
            running.countDown();
            try { new CountDownLatch(1).await(); }
            catch (InterruptedException expected) { /* cooperative shutdown */ }
        });
        assertTrue(running.await(1, TimeUnit.SECONDS));
        set(connections, "credentialExecutor", executor);
        Thread.currentThread().interrupt();
        try {
            assertTimeout(java.time.Duration.ofSeconds(2), connections::close);
            assertTrue(executor.isTerminated());
            assertTrue(Thread.currentThread().isInterrupted());
        } finally { Thread.interrupted(); executor.shutdownNow(); }
    }
    @Test void interruptedUnverifiedShutdownIsAnExplicitFailure() throws Exception {
        Connections connections = new Connections();
        ExecutorService executor = mock(ExecutorService.class);
        when(executor.awaitTermination(org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.eq(TimeUnit.NANOSECONDS)))
                .thenThrow(new InterruptedException("test")).thenReturn(false);
        set(connections, "credentialExecutor", executor);
        try {
            assertThrows(IllegalStateException.class, connections::close);
            assertTrue(Thread.currentThread().isInterrupted());
            verify(executor, times(2)).shutdownNow();
        } finally { Thread.interrupted(); }
    }
    private static void set(Object target, String name, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(name); field.setAccessible(true); field.set(target, value);
    }
}
