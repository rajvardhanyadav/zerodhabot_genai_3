package com.tradingbot.service.strategy;

import com.tradingbot.config.StrategyConfig;
import com.tradingbot.model.StrategyCompletionReason;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for DailyPnlGateService — thread safety, threshold logic, and reset behavior.
 */
class DailyPnlGateServiceTest {

    private StrategyConfig config;
    private DailyPnlGateService service;

    @BeforeEach
    void setUp() {
        config = new StrategyConfig();
        config.setDailyMaxProfit(5000);
        config.setDailyMaxLoss(3000);
        service = new DailyPnlGateService(config);
    }

    // ==================== ACCUMULATION TESTS ====================

    @Test
    void testAccumulatePositivePnl() {
        service.accumulate("user-1", BigDecimal.valueOf(1000));
        assertEquals(BigDecimal.valueOf(1000), service.getDailyPnl("user-1"));

        service.accumulate("user-1", BigDecimal.valueOf(2000));
        assertEquals(BigDecimal.valueOf(3000), service.getDailyPnl("user-1"));
    }

    @Test
    void testAccumulateNegativePnl() {
        service.accumulate("user-1", BigDecimal.valueOf(-500));
        assertEquals(BigDecimal.valueOf(-500), service.getDailyPnl("user-1"));

        service.accumulate("user-1", BigDecimal.valueOf(-1000));
        assertEquals(BigDecimal.valueOf(-1500), service.getDailyPnl("user-1"));
    }

    @Test
    void testAccumulateMixedPnl() {
        service.accumulate("user-1", BigDecimal.valueOf(2000));
        service.accumulate("user-1", BigDecimal.valueOf(-500));
        service.accumulate("user-1", BigDecimal.valueOf(1500));
        assertEquals(BigDecimal.valueOf(3000), service.getDailyPnl("user-1"));
    }

    @Test
    void testUserIsolation() {
        service.accumulate("user-1", BigDecimal.valueOf(1000));
        service.accumulate("user-2", BigDecimal.valueOf(-2000));

        assertEquals(BigDecimal.valueOf(1000), service.getDailyPnl("user-1"));
        assertEquals(BigDecimal.valueOf(-2000), service.getDailyPnl("user-2"));
    }

    @Test
    void testGetDailyPnlForUnknownUser() {
        assertEquals(BigDecimal.ZERO, service.getDailyPnl("unknown-user"));
    }

    @Test
    void testAccumulateWithNullInputs() {
        // Should not throw
        service.accumulate(null, BigDecimal.valueOf(100));
        service.accumulate("user-1", null);
        assertEquals(BigDecimal.ZERO, service.getDailyPnl("user-1"));
    }

    // ==================== BREACH DETECTION TESTS ====================

    @Test
    void testProfitLimitBreached() {
        service.accumulate("user-1", BigDecimal.valueOf(5000)); // exactly at limit
        assertTrue(service.isBreached("user-1"));

        Optional<StrategyCompletionReason> reason = service.getBreachReason("user-1");
        assertTrue(reason.isPresent());
        assertEquals(StrategyCompletionReason.DAY_PROFIT_LIMIT_HIT, reason.get());
    }

    @Test
    void testProfitLimitExceeded() {
        service.accumulate("user-1", BigDecimal.valueOf(6000)); // over limit
        assertTrue(service.isBreached("user-1"));
        assertEquals(StrategyCompletionReason.DAY_PROFIT_LIMIT_HIT,
                service.getBreachReason("user-1").orElse(null));
    }

    @Test
    void testLossLimitBreached() {
        service.accumulate("user-1", BigDecimal.valueOf(-3000)); // exactly at loss limit
        assertTrue(service.isBreached("user-1"));

        Optional<StrategyCompletionReason> reason = service.getBreachReason("user-1");
        assertTrue(reason.isPresent());
        assertEquals(StrategyCompletionReason.DAY_LOSS_LIMIT_HIT, reason.get());
    }

