package com.tradingbot.backtest.engine;

import com.tradingbot.backtest.adapter.HistoricalCandleAdapter;
import com.tradingbot.backtest.adapter.HistoricalCandleAdapter.SimulatedCandle;
import com.tradingbot.backtest.adapter.TickFeedMerger;
import com.tradingbot.backtest.adapter.TickFeedMerger.MergedTick;
import com.tradingbot.backtest.dto.BacktestRequest;
import com.tradingbot.backtest.dto.BacktestTrade;
import com.tradingbot.backtest.engine.InstrumentResolver.ResolvedInstruments;
import com.tradingbot.model.SlTargetMode;
import com.tradingbot.service.strategy.monitoring.PositionMonitorV2;
import com.tradingbot.util.CandleUtils;
import com.zerodhatech.models.HistoricalData;
import com.zerodhatech.models.Instrument;
import com.zerodhatech.models.Tick;
import lombok.extern.slf4j.Slf4j;

import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Core backtest simulation engine.
 * <p>
 * At each entry point (initial + every auto-restart), the engine:
 * <ol>
 *   <li>Looks up the underlying spot price from pre-fetched index candles at the entry time</li>
 *   <li>Resolves fresh ATM CE/PE instruments for that spot price</li>
 *   <li>Fetches minute-candle data for those specific CE/PE instruments</li>
 *   <li>Merges into a tick feed and runs the trade cycle through {@link PositionMonitorV2}</li>
 * </ol>
 * This mirrors the live strategy behavior where ATM strike is recalculated on every restart.
 * <p>
 * Thread safety: NOT thread-safe. Each backtest should create its own engine instance.
 */
@Slf4j
public class BacktestEngine {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    // ==================== CONFIGURATION ====================

    private final BacktestRequest request;
    private final int quantity;

    // Pre-fetched data (fetched once per backtest day)
    private final List<SimulatedCandle> indexCandles;
    private final List<Instrument> nfoInstruments;

    // Services for dynamic ATM resolution
    private final InstrumentResolver instrumentResolver;
    private final HistoricalDataFetcher historicalDataFetcher;

    // Parsed time boundaries
    private final LocalTime startTime;
    private final LocalTime endTime;
    private final LocalTime autoSquareOffTime;

    // SL/Target config
    private final double stopLossPoints;
    private final double targetPoints;
    private final double targetDecayPct;
    private final double stopLossExpansionPct;
    private final SlTargetMode slTargetMode;

    public BacktestEngine(BacktestRequest request,
                           List<SimulatedCandle> indexCandles,
                           List<Instrument> nfoInstruments,
                           InstrumentResolver instrumentResolver,
                           HistoricalDataFetcher historicalDataFetcher,
                           int quantity) {
        this.request = request;
        this.indexCandles = indexCandles;
        this.nfoInstruments = nfoInstruments;
        this.instrumentResolver = instrumentResolver;
        this.historicalDataFetcher = historicalDataFetcher;
        this.quantity = quantity;

        // Parse time boundaries
        this.startTime = LocalTime.parse(request.getStartTime());
        this.endTime = LocalTime.parse(request.getEndTime());
        this.autoSquareOffTime = LocalTime.parse(request.getAutoSquareOffTime());

        // Resolve SL/Target mode
        String mode = request.getSlTargetMode();
        if ("premium".equalsIgnoreCase(mode) || "percentage".equalsIgnoreCase(mode)) {
            this.slTargetMode = SlTargetMode.PREMIUM;
        } else {
            this.slTargetMode = SlTargetMode.POINTS;
        }

        // Resolve values with defaults (aligned with StrategyConfig defaults)
        this.stopLossPoints = request.getStopLossPoints() != null ? request.getStopLossPoints() : 2.0;
        this.targetPoints = request.getTargetPoints() != null ? request.getTargetPoints() : 2.0;
        this.targetDecayPct = request.getTargetDecayPct() != null ? request.getTargetDecayPct() : 3.5;
        this.stopLossExpansionPct = request.getStopLossExpansionPct() != null ? request.getStopLossExpansionPct() : 7.0;
    }

