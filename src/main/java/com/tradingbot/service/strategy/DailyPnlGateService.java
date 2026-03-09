package com.tradingbot.service.strategy;

import com.tradingbot.config.StrategyConfig;
import com.tradingbot.model.StrategyCompletionReason;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Tracks cumulative daily realized P&L per user and gates auto-restart decisions.
 * <p>
 * When a strategy node (e.g., SELL_ATM_STRADDLE) completes with a target hit or stop loss,
 * the realized P&L is accumulated here. Before scheduling the next auto-restart,
 * {@link StrategyRestartScheduler} consults this service to check if the user has
 * breached the configurable daily profit or loss ceiling.
 *
 * <h2>Thread Safety</h2>
 * <ul>
 *   <li>Uses {@link ConcurrentHashMap} for per-user isolation</li>
 *   <li>Uses {@link AtomicReference} with CAS loop for lock-free P&L accumulation</li>
 *   <li>Safe for concurrent updates from multiple strategy completion threads</li>
 * </ul>
 *
 * <h2>HFT Considerations</h2>
 * <ul>
 *   <li>Lock-free accumulation via compare-and-swap (no synchronized blocks)</li>
 *   <li>{@code isBreached()} is a simple compare — O(1) with no allocation</li>
 *   <li>Daily reset via scheduled cron to avoid stale state</li>
 * </ul>
 *
 * @since 4.3
 */
@Service
@Slf4j
public class DailyPnlGateService {

    private static final ZoneId MARKET_ZONE = ZoneId.of("Asia/Kolkata");

    private final StrategyConfig strategyConfig;

    /**
     * Per-user cumulative daily P&L. Key = userId, Value = running total in INR.
     * Positive = profit, negative = loss.
     */
    private final ConcurrentHashMap<String, AtomicReference<BigDecimal>> dailyPnlByUser =
            new ConcurrentHashMap<>();

    /**
     * Tracks the trading date for which the current P&L state is valid.
     * Used to auto-reset on date change (defensive guard).
     */
    private volatile LocalDate currentTradingDate = LocalDate.now(MARKET_ZONE);

    public DailyPnlGateService(StrategyConfig strategyConfig) {
        this.strategyConfig = strategyConfig;
        log.info("DailyPnlGateService initialized - dailyMaxProfit={}, dailyMaxLoss={}",
                strategyConfig.getDailyMaxProfit(), strategyConfig.getDailyMaxLoss());
    }

    /**
     * Accumulate realized P&L for a completed strategy node.
     * <p>
     * Thread-safe via CAS loop on AtomicReference&lt;BigDecimal&gt;.
     *
     * @param userId  the user who owns the strategy
     * @param nodePnl the realized P&L of the completed node (positive = profit, negative = loss)
     */
    public void accumulate(String userId, BigDecimal nodePnl) {
        if (userId == null || nodePnl == null) {
            return;
        }

        // Defensive: reset if trading date has changed
        checkAndResetOnDateChange();

        AtomicReference<BigDecimal> ref = dailyPnlByUser.computeIfAbsent(userId,
                k -> new AtomicReference<>(BigDecimal.ZERO));

        // Lock-free CAS loop for thread-safe accumulation
        BigDecimal prev, next;
        do {
            prev = ref.get();
            next = prev.add(nodePnl);
        } while (!ref.compareAndSet(prev, next));

        log.info("Daily P&L updated for user={}: nodePnl={}, cumulativePnl={}", userId, nodePnl, next);
    }

    /**
     * Check if the user's cumulative daily P&L has breached either the max profit or max loss threshold.
     *
     * @param userId the user to check
     * @return true if either threshold is breached
     */
    public boolean isBreached(String userId) {
        return getBreachReason(userId).isPresent();
    }

    /**
     * Get the specific breach reason if any threshold is hit.
     *
     * @param userId the user to check
     * @return the breach reason, or empty if no threshold is breached
     */
    public Optional<StrategyCompletionReason> getBreachReason(String userId) {
        if (userId == null) {
            return Optional.empty();
        }

        BigDecimal cumulativePnl = getDailyPnl(userId);

        double maxProfit = strategyConfig.getDailyMaxProfit();
        double maxLoss = strategyConfig.getDailyMaxLoss();

        // Check profit ceiling (only if configured > 0)
        if (maxProfit > 0 && cumulativePnl.compareTo(BigDecimal.valueOf(maxProfit)) >= 0) {
            log.info("Daily PROFIT limit breached for user={}: cumulative={}, threshold={}",
                    userId, cumulativePnl, maxProfit);
            return Optional.of(StrategyCompletionReason.DAY_PROFIT_LIMIT_HIT);
        }

        // Check loss floor (only if configured > 0; loss threshold is positive, compare against negative P&L)
        if (maxLoss > 0 && cumulativePnl.compareTo(BigDecimal.valueOf(-maxLoss)) <= 0) {
            log.info("Daily LOSS limit breached for user={}: cumulative={}, threshold=-{}",
                    userId, cumulativePnl, maxLoss);
            return Optional.of(StrategyCompletionReason.DAY_LOSS_LIMIT_HIT);
        }

        return Optional.empty();
    }

    /**
     * Get the current cumulative daily P&L for a user.
     *
     * @param userId the user to query
     * @return cumulative P&L (ZERO if no data)
     */
    public BigDecimal getDailyPnl(String userId) {
        if (userId == null) {
            return BigDecimal.ZERO;
        }
        AtomicReference<BigDecimal> ref = dailyPnlByUser.get(userId);
        return ref != null ? ref.get() : BigDecimal.ZERO;
    }

    /**
     * Reset daily P&L for all users.
     * Called automatically at 9:00 AM IST (before market open) on weekdays.
     */
    @Scheduled(cron = "0 0 9 * * MON-FRI", zone = "Asia/Kolkata")
    public void resetAll() {
        int userCount = dailyPnlByUser.size();
        dailyPnlByUser.clear();
        currentTradingDate = LocalDate.now(MARKET_ZONE);
        log.info("Daily P&L reset for all users (count={}). Trading date: {}", userCount, currentTradingDate);
    }

    /**
     * Reset daily P&L for a specific user (e.g., on logout).
     *
     * @param userId the user to reset
     */
    public void resetUser(String userId) {
        if (userId != null) {
            AtomicReference<BigDecimal> removed = dailyPnlByUser.remove(userId);
            if (removed != null) {
                log.info("Daily P&L reset for user={} (was {})", userId, removed.get());
            }
        }
    }

    /**
     * Defensive check: if the calendar date has changed since last operation,
     * reset all P&L state. This handles cases where the scheduled cron is missed
     * (e.g., app restart after 9 AM).
     */
    private void checkAndResetOnDateChange() {
        LocalDate today = LocalDate.now(MARKET_ZONE);
        if (!today.equals(currentTradingDate)) {
            log.info("Trading date changed from {} to {} — resetting daily P&L", currentTradingDate, today);
            dailyPnlByUser.clear();
            currentTradingDate = today;
        }
    }
}


