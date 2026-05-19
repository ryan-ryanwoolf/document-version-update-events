package com.ryanwoolf.document_version_update_events.config;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DocumentProcessingStripesTest {

    @Test
    void serializesWorkForSameDocumentId() throws InterruptedException {
        DocumentProcessingStripes documentProcessingStripes = new DocumentProcessingStripes(8);
        AtomicInteger concurrentCount = new AtomicInteger();
        AtomicInteger maxConcurrentCount = new AtomicInteger();
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(2);

        Runnable work = () -> runStripedWork(
                documentProcessingStripes,
                "doc-hot-001",
                concurrentCount,
                maxConcurrentCount,
                startLatch,
                doneLatch);

        ExecutorService executorService = Executors.newFixedThreadPool(2);
        try {
            executorService.submit(work);
            executorService.submit(work);
            startLatch.countDown();
            assertTrue(doneLatch.await(5, TimeUnit.SECONDS));
        } finally {
            executorService.shutdown();
            assertTrue(executorService.awaitTermination(5, TimeUnit.SECONDS));
        }

        assertEquals(1, maxConcurrentCount.get());
    }

    @Test
    void allowsParallelWorkForDifferentDocumentIds() throws InterruptedException {
        DocumentProcessingStripes documentProcessingStripes = new DocumentProcessingStripes(8);
        AtomicInteger concurrentCount = new AtomicInteger();
        AtomicInteger maxConcurrentCount = new AtomicInteger();
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(2);

        ExecutorService executorService = Executors.newFixedThreadPool(2);
        try {
            executorService.submit(() -> runStripedWork(
                    documentProcessingStripes,
                    "doc-a",
                    concurrentCount,
                    maxConcurrentCount,
                    startLatch,
                    doneLatch));
            executorService.submit(() -> runStripedWork(
                    documentProcessingStripes,
                    "doc-b",
                    concurrentCount,
                    maxConcurrentCount,
                    startLatch,
                    doneLatch));
            startLatch.countDown();
            assertTrue(doneLatch.await(5, TimeUnit.SECONDS));
        } finally {
            executorService.shutdown();
            assertTrue(executorService.awaitTermination(5, TimeUnit.SECONDS));
        }

        assertEquals(2, maxConcurrentCount.get());
    }

    private static void runStripedWork(
            DocumentProcessingStripes documentProcessingStripes,
            String documentId,
            AtomicInteger concurrentCount,
            AtomicInteger maxConcurrentCount,
            CountDownLatch startLatch,
            CountDownLatch doneLatch) {
        try {
            startLatch.await();
            documentProcessingStripes.withLock(documentId, () -> {
                int active = concurrentCount.incrementAndGet();
                maxConcurrentCount.updateAndGet(current -> Math.max(current, active));
                try {
                    Thread.sleep(100);
                } catch (InterruptedException interruptedException) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(interruptedException);
                } finally {
                    concurrentCount.decrementAndGet();
                }
            });
        } catch (InterruptedException interruptedException) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(interruptedException);
        } finally {
            doneLatch.countDown();
        }
    }

}
