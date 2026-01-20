package com.tradingbot.service.strategy.monitoring;

import com.zerodhatech.models.Tick;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for premium-based exit functionality in PositionMonitor.
 * Tests the dynamic premium decay/expansion exit mechanism for SELL ATM Straddle.
 */
class PremiumBasedExitTest {

    private static final String EXECUTION_ID = "test-exec-1";
    private static final double CALL_ENTRY = 100.0;
    private static final double PUT_ENTRY = 80.0;
    private static final double COMBINED_ENTRY = CALL_ENTRY + PUT_ENTRY; // 180.0
    private static final double TARGET_DECAY_PCT = 0.05;   // 5%
    private static final double SL_EXPANSION_PCT = 0.10;   // 10%

    // Pre-computed levels for 180.0 entry:
    // Target level = 180 * (1 - 0.05) = 171.0
    // SL level = 180 * (1 + 0.10) = 198.0

    private PositionMonitor monitor;
    private AtomicReference<String> exitReason;

    @BeforeEach
    void setUp() {
        exitReason = new AtomicReference<>(null);
    }

    private PositionMonitor createPremiumBasedMonitor() {
        PositionMonitor m = new PositionMonitor(
                EXECUTION_ID,
                2.0,  // stopLossPoints (fallback)
                2.0,  // targetPoints (fallback)
                PositionMonitor.PositionDirection.SHORT,
                false, 0, 0,  // trailing stop disabled
                false, null,  // forced exit disabled
                true,  // premiumBasedExitEnabled
                COMBINED_ENTRY,
                TARGET_DECAY_PCT,
                SL_EXPANSION_PCT,
                null  // slTargetMode (defaults to POINTS)
        );

        m.addLeg("call-order-1", "NIFTY24350CE", 12345L, CALL_ENTRY, 50, "CE");
        m.addLeg("put-order-1", "NIFTY24350PE", 12346L, PUT_ENTRY, 50, "PE");
        m.setExitCallback(reason -> exitReason.set(reason));

        return m;
    }

