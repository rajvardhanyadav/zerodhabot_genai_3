package com.tradingbot.service.strategy.monitoring;

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

public class PositionMonitorTest {

    @Test
    void triggersAllLegsExitAtPositiveThreshold() {
        PositionMonitor monitor = new PositionMonitor("exec-1", 10.0, 15.0);
        AtomicReference<String> exitReason = new AtomicReference<>(null);
        monitor.setExitCallback(exitReason::set);

        // Add two legs at entry 100.0
        monitor.addLeg("order-1", "SYM-CE", 101L, 100.0, 50, "CE");
        monitor.addLeg("order-2", "SYM-PE", 102L, 100.0, 50, "PE");

        // Update prices so one leg diff >= 3.0 (e.g., 103.1)
        monitor.updateWithTokenPrices(Map.of(101L, 103.1, 102L, 99.8));

        assertFalse(monitor.isActive(), "Monitor should become inactive after all-legs exit");
        assertNotNull(exitReason.get(), "Exit reason should be set");
        assertTrue(exitReason.get().startsWith("PRICE_DIFF_ALL_LEGS"));
    }

    @Test
    void triggersIndividualLegExitAtNegativeThreshold() {
        PositionMonitor monitor = new PositionMonitor("exec-2", 10.0, 15.0);
        AtomicBoolean individualExitCalled = new AtomicBoolean(false);
        monitor.setIndividualLegExitCallback((legSymbol, reason) -> individualExitCalled.set(true));

        // Add two legs at entry 200.0
        monitor.addLeg("order-3", "SYM-CE2", 201L, 200.0, 50, "CE");
        monitor.addLeg("order-4", "SYM-PE2", 202L, 200.0, 50, "PE");

        // Update one leg to drop <= -1.5 (e.g., 198.4 => diff -1.6)
        monitor.updateWithTokenPrices(Map.of(201L, 198.4, 202L, 200.2));

        assertTrue(individualExitCalled.get(), "Individual leg exit callback should be invoked");
        assertTrue(monitor.isActive(), "Monitor should remain active if other legs remain");
        assertEquals(1, monitor.getLegs().size(), "One leg should remain after individual exit");
    }
}