    @Test
    void testLossLimitExceeded() {
        service.accumulate("user-1", BigDecimal.valueOf(-4000));
        assertTrue(service.isBreached("user-1"));
        assertEquals(StrategyCompletionReason.DAY_LOSS_LIMIT_HIT,
                service.getBreachReason("user-1").orElse(null));
    }

    @Test
    void testNoBreachWithinLimits() {
        service.accumulate("user-1", BigDecimal.valueOf(4999));
        assertFalse(service.isBreached("user-1"));
        assertTrue(service.getBreachReason("user-1").isEmpty());
    }

    @Test
    void testNoBreachWhenLossWithinLimit() {
        service.accumulate("user-1", BigDecimal.valueOf(-2999));
        assertFalse(service.isBreached("user-1"));
        assertTrue(service.getBreachReason("user-1").isEmpty());
    }

    @Test
    void testNoBreachForUnknownUser() {
        assertFalse(service.isBreached("unknown-user"));
        assertTrue(service.getBreachReason("unknown-user").isEmpty());
    }

    @Test
    void testNoBreachWhenThresholdsDisabled() {
        // Disable both thresholds
        config.setDailyMaxProfit(0);
        config.setDailyMaxLoss(0);

        service.accumulate("user-1", BigDecimal.valueOf(999999));
        assertFalse(service.isBreached("user-1"));
        assertTrue(service.getBreachReason("user-1").isEmpty());

        service.accumulate("user-1", BigDecimal.valueOf(-999999));
        assertFalse(service.isBreached("user-1"));
    }

    @Test
    void testOnlyProfitThresholdEnabled() {
        config.setDailyMaxProfit(5000);
        config.setDailyMaxLoss(0); // disabled

        service.accumulate("user-1", BigDecimal.valueOf(-999999));
        assertFalse(service.isBreached("user-1")); // loss limit disabled

        service.resetUser("user-1");
        service.accumulate("user-1", BigDecimal.valueOf(5000));
        assertTrue(service.isBreached("user-1")); // profit limit active
    }

    @Test
    void testOnlyLossThresholdEnabled() {
        config.setDailyMaxProfit(0); // disabled
        config.setDailyMaxLoss(3000);

        service.accumulate("user-1", BigDecimal.valueOf(999999));
        assertFalse(service.isBreached("user-1")); // profit limit disabled

        service.resetUser("user-1");
        service.accumulate("user-1", BigDecimal.valueOf(-3000));
        assertTrue(service.isBreached("user-1")); // loss limit active
    }

    // ==================== RESET TESTS ====================

    @Test
    void testResetUser() {
        service.accumulate("user-1", BigDecimal.valueOf(5000));
        assertTrue(service.isBreached("user-1"));

        service.resetUser("user-1");
        assertEquals(BigDecimal.ZERO, service.getDailyPnl("user-1"));
        assertFalse(service.isBreached("user-1"));
    }

    @Test
    void testResetAll() {
        service.accumulate("user-1", BigDecimal.valueOf(5000));
        service.accumulate("user-2", BigDecimal.valueOf(-3000));

        service.resetAll();

        assertEquals(BigDecimal.ZERO, service.getDailyPnl("user-1"));
        assertEquals(BigDecimal.ZERO, service.getDailyPnl("user-2"));
    }

    @Test
    void testResetNullUser() {
        // Should not throw
        service.resetUser(null);
    }

    // ==================== THREAD SAFETY TESTS ====================

    @Test
    void testConcurrentAccumulation() throws InterruptedException {
        int threadCount = 10;
        int iterationsPerThread = 1000;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch latch = new CountDownLatch(threadCount);

        for (int i = 0; i < threadCount; i++) {
            executor.submit(() -> {
                try {
                    for (int j = 0; j < iterationsPerThread; j++) {
                        service.accumulate("user-1", BigDecimal.ONE);
                    }
                } finally {
                    latch.countDown();
                }
            });
        }

        assertTrue(latch.await(10, TimeUnit.SECONDS));
        executor.shutdown();

        // All increments should be visible
        BigDecimal expected = BigDecimal.valueOf((long) threadCount * iterationsPerThread);
        assertEquals(expected, service.getDailyPnl("user-1"));
    }
}

