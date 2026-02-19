package com.tradingbot.service.strategy.monitoring.exit;

import lombok.extern.slf4j.Slf4j;

import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;

/**
 * HFT-optimized time-based forced exit strategy.
 * <p>
 * Forces exit of all positions at or after a specified cutoff time (IST).
 * This is the highest priority exit condition - bypasses all P&L checks.
 *
 * <h2>Exit Logic</h2>
 * <ul>
 *   <li>Checks current IST time against configured cutoff (default 15:10)</li>
 *   <li>Triggers EXIT_ALL when current time >= cutoff time</li>
 *   <li>Idempotent - tracks triggered state to prevent duplicate exits</li>
 * </ul>
 *
 * <h2>HFT Optimizations</h2>
 * <ul>
 *   <li>Static timezone for fast time conversion</li>
 *   <li>Pre-built exit reason prefix</li>
 *   <li>Early return if not enabled or already triggered</li>
 * </ul>
 *
 * @see ExitStrategy
 */
@Slf4j
public class TimeBasedForcedExitStrategy extends AbstractExitStrategy {

    /** Priority: 0 (highest - evaluated first) */
    private static final int PRIORITY = 0;

    /** IST timezone for market hours comparison */
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    /** Pre-built exit reason prefix */
    private static final String EXIT_PREFIX = "TIME_BASED_FORCED_EXIT @ ";

    /** Forced exit cutoff time (IST) */
    private final LocalTime forcedExitTime;

    /** Tracks if exit has already been triggered */
    private volatile boolean triggered = false;

    /** HFT: Cached result of time check to avoid ZonedDateTime.now() allocation per tick */
    private volatile boolean cachedTimeCheckResult = false;

    /** HFT: Nanotime of last time check — re-check at most once per second */
    private volatile long lastTimeCheckNanos = 0;

    /** HFT: Re-check interval in nanoseconds (1 second) */
    private static final long TIME_CHECK_INTERVAL_NANOS = 1_000_000_000L;

    /**
     * Creates a time-based forced exit strategy.
     *
     * @param forcedExitTime cutoff time in IST (null defaults to 15:10)
     */
    public TimeBasedForcedExitStrategy(LocalTime forcedExitTime) {
        this.forcedExitTime = forcedExitTime != null ? forcedExitTime : LocalTime.of(15, 10);
    }

    @Override
    public int getPriority() {
        return PRIORITY;
    }

    @Override
    public String getName() {
        return "TimeBasedForcedExit";
    }

    @Override
    public ExitResult evaluate(ExitContext ctx) {
        // HFT: Fast path - already triggered, skip all checks
        if (triggered) {
            return ExitResult.noExit();
        }

        // Check if current time >= forced exit time
        if (isAfterForcedExitTime()) {
            triggered = true;  // Prevent duplicate triggers
            log.warn("TIME_BASED_FORCED_EXIT for execution {}: current time >= {} IST, P&L={} points - Closing ALL legs",
                    ctx.getExecutionId(), forcedExitTime, formatDouble(ctx.getCumulativePnL()));
            return ExitResult.exitAll(buildExitReason());
        }

        return ExitResult.noExit();
    }

    /**
     * Check if current time (IST) is at or after the forced exit cutoff time.
     * <p>
     * HFT: Caches result for 1 second to avoid ZonedDateTime.now() allocation per tick.
     * Time-based exit has second-level granularity so this is safe.
     *
     * @return true if current time >= forced exit time
     */
    private boolean isAfterForcedExitTime() {
        // HFT: Return cached result if checked within the last second
        final long now = System.nanoTime();
        if (now - lastTimeCheckNanos < TIME_CHECK_INTERVAL_NANOS) {
            return cachedTimeCheckResult;
        }
        // Re-check actual time
        lastTimeCheckNanos = now;
        LocalTime currentTime = ZonedDateTime.now(IST).toLocalTime();
        cachedTimeCheckResult = !currentTime.isBefore(forcedExitTime);
        return cachedTimeCheckResult;
    }

    /**
     * Build exit reason string with cutoff time.
     *
     * @return formatted exit reason
     */
    private String buildExitReason() {
        StringBuilder sb = getExitReasonBuilder();
        sb.append(EXIT_PREFIX);
        // Format time as HH:mm
        int hour = forcedExitTime.getHour();
        int minute = forcedExitTime.getMinute();
        if (hour < 10) sb.append('0');
        sb.append(hour);
        sb.append(':');
        if (minute < 10) sb.append('0');
        sb.append(minute);
        return sb.toString();
    }

    /**
     * Check if forced exit time has been reached (for external callers/backtest).
     *
     * @param simulatedTime the simulated current time to check against
     * @return true if simulatedTime >= forced exit time
     */
    public boolean shouldForcedExit(LocalTime simulatedTime) {
        if (triggered) {
            return false;
        }
        return !simulatedTime.isBefore(forcedExitTime);
    }

    /**
     * Manually trigger forced exit (for backtest or external triggers).
     *
     * @return true if exit was triggered, false if already triggered
     */
    public boolean triggerManually() {
        if (triggered) {
            return false;
        }
        triggered = true;
        return true;
    }

    /**
     * Reset triggered state (for testing/reuse).
     */
    public void reset() {
        triggered = false;
    }

    /**
     * Get the configured forced exit time.
     *
     * @return forced exit time in IST
     */
    public LocalTime getForcedExitTime() {
        return forcedExitTime;
    }

    /**
     * Check if this strategy has already triggered.
     *
     * @return true if forced exit was triggered
     */
    public boolean isTriggered() {
        return triggered;
    }
}

