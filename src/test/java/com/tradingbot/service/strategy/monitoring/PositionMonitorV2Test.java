package com.tradingbot.service.strategy.monitoring;

import com.tradingbot.model.SlTargetMode;
import com.tradingbot.service.strategy.monitoring.exit.*;
import com.zerodhatech.models.Tick;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.LocalTime;
import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for PositionMonitorV2 using Strategy pattern.
 * Verifies that the refactored implementation behaves identically to PositionMonitor.
 */
class PositionMonitorV2Test {

    private static final String EXECUTION_ID = "test-exec-v2";
    private static final double CALL_ENTRY = 100.0;
    private static final double PUT_ENTRY = 80.0;
    private static final double COMBINED_ENTRY = CALL_ENTRY + PUT_ENTRY; // 180.0

    private PositionMonitorV2 monitor;
    private AtomicReference<String> exitReason;

    @BeforeEach
    void setUp() {
        exitReason = new AtomicReference<>(null);
    }

    private ArrayList<Tick> createTicks(double callPrice, double putPrice) {
        ArrayList<Tick> ticks = new ArrayList<>();

        Tick callTick = new Tick();
        callTick.setInstrumentToken(12345L);
        callTick.setLastTradedPrice(callPrice);
        ticks.add(callTick);

        Tick putTick = new Tick();
        putTick.setInstrumentToken(12346L);
        putTick.setLastTradedPrice(putPrice);
        ticks.add(putTick);

        return ticks;
    }

    @Nested
    @DisplayName("Points-Based Exit Tests")
    class PointsBasedExitTests {

        @BeforeEach
        void createPointsBasedMonitor() {
            monitor = new PositionMonitorV2(
                    EXECUTION_ID,
                    3.0,  // stopLossPoints
                    2.0,  // targetPoints
                    PositionMonitorV2.PositionDirection.SHORT,
                    false, 0, 0,  // trailing stop disabled
                    false, null,  // forced exit disabled
                    false, 0, 0, 0,  // premium-based disabled
                    SlTargetMode.POINTS
            );

            monitor.addLeg("call-order-1", "NIFTY24350CE", 12345L, CALL_ENTRY, 50, "CE");
            monitor.addLeg("put-order-1", "NIFTY24350PE", 12346L, PUT_ENTRY, 50, "PE");
            monitor.setExitCallback(reason -> exitReason.set(reason));
        }

        @Test
        @DisplayName("Should trigger target exit when cumulative profit >= target")
        void shouldTriggerTargetExit() {
            // For SHORT: profit when price decreases
            // Entry: CE=100, PE=80, total=180
            // Current: CE=99, PE=79, total=178 -> P&L = +2 points (target hit)
            ArrayList<Tick> ticks = createTicks(99.0, 79.0);

            monitor.updatePriceWithDifferenceCheck(ticks);

            assertFalse(monitor.isActive());
            assertNotNull(exitReason.get());
            assertTrue(exitReason.get().contains("CUMULATIVE_TARGET_HIT"));
        }

        @Test
        @DisplayName("Should trigger stop loss exit when cumulative loss >= SL")
        void shouldTriggerStopLossExit() {
            // For SHORT: loss when price increases
            // Entry: CE=100, PE=80, total=180
            // Current: CE=102, PE=81, total=183 -> P&L = -3 points (SL hit)
            ArrayList<Tick> ticks = createTicks(102.0, 81.0);

            monitor.updatePriceWithDifferenceCheck(ticks);

            assertFalse(monitor.isActive());
            assertNotNull(exitReason.get());
            assertTrue(exitReason.get().contains("CUMULATIVE_STOPLOSS_HIT"));
        }

        @Test
        @DisplayName("Should not exit when P&L is within thresholds")
        void shouldNotExitWithinThresholds() {
            // Entry: CE=100, PE=80, total=180
            // Current: CE=100.5, PE=80, total=180.5 -> P&L = -0.5 points (within range)
            ArrayList<Tick> ticks = createTicks(100.5, 80.0);

            monitor.updatePriceWithDifferenceCheck(ticks);

            assertTrue(monitor.isActive());
            assertNull(exitReason.get());
        }
    }

    @Nested
    @DisplayName("Premium-Based Exit Tests")
    class PremiumBasedExitTests {