    /**
     * Run the full simulation for the day.
     * <p>
     * At each entry point, the spot price is looked up from index candles and
     * fresh ATM CE/PE instruments are resolved. This is the correct behavior —
     * matching how the live strategy recalculates ATM on every execution.
     *
     * @return list of simulated trades
     */
    public List<BacktestTrade> runSimulation() {
        List<BacktestTrade> trades = new ArrayList<>();
        int tradeNumber = 0;
        int restartCount = 0;
        boolean isRestart = false;
        LocalTime nextEntryTime = startTime;

        log.info("Starting backtest simulation: {} index candles, startTime={}, endTime={}, squareOff={}",
                indexCandles.size(), startTime, endTime, autoSquareOffTime);

        while (true) {
            // Check if next entry time is past auto square-off
            if (!nextEntryTime.isBefore(autoSquareOffTime)) {
                log.debug("Entry time {} is at/past auto square-off {}, no new entries", nextEntryTime, autoSquareOffTime);
                break;
            }
            if (nextEntryTime.isAfter(endTime)) {
                log.debug("Entry time {} is past end time {}, stopping", nextEntryTime, endTime);
                break;
            }

            // Step 1: Look up spot price at entry time from index candles
            double spotPrice = lookupSpotPrice(nextEntryTime);
            if (spotPrice <= 0) {
                log.warn("No spot price available at {}, ending simulation", nextEntryTime);
                break;
            }

            // Step 2: Resolve ATM CE/PE instruments for this spot price
            ResolvedInstruments resolved;
            try {
                resolved = instrumentResolver.resolveForSpotPrice(
                        request.getInstrumentType(), request.getExpiryDate(),
                        spotPrice, nfoInstruments);
            } catch (BacktestException e) {
                log.error("Failed to resolve ATM instruments at {}: {}", nextEntryTime, e.getMessage());
                break;
            }

            Instrument ce = resolved.ceInstrument();
            Instrument pe = resolved.peInstrument();

            log.info("{} #{}: spot={} -> ATM strike={}, CE={}, PE={}",
                    isRestart ? "Restart" : "Entry", tradeNumber + 1,
                    String.format("%.2f", spotPrice), resolved.atmStrike(),
                    ce.tradingsymbol, pe.tradingsymbol);

            // Step 3: Fetch CE/PE candle data for this specific strike
            String interval = request.getCandleInterval() != null
                    ? request.getCandleInterval() : "minute";
            List<MergedTick> tickFeed = fetchAndMergeTicks(ce, pe, interval);

            if (tickFeed.isEmpty()) {
                log.warn("No tick data available for CE={} PE={}, ending simulation", ce.tradingsymbol, pe.tradingsymbol);
                break;
            }

            // Step 4: Find the entry tick at or after nextEntryTime
            int feedIndex = findTickIndex(tickFeed, nextEntryTime);
            if (feedIndex < 0) {
                log.warn("No ticks at or after {} for strike {}", nextEntryTime, resolved.atmStrike());
                break;
            }

            MergedTick entryTick = tickFeed.get(feedIndex);

            // Recheck time boundaries with actual tick
            if (entryTick.timestamp().toLocalTime().isAfter(endTime)) break;
            if (!entryTick.timestamp().toLocalTime().isBefore(autoSquareOffTime)) break;

            tradeNumber++;
            double ceEntryPrice = entryTick.ceLtp();
            double peEntryPrice = entryTick.peLtp();
            double combinedEntryPremium = ceEntryPrice + peEntryPrice;

            log.info("Trade #{}: Entry at {} | CE={} @ {} | PE={} @ {} | Combined={}",
                    tradeNumber, entryTick.timestamp(), ce.tradingsymbol, ceEntryPrice,
                    pe.tradingsymbol, peEntryPrice, combinedEntryPremium);

            // Step 5: Run one trade cycle
            TradeCycleResult result = runTradeCycle(
                    tickFeed, feedIndex, ce, pe, ceEntryPrice, peEntryPrice, combinedEntryPremium);

            // Build trade record
            MergedTick exitTick = result.exitTickIndex < tickFeed.size()
                    ? tickFeed.get(result.exitTickIndex)
                    : tickFeed.get(tickFeed.size() - 1);

            double ceExitPrice = exitTick.ceLtp();
            double peExitPrice = exitTick.peLtp();
            double combinedExitPremium = ceExitPrice + peExitPrice;

            // For SHORT straddle: P&L = (entryPremium - exitPremium)
            double pnlPoints = combinedEntryPremium - combinedExitPremium;
            double pnlAmount = pnlPoints * quantity;

            BacktestTrade trade = BacktestTrade.builder()
                    .tradeNumber(tradeNumber)
                    .ceSymbol(ce.tradingsymbol)
                    .peSymbol(pe.tradingsymbol)
                    .strikePrice(resolved.atmStrike())
                    .entryTime(entryTick.timestamp())
                    .ceEntryPrice(ceEntryPrice)
                    .peEntryPrice(peEntryPrice)
                    .combinedEntryPremium(combinedEntryPremium)
                    .exitTime(exitTick.timestamp())
                    .ceExitPrice(ceExitPrice)
                    .peExitPrice(peExitPrice)
                    .combinedExitPremium(combinedExitPremium)
                    .quantity(quantity)
                    .pnlPoints(pnlPoints)
                    .pnlAmount(pnlAmount)
                    .exitReason(result.exitReason)
                    .wasRestarted(isRestart)
                    .build();

            trades.add(trade);
            log.info("Trade #{} closed: exitTime={}, strike={}, P&L={} pts ({} INR), reason={}",
                    tradeNumber, exitTick.timestamp(), resolved.atmStrike(),
                    String.format("%.2f", pnlPoints), String.format("%.2f", pnlAmount), result.exitReason);

            // Step 6: Check auto-restart
            if (!shouldAutoRestart(result.exitReason, restartCount, exitTick.timestamp().toLocalTime())) {
                break;
            }

            // Fast-forward to next 5-minute candle boundary
            LocalDateTime exitDt = exitTick.timestamp();
            ZonedDateTime exitZdt = exitDt.atZone(IST);
            ZonedDateTime nextCandle = CandleUtils.nextFiveMinuteCandle(exitZdt);
            nextEntryTime = nextCandle.toLocalTime();

            restartCount++;
            isRestart = true;
            log.info("Auto-restart #{}: next entry at {} (next 5-min candle, fresh ATM lookup)", restartCount, nextEntryTime);
        }

        log.info("Simulation complete: {} trades, {} restarts", trades.size(), restartCount);
        return trades;
    }

