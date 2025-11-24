package com.tradingbot.service.strategy.monitoring;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertTrue;

public class PositionMonitorTest {

    @Test
    public void cumulativeTargetTriggersExit() {
        PositionMonitor monitor = new PositionMonitor("exec-1", 2.0, 2.0);
        // two legs each 1.0 point up should total 2.0
        monitor.addLeg("o1", "CALL1", 111L, 100.0, 1, "CE");
        monitor.addLeg("o2", "PUT1", 222L, 50.0, 1, "PE");

        // Set current prices
        monitor.getLegs().forEach(l -> {
            if (l.getSymbol().equals("CALL1")) {
                l.setCurrentPrice(BigDecimal.valueOf(101.0));
            } else if (l.getSymbol().equals("PUT1")) {
                l.setCurrentPrice(BigDecimal.valueOf(51.0));
            }
        });

        AtomicBoolean exited = new AtomicBoolean(false);
        monitor.setExitCallback(reason -> exited.set(true));

        // Run the check using token price map helper
        monitor.updateWithTokenPrices(java.util.Map.of(111L, 101.0, 222L, 51.0));

        assertTrue(exited.get(), "Expected monitor to trigger cumulative exit");
    }
}