        @BeforeEach
        void createPremiumBasedMonitor() {
            monitor = new PositionMonitorV2(
                    EXECUTION_ID,
                    2.0,  // stopLossPoints (fallback)
                    2.0,  // targetPoints (fallback)
                    PositionMonitorV2.PositionDirection.SHORT,
                    false, 0, 0,  // trailing stop disabled
                    false, null,  // forced exit disabled
                    true,  // premiumBasedExitEnabled
                    COMBINED_ENTRY,  // 180.0
                    0.05,  // 5% target decay
                    0.10,  // 10% SL expansion
                    SlTargetMode.PREMIUM
            );

            monitor.addLeg("call-order-1", "NIFTY24350CE", 12345L, CALL_ENTRY, 50, "CE");
            monitor.addLeg("put-order-1", "NIFTY24350PE", 12346L, PUT_ENTRY, 50, "PE");
            monitor.setExitCallback(reason -> exitReason.set(reason));
        }

        @Test
        @DisplayName("Should have correct premium threshold levels")
        void shouldHaveCorrectThresholdLevels() {
            // Entry premium = 180
            // Target level = 180 * (1 - 0.05) = 171.0
            // SL level = 180 * (1 + 0.10) = 198.0
            assertEquals(171.0, monitor.getTargetPremiumLevel(), 0.01);
            assertEquals(198.0, monitor.getStopLossPremiumLevel(), 0.01);
        }

        @Test
        @DisplayName("Should trigger target exit when premium decays below target level")
        void shouldTriggerPremiumDecayTarget() {
            // Combined LTP needs to be <= 171 (target level)
            // CE=90, PE=80 -> combined=170 (below 171)
            ArrayList<Tick> ticks = createTicks(90.0, 80.0);

            monitor.updatePriceWithDifferenceCheck(ticks);

            assertFalse(monitor.isActive());
            assertNotNull(exitReason.get());
            assertTrue(exitReason.get().contains("PREMIUM_DECAY_TARGET_HIT"));
        }

        @Test
        @DisplayName("Should trigger SL exit when premium expands above SL level")
        void shouldTriggerPremiumExpansionSL() {
            // Combined LTP needs to be >= 198 (SL level)
            // CE=110, PE=90 -> combined=200 (above 198)
            ArrayList<Tick> ticks = createTicks(110.0, 90.0);

            monitor.updatePriceWithDifferenceCheck(ticks);

            assertFalse(monitor.isActive());
            assertNotNull(exitReason.get());
            assertTrue(exitReason.get().contains("PREMIUM_EXPANSION_SL_HIT"));
        }

        @Test
        @DisplayName("Should not exit when premium is between thresholds")
        void shouldNotExitBetweenThresholds() {
            // Combined LTP between 171 and 198
            // CE=95, PE=85 -> combined=180 (between thresholds)
            ArrayList<Tick> ticks = createTicks(95.0, 85.0);

            monitor.updatePriceWithDifferenceCheck(ticks);

            assertTrue(monitor.isActive());
            assertNull(exitReason.get());
        }
    }

    @Nested
    @DisplayName("Strategy Configuration Tests")
    class StrategyConfigurationTests {

        @Test
        @DisplayName("Should configure correct strategies for points-based mode")
        void shouldConfigurePointsBasedStrategies() {
            monitor = new PositionMonitorV2(
                    EXECUTION_ID, 2.0, 2.0, PositionMonitorV2.PositionDirection.SHORT,
                    false, 0, 0, false, null, false, 0, 0, 0, null
            );

            var strategies = monitor.getExitStrategies();

            // Should have: PointsBasedTarget + PointsBasedStopLoss
            assertEquals(2, strategies.size());
            assertTrue(strategies.stream().anyMatch(s -> s.getName().equals("PointsBasedTarget")));
            assertTrue(strategies.stream().anyMatch(s -> s.getName().equals("PointsBasedStopLoss")));
        }

        @Test
        @DisplayName("Should configure correct strategies for premium-based mode")
        void shouldConfigurePremiumBasedStrategies() {
            monitor = new PositionMonitorV2(
                    EXECUTION_ID, 2.0, 2.0, PositionMonitorV2.PositionDirection.SHORT,
                    false, 0, 0, false, null, true, 180.0, 0.05, 0.10, null
            );

            var strategies = monitor.getExitStrategies();

            // Should have: PremiumBasedExit only
            assertEquals(1, strategies.size());
            assertTrue(strategies.stream().anyMatch(s -> s.getName().equals("PremiumBasedExit")));
        }

