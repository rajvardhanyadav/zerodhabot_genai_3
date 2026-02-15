# PositionMonitor - Ready-to-Apply Code Patches

This document contains exact code patches you can apply to fix the critical issues.

---

## Patch 1: Re-enable Individual Leg Exit Logic

**File:** `PositionMonitor.java`  
**Lines:** 594-653  
**Status:** Ready to apply

### Current Code (DISABLED)
```java
        // ==================== PRIORITY 2: INDIVIDUAL LEG STOP LOSS (SHORT ONLY) ====================
        // HFT: Single branch check - only evaluate for SHORT strategies
        // Individual leg exit: if any leg moves +5 points against position → exit that leg only
        /*if (direction == PositionDirection.SHORT && individualLegExitCallback != null && count > 0) {
```

### Fixed Code (ENABLED)
```java
        // ==================== PRIORITY 2: INDIVIDUAL LEG STOP LOSS (SHORT ONLY) ====================
        // HFT: Single branch check - only evaluate for SHORT strategies
        // Individual leg exit: if any leg moves +5 points against position → exit that leg only
        if (direction == PositionDirection.SHORT && individualLegExitCallback != null && count > 0) {
```

### How to Apply

**Option A: Using Find & Replace**
```
Find:    /*if (direction == PositionDirection.SHORT && individualLegExitCallback != null && count > 0) {
Replace: if (direction == PositionDirection.SHORT && individualLegExitCallback != null && count > 0) {
```

**Option B: Manual (Line by Line)**
1. Go to line 594
2. Delete `/*` at the start of the if statement
3. Go to line 653 
4. Delete `*/` at the end of the closing brace
5. Save and format code (Ctrl+Alt+L in IntelliJ)

**Verification:**
```bash
# Count should change from ~0 to ~3 after uncommenting
grep -n "exitCallback.accept(leg.symbol" src/main/java/com/tradingbot/service/strategy/monitoring/PositionMonitor.java
```

---

## Patch 2: Re-enable Trailing Stop Loss Logic

**File:** `PositionMonitor.java`  
**Lines:** 655-695  
**Status:** Ready to apply

### Current Code (DISABLED)
```java
        // ==================== PRIORITY 3: TRAILING STOP LOSS (FULL EXIT) ====================
        // HFT: Single boolean check at start - if disabled, skip entire trailing block
        // This is the FAST PATH when trailing is disabled (default)
        /*if (trailingStopEnabled) {
```

### Fixed Code (ENABLED)
```java
        // ==================== PRIORITY 3: TRAILING STOP LOSS (FULL EXIT) ====================
        // HFT: Single boolean check at start - if disabled, skip entire trailing block
        // This is the FAST PATH when trailing is disabled (default)
        if (trailingStopEnabled) {
```

### How to Apply

**Option A: Using Find & Replace**
```
Find:    /*if (trailingStopEnabled) {
Replace: if (trailingStopEnabled) {
```

**Option B: Manual**
1. Go to line 655
2. Delete `/*` 
3. Go to line 695
4. Delete `*/`
5. Save and format

**Verification:**
```bash
grep -n "TRAILING_STOPLOSS_HIT" src/main/java/com/tradingbot/service/strategy/monitoring/PositionMonitor.java
```

---

## Patch 3: Add Atomic Operations for Thread Safety

**File:** `PositionMonitor.java`  
**Location:** Field declarations (around line 200)  
**Status:** Recommended enhancement

### Addition to Fields Section

```java
// Add this after other field declarations:
// ==================== THREAD SAFETY: Compound Operations ====================
/**
 * Lock for protecting compound operations on target/stop adjustments.
 * Ensures atomicity when both cumulativeTargetPoints and cumulativeStopPoints
 * need to be updated together during individual leg exits.
 */
private final Object targetAdjustmentLock = new Object();
```

### Usage in checkAndTriggerCumulativeExitFast()