    // ==================== SPOT PRICE LOOKUP ====================

    /**
     * Look up the spot price at or just before the given time from pre-fetched index candles.
     * Uses the close price of the candle at or immediately before the target time.
     *
     * @return spot price, or 0 if not found
     */
    private double lookupSpotPrice(LocalTime targetTime) {
        double lastClose = 0;
        for (SimulatedCandle candle : indexCandles) {
            LocalTime candleTime = candle.timestamp().toLocalTime();
            if (candleTime.isAfter(targetTime)) break;
            lastClose = candle.close();
        }
        if (lastClose > 0) {
            log.debug("Spot price at {}: {}", targetTime, lastClose);
        }
        return lastClose;
    }

    // ==================== TICK FEED CONSTRUCTION ====================

    /**
     * Fetch and merge CE/PE candle data into a MergedTick feed.
     */
    private List<MergedTick> fetchAndMergeTicks(Instrument ce, Instrument pe, String interval) {
        try {
            HistoricalData ceData = historicalDataFetcher.fetchDayCandles(
                    String.valueOf(ce.instrument_token), request.getBacktestDate(), interval);
            HistoricalData peData = historicalDataFetcher.fetchDayCandles(
                    String.valueOf(pe.instrument_token), request.getBacktestDate(), interval);

            List<SimulatedCandle> ceCandles = HistoricalCandleAdapter.convert(ceData, ce.instrument_token);
            List<SimulatedCandle> peCandles = HistoricalCandleAdapter.convert(peData, pe.instrument_token);

            return TickFeedMerger.merge(ceCandles, peCandles, ce.instrument_token, pe.instrument_token);
        } catch (BacktestException e) {
            log.error("Failed to fetch tick data for CE={} PE={}: {}", ce.tradingsymbol, pe.tradingsymbol, e.getMessage());
            return List.of();
        }
    }

    // ==================== TRADE CYCLE ====================

    private record TradeCycleResult(int exitTickIndex, String exitReason) {}

