package com.tradingbot.service;

import com.tradingbot.dto.BotStatusResponse;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class BotStatusServiceTest {

    @Test
    void defaultStateIsStopped() {
        BotStatusService svc = new BotStatusService();
        BotStatusResponse status = svc.getStatus();
        assertEquals("STOPPED", status.getStatus());
        assertNotNull(status.getLastUpdated());
    }

    @Test
    void markRunningAndStoppedUpdatesStateAndTimestamp() throws InterruptedException {
        BotStatusService svc = new BotStatusService();
        Instant t0 = svc.getStatus().getLastUpdated();
        Thread.sleep(5);
        svc.markRunning();
        BotStatusResponse running = svc.getStatus();
        assertEquals("RUNNING", running.getStatus());
        assertTrue(running.getLastUpdated().isAfter(t0));

        Thread.sleep(5);
        svc.markStopped();
        BotStatusResponse stopped = svc.getStatus();
        assertEquals("STOPPED", stopped.getStatus());
        assertTrue(stopped.getLastUpdated().isAfter(running.getLastUpdated()));
    }

    @Test
    void concurrentUpdatesDoNotCrash() throws InterruptedException {
        BotStatusService svc = new BotStatusService();
        int threads = 10;
        CountDownLatch latch = new CountDownLatch(threads);
        var pool = Executors.newFixedThreadPool(threads);
        for (int i = 0; i < threads; i++) {
            int idx = i;
            pool.submit(() -> {
                try {
                    if (idx % 2 == 0) svc.markRunning(); else svc.markStopped();
                } finally {
                    latch.countDown();
                }
            });
        }
        assertTrue(latch.await(3, TimeUnit.SECONDS));
        // State is either RUNNING or STOPPED; just ensure lastUpdated is set
        assertNotNull(svc.getStatus().getLastUpdated());
        pool.shutdownNow();
    }
}