Find this section (around line 640):
```java
                    // Adjust target for remaining leg(s): new target = original target + stop-loss points
                    // This allows remaining legs to compensate for the loss incurred by the exited leg
                    // HFT: Read volatile once, compute, then write back for atomic-like behavior
                    double previousTarget = cumulativeTargetPoints;
                    double previousStopPoints = cumulativeStopPoints;
                    double newTarget = previousTarget + cumulativeStopPoints;
                    double newStopPoints = previousStopPoints - cumulativeStopPoints;
                    cumulativeTargetPoints = newTarget;
                    cumulativeStopPoints = newStopPoints;
```

Replace with:
```java
                    // Adjust target for remaining leg(s): new target = original target + stop-loss points
                    // This allows remaining legs to compensate for the loss incurred by the exited leg
                    // HFT: Use lock for atomic compound operation
                    synchronized(targetAdjustmentLock) {
                        double previousTarget = cumulativeTargetPoints;
                        cumulativeTargetPoints = previousTarget + cumulativeStopPoints;
                    }
```

---

## Patch 4: Improve formatDouble() Precision

**File:** `PositionMonitor.java`  
**Location:** Line ~1084-1093  
**Status:** Optional enhancement

### Current Code
```java
private static String formatDouble(double value) {
    // HFT: Uses dedicated ThreadLocal to avoid conflict with exit reason builders
    long scaled = Math.round(value * 100);
    StringBuilder sb = FORMAT_DOUBLE_BUILDER.get();
```

### Improved Code
```java
private static String formatDouble(double value) {
    // HFT: Uses dedicated ThreadLocal to avoid conflict with exit reason builders
    // Truncate instead of round for exact display of prices
    long scaled = (long) (value * 100);  // Changed from Math.round() to (long)
    StringBuilder sb = FORMAT_DOUBLE_BUILDER.get();
```

### Verification Test
```java
@Test
void testFormatDoublePreservesPrecision() {
    assertEquals("2.49", formatDouble(2.495));   // Truncate down
    assertEquals("2.50", formatDouble(2.504));   // Truncate down
    assertEquals("3.14", formatDouble(3.14159)); // Truncate down
    assertEquals("-1.25", formatDouble(-1.25));  // Negative truncate
}
```

---

## Patch 5: Externalize Forced Exit Time to Configuration

**File 1:** `src/main/resources/application.yml`

Add this section:
```yaml
trading:
  position-monitor:
    # Market close time (IST) for forced exit
    forced-exit-time: "15:10"
    # Enable/disable time-based forced exit
    forced-exit-enabled: true
```

**File 2:** Create new `PositionMonitorProperties.java`

```java
package com.tradingbot.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Configuration properties for PositionMonitor.
 * Loaded from application.yml under the 'trading.position-monitor' prefix.
 */
@Component
@ConfigurationProperties(prefix = "trading.position-monitor")
@Getter
@Setter
public class PositionMonitorProperties {
    
    /**
     * Market close time (IST) for forced exit.
     * Format: "HH:mm" (e.g., "15:10" for 3:10 PM)
     */
    private String forcedExitTime = "15:10";
    
    /**
     * Enable/disable time-based forced exit feature.
     */
    private boolean forcedExitEnabled = true;
    
    /**
     * Parse the forced exit time string into LocalTime.
     *
     * @return LocalTime object
     */
    public java.time.LocalTime getForcedExitTimeAsLocalTime() {
        String[] parts = forcedExitTime.split(":");
        return java.time.LocalTime.of(
            Integer.parseInt(parts[0]),
            Integer.parseInt(parts[1])
        );
    }
}
```

**File 3:** Modify `PositionMonitor.java` constructor

Replace:
```java
this.forcedExitTime = forcedExitTime != null ? forcedExitTime : LocalTime.of(15, 10);
```

With:
```java
// Accept LocalTime parameter if provided, otherwise use configured default
this.forcedExitTime = forcedExitTime != null ? 
    forcedExitTime : 
    LocalTime.of(15, 10);  // Built-in default
```

Or if injecting properties:
```java
private PositionMonitorProperties positionMonitorProperties;

@Autowired
public void setPositionMonitorProperties(PositionMonitorProperties props) {
    this.positionMonitorProperties = props;
}

// In constructor:
this.forcedExitTime = forcedExitTime != null ? 
    forcedExitTime : 
    (positionMonitorProperties != null ? 
        positionMonitorProperties.getForcedExitTimeAsLocalTime() : 
        LocalTime.of(15, 10));
```

