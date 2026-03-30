package com.tradingbot.service.strategy;

import com.tradingbot.config.MarketDataEngineConfig;
import com.tradingbot.config.NeutralMarketV3Config;
import com.tradingbot.model.MarketStateEvent;
import com.tradingbot.model.NeutralMarketEvaluation;
import com.tradingbot.model.NeutralMarketResultV3;
import com.tradingbot.service.persistence.NeutralMarketLogService;
import com.tradingbot.service.session.UserSessionManager;
import com.tradingbot.util.CurrentUserContext;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Set;

/**
 * Periodically evaluates market neutrality and publishes {@link MarketStateEvent}s.
 *
 * <p>Replaces the previous per-execution polling loop in {@code StrategyRestartScheduler}.
 * A single scheduled task evaluates each instrument once per cycle (default 30s) and
 * publishes events that any listener can react to — eliminating thread-blocking polls
 * and duplicate API calls when multiple executions are pending restart.</p>
 *
 * <h2>API Call Budget</h2>
 * Per instrument per cycle: 4 API calls (VWAP candles, ADX candles, OI quotes, LTP).
 * With 1 instrument (NIFTY): 4 API calls per 30 s, well within Kite limits.
 *
 * @since 4.2
 * @see MarketStateEvent
 * @see StrategyRestartScheduler
 */
@Component
@Slf4j
public class MarketStateUpdater {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final LocalTime MARKET_OPEN = LocalTime.of(9, 15);
    private static final LocalTime MARKET_CLOSE = LocalTime.of(15, 10);

    private final NeutralMarketDetector neutralMarketDetectorService;
    private final NeutralMarketV3Config neutralMarketV3Config;
    private final MarketDataEngineConfig marketDataEngineConfig;
    private final ApplicationEventPublisher eventPublisher;
    private final UserSessionManager userSessionManager;
    private final NeutralMarketLogService neutralMarketLogService;

    public MarketStateUpdater(@Qualifier("neutralMarketDetectorV3") NeutralMarketDetector neutralMarketDetectorService,
                              NeutralMarketV3Config neutralMarketV3Config,
                              MarketDataEngineConfig marketDataEngineConfig,
                              ApplicationEventPublisher eventPublisher,
                              UserSessionManager userSessionManager,
                              NeutralMarketLogService neutralMarketLogService) {
        this.neutralMarketDetectorService = neutralMarketDetectorService;
        this.neutralMarketV3Config = neutralMarketV3Config;
        this.marketDataEngineConfig = marketDataEngineConfig;
        this.eventPublisher = eventPublisher;
        this.userSessionManager = userSessionManager;
        this.neutralMarketLogService = neutralMarketLogService;
    }

    @PostConstruct
    void logStartupInfo() {
        log.info("MarketStateUpdater initialized. Neutral market log persistence prerequisites: " +
                "(1) V3 detector enabled={}, " +
                "(2) active Kite session required (POST /auth/login), " +
                "(3) market hours IST {}-{} weekdays only, " +
                "(4) persistence enabled. " +
                "Evaluation cycle interval: {}ms (from strategy.neutral-market-poll-interval-ms)",
                neutralMarketV3Config.isEnabled(), MARKET_OPEN, MARKET_CLOSE,
                "${strategy.neutral-market-poll-interval-ms:30000}");
    }

    /** Tracks whether we've already warned about missing sessions (to avoid log spam). */
    private volatile boolean warnedNoSessions = false;

