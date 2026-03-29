package com.tradingbot.service.strategy;

import com.tradingbot.model.NeutralMarketEvaluation;

/**
 * Common interface for neutral market detection engines.
 *
 * <p>Currently implemented by {@link NeutralMarketDetectorServiceV3} — the 3-layer
 * tradable opportunity detector with regime scoring, microstructure bonuses, and
 * breakout risk veto gates.</p>
 *
 * <h2>Bean Names</h2>
 * <ul>
 *   <li>{@code "neutralMarketDetectorV3"} — V3 3-layer tradable opportunity detector</li>
 * </ul>
 *
 * <h2>Usage</h2>
 * <pre>
 * &#64;Autowired
 * &#64;Qualifier("neutralMarketDetectorV3")
 * private NeutralMarketDetector neutralMarketDetector;
 * </pre>
 *
 * <h2>HFT Safety</h2>
 * All implementations use per-instrument caching with configurable TTL.
 * {@link #evaluate(String)} returns a cached result on hot-path reads and
 * triggers a fresh evaluation only when the cache expires.
 *
 * @since 6.1
 * @see NeutralMarketDetectorServiceV3
 */
public interface NeutralMarketDetector {

    /**
     * Evaluate all neutral market signals for the given instrument.
     *
     * @param instrumentType "NIFTY" or "BANKNIFTY"
     * @return composite evaluation result (cached if TTL has not expired)
     */
    NeutralMarketEvaluation evaluate(String instrumentType);

    /**
     * Convenience: check if the market is currently neutral (tradable) for the given instrument.
     *
     * @param instrumentType "NIFTY" or "BANKNIFTY"
     * @return true if the market is neutral/tradable
     */
    boolean isMarketNeutral(String instrumentType);

    /**
     * Clear all cached evaluation state.
     * Useful for testing or forced refresh.
     */
    void clearCache();
}

