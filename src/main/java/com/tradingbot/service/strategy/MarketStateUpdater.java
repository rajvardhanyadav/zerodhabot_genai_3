package com.tradingbot.service.strategy;

import com.tradingbot.config.NeutralMarketConfig;
import com.tradingbot.model.MarketStateEvent;
import com.tradingbot.service.strategy.NeutralMarketDetectorService.NeutralMarketResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;

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
 * With 2 instruments (NIFTY + BANKNIFTY): 8 API calls per 30 s, well within Kite limits.
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

    /** Instruments to evaluate each cycle. */
    private static final String[] INSTRUMENTS = {"NIFTY", "BANKNIFTY"};

    private final NeutralMarketDetectorService neutralMarketDetectorService;
    private final NeutralMarketConfig neutralMarketConfig;
    private final ApplicationEventPublisher eventPublisher;

    public MarketStateUpdater(NeutralMarketDetectorService neutralMarketDetectorService,
                              NeutralMarketConfig neutralMarketConfig,
                              ApplicationEventPublisher eventPublisher) {
        this.neutralMarketDetectorService = neutralMarketDetectorService;
        this.neutralMarketConfig = neutralMarketConfig;
        this.eventPublisher = eventPublisher;
    }

    /**
     * Scheduled evaluation cycle. Runs at the interval configured by
     * {@code strategy.neutral-market-poll-interval-ms} (default 30 000 ms).
     *
     * <p>For each instrument, evaluates the neutral market detector and publishes
     * a {@link MarketStateEvent}. Listeners decide how to react (e.g. restart strategies).
     */
    @Scheduled(fixedRateString = "${strategy.neutral-market-poll-interval-ms:30000}")
    public void evaluateAndPublish() {
        if (!neutralMarketConfig.isEnabled()) {
            log.trace("MarketStateUpdater: neutral-market filter disabled, skipping evaluation cycle");
            return;
        }

        if (!isWithinMarketHours()) {
            log.trace("MarketStateUpdater: outside market hours, skipping evaluation cycle");
            return;
        }

        for (String instrument : INSTRUMENTS) {
            try {
                NeutralMarketResult result = neutralMarketDetectorService.evaluate(instrument);
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

                eventPublisher.publishEvent(event);

            } catch (Exception e) {
                log.error("MarketStateUpdater: failed to evaluate {}: {}", instrument, e.getMessage(), e);
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