    /**
     * Scheduled evaluation cycle. Runs at the interval configured by
     * {@code strategy.neutral-market-poll-interval-ms} (default 30 000 ms).
     *
     * <p>For each instrument, evaluates the neutral market detector and publishes
     * a {@link MarketStateEvent}. Listeners decide how to react (e.g. restart strategies).
     */
    @Scheduled(fixedRateString = "${strategy.neutral-market-poll-interval-ms:30000}")
    public void evaluateAndPublish() {
        if (!neutralMarketV3Config.isEnabled()) {
            log.trace("MarketStateUpdater: V3 neutral-market filter disabled, skipping evaluation cycle");
            return;
        }

        if (!isWithinMarketHours()) {
            log.trace("MarketStateUpdater: outside market hours, skipping evaluation cycle");
            return;
        }

        // Scheduled tasks run without HTTP request context, so CurrentUserContext is empty.
        // We must set it explicitly from an active session so that downstream TradingService
        // calls (which resolve KiteConnect via getRequiredKiteForCurrentUser) find a valid session
        // instead of falling back to PAPER_DEFAULT_USER.
        Set<String> activeUserIds = userSessionManager.getActiveUserIds();
        if (activeUserIds.isEmpty()) {
            if (!warnedNoSessions) {
                log.warn("MarketStateUpdater: no active user sessions — neutral market evaluations " +
                        "and persistence are PAUSED. Log in via POST /auth/login to activate. " +
                        "(This warning is logged once; subsequent skips logged at DEBUG level.)");
                warnedNoSessions = true;
            } else {
                log.debug("MarketStateUpdater: no active user sessions, skipping evaluation cycle");
            }
            return;
        }
        // Reset warning flag when sessions become available
        warnedNoSessions = false;

        // Use the first active user's session for market data API calls.
        // Neutral market evaluation is user-agnostic (same market data for all users),
        // so any authenticated session suffices.
        String userId = activeUserIds.iterator().next();
        String previousUserId = CurrentUserContext.getUserId();
        try {
            CurrentUserContext.setUserId(userId);
            log.debug("MarketStateUpdater: set user context to {} for scheduled evaluation", userId);

            for (String instrument : marketDataEngineConfig.getSupportedInstrumentsArray()) {
                try {
                    NeutralMarketEvaluation result = neutralMarketDetectorService.evaluate(instrument);
                    Instant now = Instant.now();

                    MarketStateEvent event = new MarketStateEvent(
                            instrument,
                            result.neutral(),
                            result.totalScore(),
                            result.maxScore(),
                            result,
                            now
                    );

                    log.debug("MarketStateUpdater: publishing event for {}: neutral={}, score={}/{}",
                            instrument, result.neutral(), result.totalScore(), result.maxScore());

                    // Log at INFO when regime is neutral but veto gate blocks tradability
                    String vetoReason = result.getVetoReason();
                    if (!result.neutral() && vetoReason != null && !"TRENDING".equals(result.getRegimeLabel())) {
                        log.info("MarketStateUpdater: {} regime={} but VETO blocked tradability. " +
                                        "vetoReason={}, score={}/{}",
                                instrument, result.getRegimeLabel(), vetoReason,
                                result.totalScore(), result.maxScore());
                    }

                    eventPublisher.publishEvent(event);

                    // Persist evaluation log asynchronously for historical analysis
                    if (result instanceof NeutralMarketResultV3 v3Result) {
                        log.debug("MarketStateUpdater: persisting V3 evaluation log for {} (tradable={}, regime={}, score={})",
                                instrument, v3Result.isTradable(), v3Result.getRegime(), v3Result.getFinalScore());
                        neutralMarketLogService.persistEvaluationAsync(v3Result, instrument);
                    } else {
                        log.debug("MarketStateUpdater: evaluation result for {} is not V3 (type={}), skipping persistence",
                                instrument, result.getClass().getSimpleName());
                    }

                } catch (Exception e) {
                    log.error("MarketStateUpdater: failed to evaluate {}: {}", instrument, e.getMessage(), e);
                }
            }
        } finally {
            // Restore previous user context to avoid leaking into other scheduled tasks
            if (previousUserId == null || previousUserId.isBlank()) {
                CurrentUserContext.clear();
            } else {
                CurrentUserContext.setUserId(previousUserId);
            }
        }
    }

    /**
     * Check if current IST time is within market trading hours.
     * Package-private for test override.
     */
    boolean isWithinMarketHours() {
        ZonedDateTime now = ZonedDateTime.now(IST);
        LocalTime time = now.toLocalTime();
        java.time.DayOfWeek day = now.getDayOfWeek();

        if (day == java.time.DayOfWeek.SATURDAY || day == java.time.DayOfWeek.SUNDAY) {
            return false;
        }

        return !time.isBefore(MARKET_OPEN) && !time.isAfter(MARKET_CLOSE);
    }
}