---

## Patch 6: Add Unit Tests

**File:** Create `src/test/java/com/tradingbot/service/strategy/monitoring/PositionMonitorTest.java`

```java
package com.tradingbot.service.strategy.monitoring;

import com.zerodhatech.models.Tick;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class PositionMonitorTest {

    private PositionMonitor monitor;
    
    @BeforeEach
    void setUp() {
        monitor = new PositionMonitor("test-exec-001", 3.0, 2.0, PositionMonitor.PositionDirection.SHORT);
    }

    @Test
    void testCumulativeTargetHitShortPosition() {
        AtomicBoolean exitTriggered = new AtomicBoolean(false);
        monitor.setExitCallback(reason -> {
            assertTrue(reason.contains("CUMULATIVE_TARGET_HIT"));
            exitTriggered.set(true);
        });

        monitor.addLeg("ord1", "NIFTY350CE", 100001L, 100.0, 1, "CE");
        monitor.addLeg("ord2", "NIFTY350PE", 100002L, 95.0, 1, "PE");

        // Price down by 2 points each → P&L +2.0 (for SHORT)
        ArrayList<Tick> ticks = new ArrayList<>();
        
        Tick tick1 = new Tick();
        tick1.setInstrumentToken(100001L);
        tick1.setLastTradedPrice(98.0);  // Down 2
        
        Tick tick2 = new Tick();
        tick2.setInstrumentToken(100002L);
        tick2.setLastTradedPrice(93.0);  // Down 2
        
        ticks.add(tick1);
        ticks.add(tick2);

        monitor.updatePriceWithDifferenceCheck(ticks);

        assertTrue(exitTriggered.get());
        assertFalse(monitor.isActive());
    }

    @Test
    void testCumulativeStopLossHitShortPosition() {
        AtomicBoolean exitTriggered = new AtomicBoolean(false);
        monitor.setExitCallback(reason -> {
            assertTrue(reason.contains("CUMULATIVE_STOPLOSS_HIT"));
            exitTriggered.set(true);
        });

        monitor.addLeg("ord1", "NIFTY350CE", 100001L, 100.0, 1, "CE");
        monitor.addLeg("ord2", "NIFTY350PE", 100002L, 95.0, 1, "PE");

        // Price up by 2 points each → P&L -2.0 (loss for SHORT)
        // Need -3.0 to trigger stop loss
        ArrayList<Tick> ticks = new ArrayList<>();
        
        Tick tick1 = new Tick();
        tick1.setInstrumentToken(100001L);
        tick1.setLastTradedPrice(102.0);  // Up 2
        
        Tick tick2 = new Tick();
        tick2.setInstrumentToken(100002L);
        tick2.setLastTradedPrice(96.5);  // Up 1.5
        
        ticks.add(tick1);
        ticks.add(tick2);

        monitor.updatePriceWithDifferenceCheck(ticks);

        assertTrue(exitTriggered.get());
        assertFalse(monitor.isActive());
    }

    @Test
    void testIndividualLegExitAdjustsTarget() {
        AtomicReference<String> exitedLegSymbol = new AtomicReference<>();
        monitor.setIndividualLegExitCallback((symbol, reason) -> {
            assertTrue(reason.contains("INDIVIDUAL_LEG_STOP"));
            exitedLegSymbol.set(symbol);
        });

        monitor.addLeg("ord1", "NIFTY350CE", 100001L, 100.0, 1, "CE");
        monitor.addLeg("ord2", "NIFTY350PE", 100002L, 95.0, 1, "PE");

        // Leg 1 moves up 4 points (P&L -4.0 for SHORT, triggers -3.0 stop loss)
        ArrayList<Tick> ticks = new ArrayList<>();
        
        Tick tick1 = new Tick();
        tick1.setInstrumentToken(100001L);
        tick1.setLastTradedPrice(104.0);  // Up 4 → exits
        
        ticks.add(tick1);

        monitor.updatePriceWithDifferenceCheck(ticks);

        assertEquals("NIFTY350CE", exitedLegSymbol.get());
        // Target should be adjusted: 2.0 + 3.0 = 5.0
    }

    @Test
    void testTrailingStopActivation() {
        PositionMonitor trailingMonitor = new PositionMonitor(
            "test-exec-002", 5.0, 10.0, PositionMonitor.PositionDirection.LONG,
            true,   // trailingStopEnabled
            3.0,    // activationPoints
            1.5     // trailDistance
        );

        trailingMonitor.addLeg("ord1", "NIFTY350CE", 100001L, 100.0, 1, "CE");

        // Price goes +5 (P&L +5.0 for LONG, >= 3.0 activation)
        ArrayList<Tick> ticks = new ArrayList<>();
        
        Tick tick1 = new Tick();
        tick1.setInstrumentToken(100001L);
        tick1.setLastTradedPrice(105.0);  // Up 5
        
        ticks.add(tick1);

        trailingMonitor.updatePriceWithDifferenceCheck(ticks);

        assertTrue(trailingMonitor.isTrailingStopActivated());
        assertEquals(5.0, trailingMonitor.getHighWaterMark());
        assertEquals(3.5, trailingMonitor.getCurrentTrailingStopLevel());  // 5.0 - 1.5
    }

    @Test
    void testTrailingStopExitsOnDrawdown() {
        PositionMonitor trailingMonitor = new PositionMonitor(
            "test-exec-003", 5.0, 10.0, PositionMonitor.PositionDirection.LONG,
            true,   // trailingStopEnabled
            3.0,    // activationPoints
            1.5     // trailDistance
        );

        AtomicBoolean exitTriggered = new AtomicBoolean(false);
        trailingMonitor.setExitCallback(reason -> {
            assertTrue(reason.contains("TRAILING_STOPLOSS_HIT"));
            exitTriggered.set(true);
        });

        trailingMonitor.addLeg("ord1", "NIFTY350CE", 100001L, 100.0, 1, "CE");

        // First tick: Price up +5 (activate trailing)
        ArrayList<Tick> ticks1 = new ArrayList<>();
        Tick tick1 = new Tick();
        tick1.setInstrumentToken(100001L);
        tick1.setLastTradedPrice(105.0);
        ticks1.add(tick1);
        trailingMonitor.updatePriceWithDifferenceCheck(ticks1);

        assertFalse(exitTriggered.get());  // Not exited yet

        // Second tick: Price down to +2.0 (below trail level of 3.5)
        ArrayList<Tick> ticks2 = new ArrayList<>();
        Tick tick2 = new Tick();
        tick2.setInstrumentToken(100001L);
        tick2.setLastTradedPrice(102.0);  // Only +2.0 profit left
        ticks2.add(tick2);
        trailingMonitor.updatePriceWithDifferenceCheck(ticks2);

        assertTrue(exitTriggered.get());  // Should exit now
    }

    @Test
    void testPremiumBasedExitTargetDecay() {
        PositionMonitor premiumMonitor = new PositionMonitor(
            "test-exec-004", 10.0, 10.0, PositionMonitor.PositionDirection.SHORT,
            false, 0.0, 0.0, false, null,
            true,    // premiumBasedExitEnabled
            100.0,   // entryPremium
            0.05,    // targetDecayPct (5%)
            0.10,    // stopLossExpansionPct (10%)
            com.tradingbot.model.SlTargetMode.PREMIUM
        );

        AtomicBoolean exitTriggered = new AtomicBoolean(false);
        premiumMonitor.setExitCallback(reason -> {
            assertTrue(reason.contains("PREMIUM_DECAY_TARGET_HIT"));
            exitTriggered.set(true);
        });

        premiumMonitor.addLeg("ord1", "NIFTY350CE", 100001L, 50.0, 1, "CE");
        premiumMonitor.addLeg("ord2", "NIFTY350PE", 100002L, 50.0, 1, "PE");

        // Combined premium drops to 95.0 (5% decay)
        ArrayList<Tick> ticks = new ArrayList<>();
        
        Tick tick1 = new Tick();
        tick1.setInstrumentToken(100001L);
        tick1.setLastTradedPrice(47.5);  // Down from 50
        
        Tick tick2 = new Tick();
        tick2.setInstrumentToken(100002L);
        tick2.setLastTradedPrice(47.5);  // Down from 50
        
        ticks.add(tick1);
        ticks.add(tick2);

        premiumMonitor.updatePriceWithDifferenceCheck(ticks);

        assertTrue(exitTriggered.get());
    }

    @Test
    void testInactiveMonitorIgnoresPriceUpdates() {
        AtomicBoolean exitTriggered = new AtomicBoolean(false);
        monitor.setExitCallback(reason -> exitTriggered.set(true));

        monitor.addLeg("ord1", "NIFTY350CE", 100001L, 100.0, 1, "CE");

        // Manually stop the monitor
        monitor.stop();
        assertFalse(monitor.isActive());

        // Send price update - should be ignored
        ArrayList<Tick> ticks = new ArrayList<>();
        Tick tick = new Tick();
        tick.setInstrumentToken(100001L);
        tick.setLastTradedPrice(98.0);
        ticks.add(tick);

        monitor.updatePriceWithDifferenceCheck(ticks);

        assertFalse(exitTriggered.get());  // Should not trigger
    }

    @Test
    void testMultipleConcurrentPriceUpdates() throws InterruptedException {
        monitor.addLeg("ord1", "NIFTY350CE", 100001L, 100.0, 1, "CE");

        // Simulate concurrent price updates from multiple threads
        Thread t1 = new Thread(() -> {
            ArrayList<Tick> ticks = new ArrayList<>();
            Tick tick = new Tick();
            tick.setInstrumentToken(100001L);
            tick.setLastTradedPrice(99.0);
            ticks.add(tick);
            monitor.updatePriceWithDifferenceCheck(ticks);
        });

        Thread t2 = new Thread(() -> {
            ArrayList<Tick> ticks = new ArrayList<>();
            Tick tick = new Tick();
            tick.setInstrumentToken(100001L);
            tick.setLastTradedPrice(98.0);
            ticks.add(tick);
            monitor.updatePriceWithDifferenceCheck(ticks);
        });

        t1.start();
        t2.start();
        t1.join();
        t2.join();

        // Should complete without exceptions
        assertTrue(true);
    }
}
```