        @Test
        @DisplayName("Should configure trailing stop when enabled")
        void shouldConfigureTrailingStop() {
            monitor = new PositionMonitorV2(
                    EXECUTION_ID, 2.0, 2.0, PositionMonitorV2.PositionDirection.SHORT,
                    true, 1.0, 0.5, false, null, false, 0, 0, 0, null
            );

            var strategies = monitor.getExitStrategies();

            // Should have: PointsBasedTarget + TrailingStopLoss + PointsBasedStopLoss
            assertEquals(3, strategies.size());
            assertTrue(strategies.stream().anyMatch(s -> s.getName().equals("TrailingStopLoss")));
            assertTrue(monitor.isTrailingStopEnabled());
        }

        @Test
        @DisplayName("Should configure time-based exit when enabled")
        void shouldConfigureTimeBasedExit() {
            LocalTime exitTime = LocalTime.of(15, 10);
            monitor = new PositionMonitorV2(
                    EXECUTION_ID, 2.0, 2.0, PositionMonitorV2.PositionDirection.SHORT,
                    false, 0, 0, true, exitTime, false, 0, 0, 0, null
            );

            var strategies = monitor.getExitStrategies();

            // Should have: TimeBasedForcedExit + PointsBasedTarget + PointsBasedStopLoss
            assertEquals(3, strategies.size());
            assertTrue(strategies.stream().anyMatch(s -> s.getName().equals("TimeBasedForcedExit")));
            assertTrue(monitor.isForcedExitEnabled());
            assertEquals(exitTime, monitor.getForcedExitTime());
        }

        @Test
        @DisplayName("Strategies should be ordered by priority")
        void strategiesShouldBeOrderedByPriority() {
            monitor = new PositionMonitorV2(
                    EXECUTION_ID, 2.0, 2.0, PositionMonitorV2.PositionDirection.SHORT,
                    true, 1.0, 0.5,   // trailing enabled
                    true, LocalTime.of(15, 10),  // forced exit enabled
                    false, 0, 0, 0, null
            );

            var strategies = monitor.getExitStrategies();

            // Verify order: TimeBasedForcedExit(0) < PointsBasedTarget(100) < TrailingStop(300) < PointsBasedStopLoss(400)
            for (int i = 1; i < strategies.size(); i++) {
                assertTrue(strategies.get(i - 1).getPriority() <= strategies.get(i).getPriority(),
                        "Strategies should be ordered by priority");
            }
        }
    }

    @Nested
    @DisplayName("Backward Compatibility Tests")
    class BackwardCompatibilityTests {

        @Test
        @DisplayName("Simple constructor should work")
        void simpleConstructorShouldWork() {
            monitor = new PositionMonitorV2(EXECUTION_ID, 2.0, 3.0);

            assertEquals(EXECUTION_ID, monitor.getExecutionId());
            assertEquals(2.0, monitor.getStopLossPoints());
            assertEquals(3.0, monitor.getTargetPoints());
            assertEquals(PositionMonitorV2.PositionDirection.LONG, monitor.getDirection());
            assertTrue(monitor.isActive());
        }

        @Test
        @DisplayName("Leg management should work correctly")
        void legManagementShouldWork() {
            monitor = new PositionMonitorV2(EXECUTION_ID, 2.0, 2.0);

            monitor.addLeg("order1", "NIFTY24350CE", 12345L, 100.0, 50, "CE");
            monitor.addLeg("order2", "NIFTY24350PE", 12346L, 80.0, 50, "PE");

            assertEquals(2, monitor.getLegs().size());
            assertEquals(2, monitor.getLegsBySymbol().size());
            assertTrue(monitor.getLegsBySymbol().containsKey("NIFTY24350CE"));
            assertTrue(monitor.getLegsBySymbol().containsKey("NIFTY24350PE"));

            monitor.removeLeg("NIFTY24350CE");
            assertEquals(1, monitor.getLegs().size());
            assertFalse(monitor.getLegsBySymbol().containsKey("NIFTY24350CE"));
        }

        @Test
        @DisplayName("Stop should deactivate monitor")
        void stopShouldDeactivateMonitor() {
            monitor = new PositionMonitorV2(EXECUTION_ID, 2.0, 2.0);
            assertTrue(monitor.isActive());

            monitor.stop();
            assertFalse(monitor.isActive());
        }

        @Test
        @DisplayName("Should handle null ticks gracefully")
        void shouldHandleNullTicks() {
            monitor = new PositionMonitorV2(EXECUTION_ID, 2.0, 2.0);
            monitor.addLeg("order1", "NIFTY24350CE", 12345L, 100.0, 50, "CE");

            // Should not throw
            assertDoesNotThrow(() -> monitor.updatePriceWithDifferenceCheck(null));
            assertDoesNotThrow(() -> monitor.updatePriceWithDifferenceCheck(new ArrayList<>()));

            assertTrue(monitor.isActive());
        }
    }
}