    /**
     * Run a single trade cycle starting from the given tick index.
     */
    private TradeCycleResult runTradeCycle(List<MergedTick> tickFeed, int startIndex,
                                            Instrument ce, Instrument pe,
                                            double ceEntryPrice, double peEntryPrice,
                                            double combinedEntryPremium) {
        String executionId = "bt-" + UUID.randomUUID().toString().substring(0, 8);
        boolean premiumBasedExit = (slTargetMode == SlTargetMode.PREMIUM);

        // Create fresh PositionMonitorV2 — forcedExitEnabled=false (engine handles time exit)
        PositionMonitorV2 monitor = new PositionMonitorV2(
                executionId,
                stopLossPoints,
                targetPoints,
                PositionMonitorV2.PositionDirection.SHORT,
                request.isTrailingStopEnabled(),
                request.getTrailingActivationPoints() != null ? request.getTrailingActivationPoints() : 0,
                request.getTrailingDistancePoints() != null ? request.getTrailingDistancePoints() : 0,
                false, null,
                premiumBasedExit, combinedEntryPremium,
                targetDecayPct, stopLossExpansionPct, slTargetMode
        );

        monitor.addLeg("bt-ce-" + executionId, ce.tradingsymbol, ce.instrument_token, ceEntryPrice, quantity, "CE");
        monitor.addLeg("bt-pe-" + executionId, pe.tradingsymbol, pe.instrument_token, peEntryPrice, quantity, "PE");

        AtomicReference<String> exitReasonRef = new AtomicReference<>("UNKNOWN");
        AtomicBoolean exited = new AtomicBoolean(false);
        monitor.setExitCallback(reason -> {
            exitReasonRef.set(reason);
            exited.set(true);
        });

        int exitIndex = startIndex;

        for (int i = startIndex; i < tickFeed.size(); i++) {
            MergedTick tick = tickFeed.get(i);
            LocalTime tickTime = tick.timestamp().toLocalTime();

            if (tickTime.isAfter(endTime)) {
                exitIndex = i > startIndex ? i - 1 : startIndex;
                if (!exited.get()) exitReasonRef.set("END_OF_DATA");
                break;
            }

            if (!tickTime.isBefore(autoSquareOffTime)) {
                exitIndex = i;
                if (!exited.get()) exitReasonRef.set("TIME_BASED_FORCED_EXIT @ " + autoSquareOffTime);
                break;
            }

            ArrayList<Tick> ticks = buildTicks(tick);
            monitor.updatePriceWithDifferenceCheck(ticks);
            exitIndex = i;

            if (exited.get() || !monitor.isActive()) break;
        }

        if (!exited.get() && monitor.isActive()) {
            exitReasonRef.set("END_OF_DATA");
        }

        return new TradeCycleResult(exitIndex, exitReasonRef.get());
    }

    // ==================== UTILITY METHODS ====================

    private ArrayList<Tick> buildTicks(MergedTick merged) {
        ArrayList<Tick> ticks = new ArrayList<>(2);

        Tick ceTick = new Tick();
        ceTick.setInstrumentToken(merged.ceToken());
        ceTick.setLastTradedPrice(merged.ceLtp());
        ticks.add(ceTick);

        Tick peTick = new Tick();
        peTick.setInstrumentToken(merged.peToken());
        peTick.setLastTradedPrice(merged.peLtp());
        ticks.add(peTick);

        return ticks;
    }

    private int findTickIndex(List<MergedTick> tickFeed, LocalTime time) {
        for (int i = 0; i < tickFeed.size(); i++) {
            LocalTime tickTime = tickFeed.get(i).timestamp().toLocalTime();
            if (!tickTime.isBefore(time)) return i;
        }
        return -1;
    }

    private boolean shouldAutoRestart(String exitReason, int currentRestarts, LocalTime exitTime) {
        if (!request.isAutoRestartEnabled()) return false;

        if (exitReason == null) return false;
        String upper = exitReason.toUpperCase();
        boolean isTargetOrSl = upper.contains("TARGET") || upper.contains("STOPLOSS")
                || upper.contains("PREMIUM_DECAY") || upper.contains("PREMIUM_EXPANSION");
        if (!isTargetOrSl) return false;

        int maxRestarts = request.getMaxAutoRestarts();
        if (maxRestarts > 0 && currentRestarts >= maxRestarts) {
            log.info("Max auto-restarts ({}) reached, stopping", maxRestarts);
            return false;
        }

        if (!exitTime.isBefore(autoSquareOffTime)) {
            log.info("Exit at {} is at/after auto square-off time {}, no restart", exitTime, autoSquareOffTime);
            return false;
        }

        return true;
    }
}