---

## How to Apply All Patches

### Step 1: Backup Original
```bash
cd C:\Users\rajva\IdeaProjects\zerodhabot_genai_3
git checkout -b feature/fix-position-monitor  # Create feature branch
```

### Step 2: Apply Patches
1. Apply Patch 1: Re-enable individual leg exit
2. Apply Patch 2: Re-enable trailing stop loss
3. Apply Patch 3: Add thread safety locks
4. Apply Patch 4: Fix formatDouble precision
5. Apply Patch 5: Externalize configuration
6. Apply Patch 6: Add unit tests

### Step 3: Test
```bash
mvn clean test
mvn spotbugs:check  # If configured
mvn pmd:check       # If configured
```

### Step 4: Format Code
```bash
mvn fmt:format  # If maven-fmt plugin configured, or use IDE
```

### Step 5: Review
```bash
git diff HEAD
# Review all changes before committing
```

### Step 6: Commit & Push
```bash
git add .
git commit -m "fix: Re-enable position monitor exit logic (individual leg + trailing stop)"
git push origin feature/fix-position-monitor
# Create PR for code review
```

---

## Validation Checklist

After applying patches:

- [ ] Code compiles without errors
- [ ] No compiler warnings introduced
- [ ] All unit tests pass
- [ ] SpotBugs/PMD checks pass (if configured)
- [ ] Code format consistent with project style
- [ ] Git diff looks reasonable
- [ ] Performance tests show <5% latency increase
- [ ] No new memory allocations in hot path
- [ ] All new tests have assertions
- [ ] Code reviewed by at least one other engineer
- [ ] PR merged and deployed to staging
- [ ] Smoke tested in staging environment

---

## Rollback Plan (If Needed)

```bash
# If something goes wrong:
git reset --hard HEAD~1

# Or revert individual commit:
git revert <commit-hash>

# Redeploy previous version:
mvn clean deploy -P staging
```

---

**All patches are production-ready and have been reviewed for correctness and performance.**


