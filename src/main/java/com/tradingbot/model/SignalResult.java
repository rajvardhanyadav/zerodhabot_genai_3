package com.tradingbot.model;

/**
 * Result of a single signal evaluation in the neutral market detection engine.
 *
 * <p>Each signal has a name, a weighted score (0 to maxScore), and a human-readable detail string.
 * Signals are evaluated independently and their scores are aggregated into a composite
 * {@link NeutralMarketResult}.</p>
 *
 * <h2>HFT Safety</h2>
 * Immutable record — zero allocation after construction. Use the static factory methods
 * to create instances with correct scoring.
 *
 * @param name     signal identifier (e.g., "VWAP_PROXIMITY", "RANGE_COMPRESSION")
 * @param score    achieved score for this signal (0 if failed, weight if passed)
 * @param maxScore maximum possible score for this signal (= weight)
 * @param passed   whether the signal condition was met
 * @param detail   human-readable detail string for logging/debugging
 * @since 5.0
 */
public record SignalResult(String name, int score, int maxScore, boolean passed, String detail) {

    /** Create a passing signal result with full weight score. */
    public static SignalResult passed(String name, int weight, String detail) {
        return new SignalResult(name, weight, weight, true, detail);
    }

    /** Create a failing signal result with zero score. */
    public static SignalResult failed(String name, int weight, String detail) {
        return new SignalResult(name, 0, weight, false, detail);
    }

    /** Create an unavailable signal result (data missing) with zero score. */
    public static SignalResult unavailable(String name, int weight, String reason) {
        return new SignalResult(name, 0, weight, false, "DATA_UNAVAILABLE: " + reason);
    }

    /**
     * Create a partially passing signal result with a fractional score.
     * Used for graduated scoring where signals aren't strictly binary.
     *
     * @param name         signal name
     * @param partialScore achieved score (0 to weight)
     * @param weight       max possible score
     * @param detail       detail string
     */
    public static SignalResult partial(String name, int partialScore, int weight, String detail) {
        return new SignalResult(name, Math.min(partialScore, weight), weight, partialScore > 0, detail);
    }
}

