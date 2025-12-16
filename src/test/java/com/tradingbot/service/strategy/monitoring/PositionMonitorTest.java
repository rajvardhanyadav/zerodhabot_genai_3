package com.tradingbot.service.strategy.monitoring;

import com.zerodhatech.models.Tick;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertTrue;

public class PositionMonitorTest {

    @Test
    public void cumulativeTargetTriggersExit() {
        PositionMonitor monitor = new PositionMonitor("exec-1", 2.0, 2.0);
        // two legs each 1.0 point up should total 2.0
        monitor.addLeg("o1", "CALL1", 111L, 100.0, 1, "CE");
        monitor.addLeg("o2", "PUT1", 222L, 50.0, 1, "PE");

        AtomicBoolean exited = new AtomicBoolean(false);
        monitor.setExitCallback(reason -> exited.set(true));

        // Create tick data with updated prices
        ArrayList<Tick> ticks = new ArrayList<>();
        Tick tick1 = new Tick();
        tick1.setInstrumentToken(111L);
        tick1.setLastTradedPrice(101.0);

        Tick tick2 = new Tick();
        tick2.setInstrumentToken(222L);
        tick2.setLastTradedPrice(51.0);

        ticks.add(tick1);
        ticks.add(tick2);

        // Run the check using tick updates - this updates prices and checks thresholds
        monitor.updatePriceWithDifferenceCheck(ticks);

        assertTrue(exited.get(), "Expected monitor to trigger cumulative exit");
    }
}
