package com.tradingbot.backtest.strategy;

import com.tradingbot.backtest.adapter.HistoricalDataAdapter;
import com.tradingbot.backtest.config.BacktestExitStrategyBuilder;
import com.tradingbot.backtest.dto.BacktestRequest;
import com.tradingbot.backtest.dto.CandleData;
import com.tradingbot.backtest.engine.BacktestContext;
import com.tradingbot.backtest.engine.BacktestContext.SimulatedPosition;
import com.tradingbot.backtest.engine.BacktestExitStrategyEvaluator;
import com.tradingbot.model.StrategyType;
import com.tradingbot.paper.ZerodhaChargeCalculator;
import com.tradingbot.paper.entity.OrderCharges;
import com.tradingbot.service.strategy.monitoring.PositionMonitorV2;
import com.tradingbot.service.strategy.monitoring.exit.ExitResult;
import com.tradingbot.service.strategy.monitoring.exit.ExitStrategy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Backtest implementation of the Sell ATM Straddle strategy.
 *
 * <h2>Unified Monitoring</h2>
 * <p>
 * This strategy uses the SAME exit strategy framework as live/paper trading,
 * ensuring consistent behavior across all trading modes. The key components are:
 * <ul>
 *   <li>{@link BacktestExitStrategyEvaluator} - Evaluates exit conditions using candle data</li>
 *   <li>{@link BacktestExitStrategyBuilder} - Builds identical exit strategy chain as live</li>
 *   <li>Reuses {@link ExitStrategy} implementations from live trading</li>
 * </ul>
 *
 * <h2>Strategy Logic</h2>
 * <ol>
 *   <li>At entry, sell ATM Call and Put options (straddle)</li>
 *   <li>Monitor combined premium using unified exit strategies</li>
 *   <li>On SL hit, close positions and optionally restart</li>
 *   <li>Square off at market close</li>
 * </ol>
 *
 * @see BacktestExitStrategyEvaluator
 * @see BacktestExitStrategyBuilder
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class BacktestSellStraddleStrategy implements BacktestStrategy {

    private final BacktestStrategyFactory strategyFactory;
    private final HistoricalDataAdapter historicalDataAdapter;
    private final ZerodhaChargeCalculator chargeCalculator;

    /** Exit strategy evaluator - uses same logic as live trading */
    private BacktestExitStrategyEvaluator exitStrategyEvaluator;

    /** Current backtest request for reference */
    private BacktestRequest currentRequest;

    // Strategy placement starts at 9:35 AM (after first 4 candles of market open)
    private static final LocalTime STRATEGY_START_TIME = LocalTime.of(9, 35);

    // Market timing constants
    private static final LocalTime MARKET_OPEN = LocalTime.of(9, 15);
    private static final LocalTime MARKET_CLOSE = LocalTime.of(15, 30);

    // Cache for option candles - keyed by "STRIKE_CE" or "STRIKE_PE"
    private final Map<String, List<CandleData>> optionCandleCache = new ConcurrentHashMap<>();

    // Flag to track if option data has been pre-fetched for current position
    private boolean optionDataPreFetched = false;

    // Lot sizes (can be made configurable)
    private static final Map<String, Integer> LOT_SIZES = Map.of(
            "NIFTY", 25,
            "BANKNIFTY", 15
    );

    // Strike interval for each instrument
    private static final Map<String, BigDecimal> STRIKE_INTERVALS = Map.of(
            "NIFTY", BigDecimal.valueOf(50),
            "BANKNIFTY", BigDecimal.valueOf(100)
    );

    @PostConstruct
    public void register() {
        strategyFactory.registerStrategy(StrategyType.SELL_ATM_STRADDLE, this);
        log.info("Registered BacktestSellStraddleStrategy for SELL_ATM_STRADDLE");
    }

    @Override
    public void initialize(BacktestRequest request, BacktestContext context) {
        this.currentRequest = request;
        int lotSize = LOT_SIZES.getOrDefault(request.getInstrumentType().toUpperCase(), 25);
        context.setLotSize(lotSize);

        // Build exit strategies using the same configuration as live trading
        // Time-based exit is handled separately by onMarketClose, so we exclude it here
        List<ExitStrategy> exitStrategies = BacktestExitStrategyBuilder.buildWithoutTimeBased(request);

        // Create evaluator with SHORT direction for SELL straddle
        this.exitStrategyEvaluator = new BacktestExitStrategyEvaluator(
            exitStrategies,
            context,
            PositionMonitorV2.PositionDirection.SHORT
        );

        log.debug("Initialized backtest for {} with lot size {}, {} exit strategies",
                request.getInstrumentType(), lotSize, exitStrategies.size());
    }

    @Override
    public void onCandle(CandleData candle, BacktestContext context, List<CandleData> historicalCandles) {
        // Skip candles before strategy start time (9:35 AM)
        LocalTime candleTime = candle.getTimestamp().toLocalTime();
        if (candleTime.isBefore(STRATEGY_START_TIME)) {
            log.trace("Skipping candle at {} - before strategy start time {}", candleTime, STRATEGY_START_TIME);
            return;
        }

        // If no position, enter using delta-based ATM selection
        if (!context.isPositionActive()) {
            enterPositionWithDelta(candle, context, historicalCandles);
            return;
        }

        // Check SL/Target conditions using fetched option data
        checkExitConditionsWithOptionData(candle, context);
    }

    @Override
    public void onRestart(CandleData candle, BacktestContext context) {
        log.debug("Strategy restart at {}, entering new position", candle.getTimestamp());

        // Reset exit strategy state (trailing stop, etc.) for fresh start
        if (exitStrategyEvaluator != null) {
            exitStrategyEvaluator.resetStrategies();
        }

        // Clear option data cache for new position
        optionCandleCache.clear();
        optionDataPreFetched = false;

        // Use the current candle as the list for delta calculation
        enterPositionWithDelta(candle, context, List.of(candle));
    }

    @Override
    public void onMarketClose(CandleData lastCandle, BacktestContext context) {
        if (context.isPositionActive()) {
            log.debug("Market close square-off at {}", lastCandle.getTimestamp());

            // Use actual option prices from cache for exit
            Map<String, BigDecimal> exitPrices = new HashMap<>();
            for (Map.Entry<String, SimulatedPosition> entry : context.getOpenPositions().entrySet()) {
                String symbol = entry.getKey();
                SimulatedPosition position = entry.getValue();

                // Get exit price from cached option data or last candle
                BigDecimal exitPrice = getExitPriceFromCache(
                        position.getStrikePrice(),
                        position.getOptionType(),
                        lastCandle.getTimestamp()
                );
                if (exitPrice.compareTo(BigDecimal.ZERO) <= 0) {
                    exitPrice = lastCandle.getClose();
                }
                exitPrices.put(symbol, exitPrice);
            }

            context.closeAllPositions(exitPrices, lastCandle.getTimestamp(), "SQUARE_OFF");
        }

        // Clear cache at end of day
        optionCandleCache.clear();
        optionDataPreFetched = false;
    }

    @Override
    public String getStrategyName() {
        return "Backtest Sell ATM Straddle";
    }

    /**
     * Enter a new straddle position using delta-based ATM strike selection.
     * Pre-fetches option data for both CE and PE legs for efficient monitoring.
     * <p>
     * This method mirrors live/paper trading behavior:
     * <ul>
     *   <li>Calculates delta for multiple strikes around ATM</li>
     *   <li>Selects strike with delta nearest to 0.5</li>
     *   <li>Pre-fetches all option candles for the day for efficient evaluation</li>
     * </ul>
     *
     * @param spotCandle current spot candle
     * @param context backtest context
     * @param historicalCandles historical candles for delta calculation
     */
    private void enterPositionWithDelta(CandleData spotCandle, BacktestContext context,
                                         List<CandleData> historicalCandles) {
        BigDecimal spotPrice = spotCandle.getClose();
        LocalDateTime entryTime = spotCandle.getTimestamp();

        // Calculate ATM strike using delta-based selection (same as live trading)
        BigDecimal atmStrike = calculateATMStrikeByDelta(spotPrice, entryTime, context);

        log.info("Delta-based ATM strike selection: spot={}, selected strike={}", spotPrice, atmStrike);

        int quantity = context.getLotSize() * context.getLots();

        // Generate symbols using actual expiry date
        String ceSymbol = generateSymbol(atmStrike, "CE", context);
        String peSymbol = generateSymbol(atmStrike, "PE", context);

        // Pre-fetch ALL option candles for CE and PE for the day (efficient batch fetch)
        preFetchOptionCandlesForDay(atmStrike, context);

        // Get entry prices from cached option data
        BigDecimal cePremium = getExitPriceFromCache(atmStrike, "CE", entryTime);
        BigDecimal pePremium = getExitPriceFromCache(atmStrike, "PE", entryTime);

        // Fallback to estimation if cache fails
        if (cePremium.compareTo(BigDecimal.ZERO) <= 0) {
            cePremium = fetchOptionPrice(atmStrike, "CE", entryTime, context);
        }
        if (pePremium.compareTo(BigDecimal.ZERO) <= 0) {
            pePremium = fetchOptionPrice(atmStrike, "PE", entryTime, context);
        }

        // Calculate and store entry charges (same as paper/live trading)
        BigDecimal ceTurnover = cePremium.multiply(BigDecimal.valueOf(quantity));
        BigDecimal peTurnover = pePremium.multiply(BigDecimal.valueOf(quantity));
        OrderCharges ceCharges = chargeCalculator.calculateCharges(
                ZerodhaChargeCalculator.OrderType.OPTIONS,
                ZerodhaChargeCalculator.TransactionType.SELL,
                ceTurnover,
                BigDecimal.valueOf(quantity)
        );
        OrderCharges peCharges = chargeCalculator.calculateCharges(
                ZerodhaChargeCalculator.OrderType.OPTIONS,
                ZerodhaChargeCalculator.TransactionType.SELL,
                peTurnover,
                BigDecimal.valueOf(quantity)
        );

        // Create CE position
        SimulatedPosition cePosition = new SimulatedPosition();
        cePosition.setTradingSymbol(ceSymbol);
        cePosition.setOptionType("CE");
        cePosition.setStrikePrice(atmStrike);
        cePosition.setEntryTime(entryTime);
        cePosition.setEntryPrice(cePremium);
        cePosition.setQuantity(quantity);
        cePosition.setTransactionType("SELL");
        context.addPosition(ceSymbol, cePosition);

        // Create PE position
        SimulatedPosition pePosition = new SimulatedPosition();
        pePosition.setTradingSymbol(peSymbol);
        pePosition.setOptionType("PE");
        pePosition.setStrikePrice(atmStrike);
        pePosition.setEntryTime(entryTime);
        pePosition.setEntryPrice(pePremium);
        pePosition.setQuantity(quantity);
        pePosition.setTransactionType("SELL");
        context.addPosition(peSymbol, pePosition);

        // Record combined entry premium
        BigDecimal combinedPremium = cePremium.add(pePremium);
        context.setEntryPremium(combinedPremium);
        context.setCurrentStrike(atmStrike);

        // Store total entry charges in context for PnL calculation
        BigDecimal totalEntryCharges = ceCharges.getTotalCharges().add(peCharges.getTotalCharges());
        context.setEntryCharges(totalEntryCharges);

        // Rebuild exit evaluator context with new positions
        if (exitStrategyEvaluator != null) {
            exitStrategyEvaluator.rebuildContextBuilder();
        }

        log.info("Entered straddle at strike {}, CE premium={}, PE premium={}, combined={}, entryCharges={}",
                atmStrike, cePremium, pePremium, combinedPremium, totalEntryCharges);
    }

    /**
     * Calculate ATM strike using delta-based selection (nearest to 0.5 delta).
     * <p>
     * This mirrors the live trading behavior in BaseStrategy.getATMStrikeByDelta().
     * For backtesting, we calculate delta using historical option prices.
     *
     * @param spotPrice current spot price
     * @param time current timestamp
     * @param context backtest context
     * @return strike with delta nearest to 0.5
     */
    private BigDecimal calculateATMStrikeByDelta(BigDecimal spotPrice, LocalDateTime time,
                                                   BacktestContext context) {
        BigDecimal interval = STRIKE_INTERVALS.getOrDefault(
                context.getInstrumentType().toUpperCase(), BigDecimal.valueOf(50));
        BigDecimal approximateATM = spotPrice.divide(interval, 0, RoundingMode.HALF_UP).multiply(interval);

        // Generate strikes to check (5 strikes around ATM, same as live trading)
        Map<BigDecimal, BigDecimal> deltaByStrike = new HashMap<>();

        for (int i = -5; i <= 5; i++) {
            BigDecimal strike = approximateATM.add(interval.multiply(BigDecimal.valueOf(i)));
            BigDecimal delta = calculateDeltaForStrike(spotPrice, strike, time, context);
            if (delta.compareTo(BigDecimal.ZERO) > 0) {
                deltaByStrike.put(strike, delta);
                log.trace("Strike {} delta: {}", strike, delta);
            }
        }

        if (deltaByStrike.isEmpty()) {
            log.warn("Could not calculate delta for any strike, using simple ATM: {}", approximateATM);
            return approximateATM;
        }

        // Find strike with delta nearest to 0.5
        BigDecimal bestStrike = approximateATM;
        BigDecimal minDiff = BigDecimal.valueOf(Double.MAX_VALUE);
        BigDecimal targetDelta = BigDecimal.valueOf(0.5);

        for (Map.Entry<BigDecimal, BigDecimal> entry : deltaByStrike.entrySet()) {
            BigDecimal diff = entry.getValue().subtract(targetDelta).abs();
            if (diff.compareTo(minDiff) < 0) {
                minDiff = diff;
                bestStrike = entry.getKey();
            }
        }

        log.debug("Selected strike {} with delta {} (diff from 0.5: {})",
                bestStrike, deltaByStrike.get(bestStrike), minDiff);
        return bestStrike;
    }

    /**
     * Calculate delta for a single strike using option prices.
     * Uses simplified Black-Scholes approximation suitable for backtesting.
     *
     * @param spotPrice current spot price
     * @param strike strike price
     * @param time current timestamp
     * @param context backtest context
     * @return call delta (0-1)
     */
    private BigDecimal calculateDeltaForStrike(BigDecimal spotPrice, BigDecimal strike,
                                                 LocalDateTime time, BacktestContext context) {
        try {
            // Fetch CE and PE prices for the strike
            BigDecimal cePrice = fetchOptionPrice(strike, "CE", time, context);
            BigDecimal pePrice = fetchOptionPrice(strike, "PE", time, context);

            if (cePrice.compareTo(BigDecimal.ZERO) <= 0 || pePrice.compareTo(BigDecimal.ZERO) <= 0) {
                return BigDecimal.ZERO;
            }

            // Calculate time to expiry in years
            double timeToExpiry = calculateTimeToExpiry(time.toLocalDate(), context.getExpiryDate());
            if (timeToExpiry <= 0) {
                // On expiry day, use intrinsic value approximation
                double moneyness = spotPrice.doubleValue() / strike.doubleValue();
                return BigDecimal.valueOf(moneyness > 1 ? 0.9 : moneyness < 1 ? 0.1 : 0.5);
            }

            // Implied volatility estimation from option prices
            double iv = estimateImpliedVolatility(spotPrice.doubleValue(), strike.doubleValue(),
                    cePrice.doubleValue(), timeToExpiry);

            // Calculate d1 for Black-Scholes delta
            double riskFreeRate = 0.065; // 6.5% risk-free rate
            double d1 = (Math.log(spotPrice.doubleValue() / strike.doubleValue()) +
                        (riskFreeRate + 0.5 * iv * iv) * timeToExpiry) /
                        (iv * Math.sqrt(timeToExpiry));

            // Delta = N(d1) for call options
            double delta = cumulativeNormalDistribution(d1);
            return BigDecimal.valueOf(delta).setScale(4, RoundingMode.HALF_UP);

        } catch (Exception e) {
            log.trace("Failed to calculate delta for strike {}: {}", strike, e.getMessage());
            return BigDecimal.ZERO;
        }
    }

    /**
     * Pre-fetch option candles for both CE and PE for the entire day.
     * This avoids repeated API calls during monitoring.
     *
     * @param strike ATM strike
     * @param context backtest context
     */
    private void preFetchOptionCandlesForDay(BigDecimal strike, BacktestContext context) {
        try {
            // Fetch CE candles for the day
            List<CandleData> ceCandles = historicalDataAdapter.fetchOptionCandles(
                    context.getInstrumentType(),
                    strike,
                    "CE",
                    context.getExpiryDate(),
                    context.getBacktestDate(),
                    currentRequest.getCandleInterval()
            );
            optionCandleCache.put(strike + "_CE", ceCandles);
            log.debug("Pre-fetched {} CE candles for strike {}", ceCandles.size(), strike);

            // Fetch PE candles for the day
            List<CandleData> peCandles = historicalDataAdapter.fetchOptionCandles(
                    context.getInstrumentType(),
                    strike,
                    "PE",
                    context.getExpiryDate(),
                    context.getBacktestDate(),
                    currentRequest.getCandleInterval()
            );
            optionCandleCache.put(strike + "_PE", peCandles);
            log.debug("Pre-fetched {} PE candles for strike {}", peCandles.size(), strike);

            optionDataPreFetched = true;
        } catch (Exception e) {
            log.warn("Failed to pre-fetch option candles for strike {}: {}", strike, e.getMessage());
            optionDataPreFetched = false;
        }
    }

    /**
     * Get option price from cached candles for a specific time.
     *
     * @param strike strike price
     * @param optionType CE or PE
     * @param time timestamp to get price at
     * @return option price or ZERO if not found
     */
    private BigDecimal getExitPriceFromCache(BigDecimal strike, String optionType, LocalDateTime time) {
        String cacheKey = strike + "_" + optionType;
        List<CandleData> candles = optionCandleCache.get(cacheKey);

        if (candles == null || candles.isEmpty()) {
            return BigDecimal.ZERO;
        }

        // Find candle at or just before the requested time
        CandleData matchingCandle = null;
        for (CandleData candle : candles) {
            if (candle.getTimestamp().isAfter(time)) {
                break;
            }
            matchingCandle = candle;
        }

        return matchingCandle != null ? matchingCandle.getClose() : BigDecimal.ZERO;
    }

    /**
     * Check exit conditions using cached option data for both legs.
     * <p>
     * This mirrors PositionMonitorV2 evaluation logic but uses cached historical data.
     *
     * @param candle current spot candle
     * @param context backtest context
     */
    private void checkExitConditionsWithOptionData(CandleData candle, BacktestContext context) {
        LocalDateTime currentTime = candle.getTimestamp();
        BigDecimal strike = context.getCurrentStrike();

        // Get current CE and PE prices from cached data
        BigDecimal cePrice = getExitPriceFromCache(strike, "CE", currentTime);
        BigDecimal pePrice = getExitPriceFromCache(strike, "PE", currentTime);

        // Fallback to estimation if cache miss
        if (cePrice.compareTo(BigDecimal.ZERO) <= 0) {
            cePrice = fetchOptionPrice(strike, "CE", currentTime, context);
        }
        if (pePrice.compareTo(BigDecimal.ZERO) <= 0) {
            pePrice = fetchOptionPrice(strike, "PE", currentTime, context);
        }

        BigDecimal currentPremium = cePrice.add(pePrice);

        // Rebuild context if positions have changed
        exitStrategyEvaluator.rebuildContextBuilder();

        // Evaluate using unified exit strategy framework (same as live trading)
        ExitResult result = exitStrategyEvaluator.evaluateCandle(candle, currentPremium);

        if (result.requiresAction()) {
            String exitReason = result.getExitReason();
            log.debug("Exit triggered: reason={}, currentPremium={} (CE={}, PE={})",
                exitReason, currentPremium, cePrice, pePrice);

            // Close all positions with actual option exit prices and calculate charges
            int quantity = context.getLotSize() * context.getLots();
            BigDecimal totalExitCharges = BigDecimal.ZERO;

            Map<String, BigDecimal> exitPrices = new HashMap<>();
            for (Map.Entry<String, SimulatedPosition> entry : context.getOpenPositions().entrySet()) {
                String symbol = entry.getKey();
                SimulatedPosition position = entry.getValue();

                BigDecimal exitPrice = "CE".equals(position.getOptionType()) ? cePrice : pePrice;
                exitPrices.put(symbol, exitPrice);

                // Calculate exit charges (BUY to close SELL position)
                BigDecimal turnover = exitPrice.multiply(BigDecimal.valueOf(quantity));
                OrderCharges charges = chargeCalculator.calculateCharges(
                        ZerodhaChargeCalculator.OrderType.OPTIONS,
                        ZerodhaChargeCalculator.TransactionType.BUY,
                        turnover,
                        BigDecimal.valueOf(quantity)
                );
                totalExitCharges = totalExitCharges.add(charges.getTotalCharges());
            }

            // Store exit charges
            context.setExitCharges(totalExitCharges);

            context.closeAllPositions(exitPrices, currentTime, exitReason);

            // Clear cache after exit
            optionCandleCache.clear();
            optionDataPreFetched = false;

            // Request restart on SL hit (strategy can be re-entered at next 5-min candle)
            if (exitReason != null && exitReason.contains("STOPLOSS")) {
                context.requestRestart();
            }
        }
    }

    /**
     * Calculate ATM strike price (fallback method when delta calculation fails).
     */
    private BigDecimal calculateATMStrike(BigDecimal spotPrice, BacktestContext context) {
        BigDecimal interval = STRIKE_INTERVALS.getOrDefault(
                context.getInstrumentType().toUpperCase(), BigDecimal.valueOf(50));
        return spotPrice.divide(interval, 0, RoundingMode.HALF_UP).multiply(interval);
    }

    /**
     * Estimate option premium (simplified model for backtesting).
     * In production, would use actual historical option data.
     */
    private BigDecimal estimateOptionPremium(BigDecimal spotPrice, BigDecimal strike) {
        // Simplified: ATM options typically have premium of ~1% of spot
        return spotPrice.multiply(BigDecimal.valueOf(0.01)).setScale(2, RoundingMode.HALF_UP);
    }

    /**
     * Estimate current combined premium based on spot movement.
     * Tries to fetch actual option prices first, falls back to estimation.
     */
    private BigDecimal estimateCurrentPremium(BigDecimal currentSpot, BacktestContext context) {
        BigDecimal strike = context.getCurrentStrike();
        BigDecimal entryPremium = context.getEntryPremium();
        LocalDateTime currentTime = context.getCurrentTime();

        // Try to fetch actual option prices
        try {
            BigDecimal cePremium = fetchOptionPrice(strike, "CE", currentTime, context);
            BigDecimal pePremium = fetchOptionPrice(strike, "PE", currentTime, context);

            // If we got valid prices that differ from entry, use them
            if (cePremium.compareTo(BigDecimal.ZERO) > 0 && pePremium.compareTo(BigDecimal.ZERO) > 0) {
                BigDecimal combined = cePremium.add(pePremium);
                log.trace("Using actual option prices: CE={}, PE={}, combined={}",
                        cePremium, pePremium, combined);
                return combined;
            }
        } catch (Exception e) {
            log.trace("Could not fetch actual prices, using estimation: {}", e.getMessage());
        }

        // Fallback: estimate premium based on spot movement from strike
        BigDecimal deviation = currentSpot.subtract(strike).abs();
        BigDecimal devPct = deviation.divide(strike, 4, RoundingMode.HALF_UP);

        // Simple adjustment: premium increases with deviation
        return entryPremium.multiply(BigDecimal.ONE.add(devPct.multiply(BigDecimal.valueOf(2))))
                .setScale(2, RoundingMode.HALF_UP);
    }

    /**
     * Generate trading symbol for option using context expiry information.
     */
    private String generateSymbol(BigDecimal strike, String optionType, BacktestContext context) {
        return historicalDataAdapter.generateOptionSymbol(
                context.getInstrumentType(),
                strike,
                optionType,
                context.getExpiryDate()
        );
    }

    /**
     * Fetch actual historical option price at the given time.
     * Falls back to estimation if historical data is not available.
     *
     * @param strike strike price
     * @param optionType CE or PE
     * @param time the timestamp to get price at
     * @param context backtest context
     * @return option premium at the given time
     */
    private BigDecimal fetchOptionPrice(BigDecimal strike, String optionType,
                                         LocalDateTime time, BacktestContext context) {
        try {
            // Use minimum timeframe (minute) for highest precision
            List<CandleData> optionCandles = historicalDataAdapter.fetchOptionCandles(
                    context.getInstrumentType(),
                    strike,
                    optionType,
                    context.getExpiryDate(),
                    context.getBacktestDate(),
                    currentRequest.getCandleInterval()
            );

            if (!optionCandles.isEmpty()) {
                // Find the candle at or just before the requested time
                CandleData matchingCandle = findCandleAtTime(optionCandles, time);
                if (matchingCandle != null) {
                    log.debug("Found actual option price for {} {} at {}: {}",
                            strike, optionType, time, matchingCandle.getClose());
                    return matchingCandle.getClose();
                }
            }

            log.debug("No historical option data found for {} {}, using estimation", strike, optionType);
        } catch (Exception e) {
            log.warn("Error fetching option price for {} {}: {}, using estimation",
                    strike, optionType, e.getMessage());
        }

        // Fallback to estimation if historical data not available
        return estimateOptionPremium(context.getCurrentStrike(), strike);
    }

    /**
     * Find candle at or just before the specified time.
     */
    private CandleData findCandleAtTime(List<CandleData> candles, LocalDateTime time) {
        CandleData result = null;
        for (CandleData candle : candles) {
            if (candle.getTimestamp().isAfter(time)) {
                break;
            }
            result = candle;
        }
        return result;
    }

    /**
     * Calculate time to expiry in years.
     *
     * @param currentDate current date
     * @param expiryDate expiry date
     * @return time to expiry in years
     */
    private double calculateTimeToExpiry(java.time.LocalDate currentDate, java.time.LocalDate expiryDate) {
        if (currentDate == null || expiryDate == null) {
            return 0.0;
        }
        long daysToExpiry = java.time.temporal.ChronoUnit.DAYS.between(currentDate, expiryDate);
        if (daysToExpiry <= 0) {
            // On expiry day, use fraction of day remaining
            return 0.5 / 365.0; // Half day
        }
        return daysToExpiry / 365.0;
    }

    /**
     * Estimate implied volatility from option price using Newton-Raphson method.
     * Simplified implementation for backtesting purposes.
     *
     * @param spot spot price
     * @param strike strike price
     * @param optionPrice call option price
     * @param timeToExpiry time to expiry in years
     * @return estimated implied volatility
     */
    private double estimateImpliedVolatility(double spot, double strike, double optionPrice, double timeToExpiry) {
        if (timeToExpiry <= 0 || optionPrice <= 0) {
            return 0.20; // Default 20% IV
        }

        double riskFreeRate = 0.065;
        double iv = 0.20; // Initial guess

        // Newton-Raphson iteration
        for (int i = 0; i < 20; i++) {
            double d1 = (Math.log(spot / strike) + (riskFreeRate + 0.5 * iv * iv) * timeToExpiry) /
                        (iv * Math.sqrt(timeToExpiry));
            double d2 = d1 - iv * Math.sqrt(timeToExpiry);

            double callPrice = spot * cumulativeNormalDistribution(d1) -
                               strike * Math.exp(-riskFreeRate * timeToExpiry) * cumulativeNormalDistribution(d2);

            double vega = spot * Math.sqrt(timeToExpiry) * normalDensity(d1);

            if (vega < 1e-10) {
                break;
            }

            double diff = callPrice - optionPrice;
            if (Math.abs(diff) < 0.01) {
                break;
            }

            iv = iv - diff / vega;
            if (iv <= 0.01) {
                iv = 0.01;
            }
            if (iv > 3.0) {
                iv = 3.0;
            }
        }

        return iv;
    }

    /**
     * Cumulative normal distribution function.
     * Approximation using Abramowitz and Stegun formula.
     *
     * @param x input value
     * @return N(x)
     */
    private double cumulativeNormalDistribution(double x) {
        double a1 = 0.254829592;
        double a2 = -0.284496736;
        double a3 = 1.421413741;
        double a4 = -1.453152027;
        double a5 = 1.061405429;
        double p = 0.3275911;

        int sign = 1;
        if (x < 0) {
            sign = -1;
        }
        x = Math.abs(x) / Math.sqrt(2);

        double t = 1.0 / (1.0 + p * x);
        double y = 1.0 - (((((a5 * t + a4) * t) + a3) * t + a2) * t + a1) * t * Math.exp(-x * x);

        return 0.5 * (1.0 + sign * y);
    }

    /**
     * Standard normal probability density function.
     *
     * @param x input value
     * @return phi(x)
     */
    private double normalDensity(double x) {
        return Math.exp(-0.5 * x * x) / Math.sqrt(2 * Math.PI);
    }
}