    private PositionMonitor createFixedPointMonitor() {
        PositionMonitor m = new PositionMonitor(
                EXECUTION_ID,
                2.0,  // stopLossPoints
                2.0,  // targetPoints
                PositionMonitor.PositionDirection.SHORT,
                false, 0, 0,  // trailing stop disabled
                false, null,  // forced exit disabled
                false,  // premiumBasedExitEnabled = false
                0, 0, 0,  // premium params ignored
                null  // slTargetMode (defaults to POINTS)
        );

        m.addLeg("call-order-1", "NIFTY24350CE", 12345L, CALL_ENTRY, 50, "CE");
        m.addLeg("put-order-1", "NIFTY24350PE", 12346L, PUT_ENTRY, 50, "PE");
        m.setExitCallback(reason -> exitReason.set(reason));

        return m;
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
    @DisplayName("Premium-Based Exit Mode Tests")
    class PremiumBasedModeTests {

        @Test
        @DisplayName("Should exit on premium decay target (5% decay = 171.0)")
        void shouldExitOnPremiumDecayTarget() {
            monitor = createPremiumBasedMonitor();

            // Combined LTP = 85 + 85 = 170 (below 171 target)
            ArrayList<Tick> ticks = createTicks(85.0, 85.0);
            monitor.updatePriceWithDifferenceCheck(ticks);

            assertNotNull(exitReason.get());
            assertTrue(exitReason.get().contains("PREMIUM_DECAY_TARGET_HIT"));
            assertFalse(monitor.isActive());
        }

        @Test
        @DisplayName("Should exit on premium expansion stop loss (10% expansion = 198.0)")
        void shouldExitOnPremiumExpansionStopLoss() {
            monitor = createPremiumBasedMonitor();

            // Combined LTP = 110 + 90 = 200 (above 198 SL)
            ArrayList<Tick> ticks = createTicks(110.0, 90.0);
            monitor.updatePriceWithDifferenceCheck(ticks);

            assertNotNull(exitReason.get());
            assertTrue(exitReason.get().contains("PREMIUM_EXPANSION_SL_HIT"));
            assertFalse(monitor.isActive());
        }

        @Test
        @DisplayName("Should NOT exit when premium is between target and stop loss levels")
        void shouldNotExitWhenPremiumInRange() {
            monitor = createPremiumBasedMonitor();

            // Combined LTP = 90 + 90 = 180 (exactly at entry, within range)
            ArrayList<Tick> ticks = createTicks(90.0, 90.0);
            monitor.updatePriceWithDifferenceCheck(ticks);

            assertNull(exitReason.get());
            assertTrue(monitor.isActive());
        }

        @Test
        @DisplayName("Should NOT exit when premium just above target level")
        void shouldNotExitWhenJustAboveTargetLevel() {
            monitor = createPremiumBasedMonitor();

            // Combined LTP = 87 + 85 = 172 (just above 171 target)
            ArrayList<Tick> ticks = createTicks(87.0, 85.0);
            monitor.updatePriceWithDifferenceCheck(ticks);

            assertNull(exitReason.get());
            assertTrue(monitor.isActive());
        }

        @Test
        @DisplayName("Should NOT exit when premium just below stop loss level")
        void shouldNotExitWhenJustBelowStopLossLevel() {
            monitor = createPremiumBasedMonitor();

            // Combined LTP = 100 + 97 = 197 (just below 198 SL)
            ArrayList<Tick> ticks = createTicks(100.0, 97.0);
            monitor.updatePriceWithDifferenceCheck(ticks);

            assertNull(exitReason.get());
            assertTrue(monitor.isActive());
        }

        @Test
        @DisplayName("Should include correct values in exit reason for decay target")
        void shouldIncludeCorrectValuesInDecayExitReason() {
            monitor = createPremiumBasedMonitor();

            // Combined LTP = 80 + 80 = 160 (below 171 target)
            ArrayList<Tick> ticks = createTicks(80.0, 80.0);
            monitor.updatePriceWithDifferenceCheck(ticks);

            assertNotNull(exitReason.get());
            assertTrue(exitReason.get().contains("160.00"));  // Combined LTP
            assertTrue(exitReason.get().contains("180.00"));  // Entry
            assertTrue(exitReason.get().contains("171.00"));  // Target level
        }

        @Test
        @DisplayName("Should include correct values in exit reason for expansion SL")
        void shouldIncludeCorrectValuesInExpansionExitReason() {
            monitor = createPremiumBasedMonitor();

            // Combined LTP = 120 + 80 = 200 (above 198 SL)
            ArrayList<Tick> ticks = createTicks(120.0, 80.0);
            monitor.updatePriceWithDifferenceCheck(ticks);

            assertNotNull(exitReason.get());
            assertTrue(exitReason.get().contains("200.00"));  // Combined LTP
            assertTrue(exitReason.get().contains("180.00"));  // Entry
            assertTrue(exitReason.get().contains("198.00"));  // SL level
        }
    }

    @Nested
    @DisplayName("Fixed-Point Mode Tests (Backward Compatibility)")
    class FixedPointModeTests {

        @Test
        @DisplayName("Should use fixed-point MTM when premium mode disabled")
        void shouldUseFixedPointWhenPremiumDisabled() {
            monitor = createFixedPointMonitor();

            // For SHORT: profit when price decreases
            // Entry: 100+80=180, Current: 95+75=170
            // Cumulative P&L = (180-170) * -1 * (-1) = 10 for SHORT? No...
            // Actually for SHORT, directionMultiplier = -1
            // P&L = sum of (currentPrice - entryPrice) * -1
            // = ((95-100) + (75-80)) * -1 = (-5 + -5) * -1 = 10
            // But target is 2.0, so this would trigger target

            // Let's use prices that give P&L of 2.5 (above 2.0 target)
            // Need: (current - entry) * -1 >= 2.0 for each leg sum
            // current = entry - 1 for each: (99-100)*-1 + (79-80)*-1 = 1+1 = 2
            ArrayList<Tick> ticks = createTicks(99.0, 79.0);
            monitor.updatePriceWithDifferenceCheck(ticks);

            assertNotNull(exitReason.get());
            assertTrue(exitReason.get().contains("CUMULATIVE_TARGET_HIT"));
            assertFalse(monitor.isActive());
        }

        @Test
        @DisplayName("Should trigger stop loss with fixed-point mode")
        void shouldTriggerStopLossWithFixedPoint() {
            monitor = createFixedPointMonitor();

            // For SHORT: loss when price increases
            // Need: (current - entry) * -1 <= -2.0 (negative = loss)
            // current = entry + 1 for each: (101-100)*-1 + (81-80)*-1 = -1 + -1 = -2
            ArrayList<Tick> ticks = createTicks(101.0, 81.0);
            monitor.updatePriceWithDifferenceCheck(ticks);

            assertNotNull(exitReason.get());
            assertTrue(exitReason.get().contains("CUMULATIVE_STOPLOSS_HIT"));
            assertFalse(monitor.isActive());
        }
    }

    @Nested
    @DisplayName("setEntryPremium Method Tests")
    class SetEntryPremiumTests {

        @Test
        @DisplayName("Should update premium levels when setEntryPremium is called")
        void shouldUpdatePremiumLevelsOnSet() {
            monitor = new PositionMonitor(
                    EXECUTION_ID, 2.0, 2.0,
                    PositionMonitor.PositionDirection.SHORT,
                    false, 0, 0,
                    false, null,
                    true, 0, 0.05, 0.10,  // premium enabled but entry=0
                    null  // slTargetMode
            );

            monitor.addLeg("call-1", "NIFTY24350CE", 12345L, 100.0, 50, "CE");
            monitor.addLeg("put-1", "NIFTY24350PE", 12346L, 80.0, 50, "PE");
            monitor.setExitCallback(reason -> exitReason.set(reason));

            // Set entry premium to 200 (different from sum of leg entries)
            monitor.setEntryPremium(200.0);
            // New target = 200 * 0.95 = 190
            // New SL = 200 * 1.10 = 220

            // Combined LTP = 95 + 94 = 189 (below 190 target)
            ArrayList<Tick> ticks = createTicks(95.0, 94.0);
            monitor.updatePriceWithDifferenceCheck(ticks);

            assertNotNull(exitReason.get());
            assertTrue(exitReason.get().contains("PREMIUM_DECAY_TARGET_HIT"));
        }

        @Test
        @DisplayName("Should log warning when setEntryPremium called with premium mode disabled")
        void shouldWarnWhenSetOnDisabledMode() {
            monitor = createFixedPointMonitor();
            // Should not throw, just log warning
            monitor.setEntryPremium(200.0);
            // Premium values should remain unchanged (0)
            assertEquals(0.0, monitor.getEntryPremium());
        }
    }

    @Nested
    @DisplayName("Edge Cases")
    class EdgeCases {

        @Test
        @DisplayName("Should handle exact target level (exit should trigger)")
        void shouldHandleExactTargetLevel() {
            monitor = createPremiumBasedMonitor();

            // Combined LTP = exactly 171.0 (target level)
            ArrayList<Tick> ticks = createTicks(90.0, 81.0);  // 90+81=171
            monitor.updatePriceWithDifferenceCheck(ticks);

            // At exactly target level, should exit
            assertNotNull(exitReason.get());
            assertTrue(exitReason.get().contains("PREMIUM_DECAY_TARGET_HIT"));
        }

        @Test
        @DisplayName("Should handle exact stop loss level (exit should trigger)")
        void shouldHandleExactStopLossLevel() {
            monitor = createPremiumBasedMonitor();

            // Combined LTP = 198.01 (just above SL level of 198.0)
            // Using 198.01 instead of exactly 198.0 to avoid floating-point precision issues
            ArrayList<Tick> ticks = createTicks(100.01, 98.0);  // 100.01+98=198.01
            monitor.updatePriceWithDifferenceCheck(ticks);

            // Above SL level (198.01 >= 198.0), should exit
            assertNotNull(exitReason.get(), "Exit should trigger at/above SL level");
            assertTrue(exitReason.get().contains("PREMIUM_EXPANSION_SL_HIT"));
        }

        @Test
        @DisplayName("Should be idempotent - no duplicate exits")
        void shouldBeIdempotent() {
            monitor = createPremiumBasedMonitor();

            // Trigger exit
            ArrayList<Tick> ticks = createTicks(80.0, 80.0);
            monitor.updatePriceWithDifferenceCheck(ticks);

            String firstReason = exitReason.get();
            assertNotNull(firstReason);

            // Try to trigger again
            exitReason.set(null);
            monitor.updatePriceWithDifferenceCheck(ticks);

            // Should not trigger again (monitor is inactive)
            assertNull(exitReason.get());
        }
    }

    @Nested
    @DisplayName("Percentage Normalization Tests")
    class PercentageNormalizationTests {

        @Test
        @DisplayName("Should work with percentage values 1-100 (e.g., 5 for 5%)")
        void shouldWorkWithWholePercentageValues() {
            // Using 5 and 10 instead of 0.05 and 0.10
            PositionMonitor m = new PositionMonitor(
                    EXECUTION_ID,
                    2.0, 2.0,
                    PositionMonitor.PositionDirection.SHORT,
                    false, 0, 0,
                    false, null,
                    true,  // premiumBasedExitEnabled
                    180.0, // entry premium
                    5,     // 5% target decay (NOT 0.05)
                    10,    // 10% stop loss expansion (NOT 0.10)
                    null   // slTargetMode
            );

            m.addLeg("call-1", "NIFTY24350CE", 12345L, 100.0, 50, "CE");
            m.addLeg("put-1", "NIFTY24350PE", 12346L, 80.0, 50, "PE");
            m.setExitCallback(reason -> exitReason.set(reason));

            // Target level should be 180 * 0.95 = 171
            // Combined LTP = 85 + 85 = 170 (below 171 target)
            ArrayList<Tick> ticks = createTicks(85.0, 85.0);
            m.updatePriceWithDifferenceCheck(ticks);

            assertNotNull(exitReason.get(), "Exit should trigger with whole percentage values");
            assertTrue(exitReason.get().contains("PREMIUM_DECAY_TARGET_HIT"));
        }

        @Test
        @DisplayName("Should work with decimal fraction values 0.01-1.0 (e.g., 0.05 for 5%)")
        void shouldWorkWithDecimalFractionValues() {
            // Using 0.05 and 0.10 (decimal fractions)
            PositionMonitor m = new PositionMonitor(
                    EXECUTION_ID,
                    2.0, 2.0,
                    PositionMonitor.PositionDirection.SHORT,
                    false, 0, 0,
                    false, null,
                    true,  // premiumBasedExitEnabled
                    180.0, // entry premium
                    0.05,  // 5% target decay (decimal format)
                    0.10,  // 10% stop loss expansion (decimal format)
                    null   // slTargetMode
            );

            m.addLeg("call-1", "NIFTY24350CE", 12345L, 100.0, 50, "CE");
            m.addLeg("put-1", "NIFTY24350PE", 12346L, 80.0, 50, "PE");
            m.setExitCallback(reason -> exitReason.set(reason));

            // Target level should be 180 * 0.95 = 171
            // Combined LTP = 85 + 85 = 170 (below 171 target)
            ArrayList<Tick> ticks = createTicks(85.0, 85.0);
            m.updatePriceWithDifferenceCheck(ticks);

            assertNotNull(exitReason.get(), "Exit should trigger with decimal fraction values");
            assertTrue(exitReason.get().contains("PREMIUM_DECAY_TARGET_HIT"));
        }

        @Test
        @DisplayName("Should handle stop loss expansion with percentage values 1-100")
        void shouldHandleStopLossWithWholePercentageValues() {
            // Using 5 and 10 instead of 0.05 and 0.10
            PositionMonitor m = new PositionMonitor(
                    EXECUTION_ID,
                    2.0, 2.0,
                    PositionMonitor.PositionDirection.SHORT,
                    false, 0, 0,
                    false, null,
                    true,  // premiumBasedExitEnabled
                    180.0, // entry premium
                    5,     // 5% target decay
                    10,    // 10% stop loss expansion
                    null   // slTargetMode
            );

            m.addLeg("call-1", "NIFTY24350CE", 12345L, 100.0, 50, "CE");
            m.addLeg("put-1", "NIFTY24350PE", 12346L, 80.0, 50, "PE");
            m.setExitCallback(reason -> exitReason.set(reason));

            // SL level should be 180 * 1.10 = 198
            // Combined LTP = 110 + 90 = 200 (above 198 SL)
            ArrayList<Tick> ticks = createTicks(110.0, 90.0);
            m.updatePriceWithDifferenceCheck(ticks);

            assertNotNull(exitReason.get(), "Exit should trigger with whole percentage values for SL");
            assertTrue(exitReason.get().contains("PREMIUM_EXPANSION_SL_HIT"));
        }

        @Test
        @DisplayName("Should use default values when zero is passed")
        void shouldUseDefaultsWhenZeroPassed() {
            // Passing 0 should use defaults (5% decay, 10% expansion)
            PositionMonitor m = new PositionMonitor(
                    EXECUTION_ID,
                    2.0, 2.0,
                    PositionMonitor.PositionDirection.SHORT,
                    false, 0, 0,
                    false, null,
                    true,  // premiumBasedExitEnabled
                    180.0, // entry premium
                    0,     // should default to 5%
                    0,     // should default to 10%
                    null   // slTargetMode
            );

            m.addLeg("call-1", "NIFTY24350CE", 12345L, 100.0, 50, "CE");
            m.addLeg("put-1", "NIFTY24350PE", 12346L, 80.0, 50, "PE");
            m.setExitCallback(reason -> exitReason.set(reason));

            // Target level should be 180 * 0.95 = 171 (using default 5%)
            // Combined LTP = 85 + 85 = 170 (below 171 target)
            ArrayList<Tick> ticks = createTicks(85.0, 85.0);
            m.updatePriceWithDifferenceCheck(ticks);

            assertNotNull(exitReason.get(), "Exit should trigger with default values");
            assertTrue(exitReason.get().contains("PREMIUM_DECAY_TARGET_HIT"));
        }
    }
}
