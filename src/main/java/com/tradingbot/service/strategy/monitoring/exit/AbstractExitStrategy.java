package com.tradingbot.service.strategy.monitoring.exit;

/**
 * Abstract base class for HFT-optimized exit strategies.
 * <p>
 * Provides common utilities for exit reason building and double formatting
 * using ThreadLocal StringBuilders to avoid allocation on the hot path.
 *
 * <h2>HFT Optimizations</h2>
 * <ul>
 *   <li>ThreadLocal StringBuilder for zero-allocation string building</li>
 *   <li>Fast double formatting without String.format overhead</li>
 *   <li>Pre-built exit reason prefixes to minimize string operations</li>
 * </ul>
 */
public abstract class AbstractExitStrategy implements ExitStrategy {

    // HFT: ThreadLocal StringBuilder for exit reason construction - avoids allocation
    protected static final ThreadLocal<StringBuilder> EXIT_REASON_BUILDER =
        ThreadLocal.withInitial(() -> new StringBuilder(64));

    // HFT: Dedicated ThreadLocal for formatDouble() to avoid conflict
    private static final ThreadLocal<StringBuilder> FORMAT_DOUBLE_BUILDER =
        ThreadLocal.withInitial(() -> new StringBuilder(16));

    /**
     * HFT: Fast double formatting without String.format overhead.
     * Uses ThreadLocal StringBuilder to avoid allocation on hot path.
     *
     * @param value the double value to format
     * @return string representation with 2 decimal places (e.g., "3.14", "-2.50")
     */
    protected static String formatDouble(double value) {
        long scaled = Math.round(value * 100);
        StringBuilder sb = FORMAT_DOUBLE_BUILDER.get();
        sb.setLength(0);
        if (scaled < 0) {
            sb.append('-');
            scaled = -scaled;
        }
        sb.append(scaled / 100);
        sb.append('.');
        long frac = scaled % 100;
        if (frac < 10) sb.append('0');
        sb.append(frac);
        return sb.toString();
    }

    /**
     * HFT: Append double with 2 decimal places to StringBuilder.
     *
     * @param sb StringBuilder to append to
     * @param value double value to format and append
     */
    protected static void appendDouble(StringBuilder sb, double value) {
        long scaled = Math.round(value * 100);
        if (scaled < 0) {
            sb.append('-');
            scaled = -scaled;
        }
        sb.append(scaled / 100);
        sb.append('.');
        long frac = scaled % 100;
        if (frac < 10) sb.append('0');
        sb.append(frac);
    }

    /**
     * Get a cleared StringBuilder for building exit reasons.
     *
     * @return ThreadLocal StringBuilder with length reset to 0
     */
    protected static StringBuilder getExitReasonBuilder() {
        StringBuilder sb = EXIT_REASON_BUILDER.get();
        sb.setLength(0);
        return sb;
    }
}

