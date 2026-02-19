package com.tradingbot.service.strategy;

import com.tradingbot.dto.OrderRequest;
import com.tradingbot.dto.OrderResponse;
import com.tradingbot.model.StrategyExecution;
import com.tradingbot.service.StrategyService;
import com.tradingbot.service.TradingService;
import com.tradingbot.service.UnifiedTradingService;
import com.tradingbot.service.strategy.monitoring.PositionMonitorV2;
import com.tradingbot.service.strategy.monitoring.WebSocketService;
import com.tradingbot.util.StrategyConstants;
import com.zerodhatech.kiteconnect.kitehttp.exceptions.KiteException;
import com.zerodhatech.models.Instrument;
import com.zerodhatech.models.LTPQuote;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import static com.tradingbot.service.TradingConstants.*;

/**
 * Handler for straddle leg replacement operations.
 * <p>
 * Extracts leg replacement logic from SellATMStraddleStrategy for better maintainability.
 * Used when a profitable leg is exited and needs to be replaced with a new leg
 * matching the premium of the loss-making leg.
 * <p>
 * HFT Optimizations:
 * <ul>
 *   <li>Uses batch LTP fetches for efficiency</li>
 *   <li>Early termination when exact premium match found</li>
 *   <li>Pre-built instrument index for fast lookups</li>
 * </ul>
 *
 * @since 4.1
 */
@Slf4j
@Component
public class LegReplacementHandler {

    private final TradingService tradingService;
    private final UnifiedTradingService unifiedTradingService;
    private final StrategyService strategyService;
    private final WebSocketService webSocketService;

    // Maximum candidates to evaluate for replacement (avoid API overload)
    private static final int MAX_CANDIDATES = 500;
    // Threshold for "exact" premium match
    private static final double EXACT_MATCH_THRESHOLD = 0.5;
    // Number of strikes to check on each side of exited leg
    private static final int STRIKE_RANGE = 10;
    // Default strike interval for NIFTY (50) and BANKNIFTY (100)
    private static final double DEFAULT_STRIKE_INTERVAL = 50.0;

    public LegReplacementHandler(TradingService tradingService,
                                 UnifiedTradingService unifiedTradingService,
                                 @Lazy StrategyService strategyService,
                                 WebSocketService webSocketService) {
        this.tradingService = tradingService;
        this.unifiedTradingService = unifiedTradingService;
        this.strategyService = strategyService;
        this.webSocketService = webSocketService;
    }

    /**
     * Find an option instrument with premium closest to the target.
     * <p>
     * Selection constraints:
     * <ul>
     *   <li>Must be of specified option type (CE or PE)</li>
     *   <li>Must be within ±10 strikes of the exited leg</li>
     *   <li>Must not be the same as the exited leg</li>
     *   <li>Must have LTP greater than the exited leg's LTP</li>
     *   <li>Premium should be closest to target premium</li>
     * </ul>
     *
     * @param instrumentIndex  pre-built instrument index
     * @param optionType       option type (CE or PE)
     * @param targetPremium    target premium to match
     * @param maxPremiumDiff   maximum acceptable premium difference
     * @param exitedLegSymbol  symbol of exited leg (excluded)
     * @param exitedLegLtp     LTP of exited leg (replacement must exceed this)
     * @return matching instrument or null if none found
     */
    public Instrument findInstrumentByTargetPremium(
            Map<?, Instrument> instrumentIndex,
            String optionType,
            double targetPremium,
            double maxPremiumDiff,
            String exitedLegSymbol,
            double exitedLegLtp) {

        log.info("Finding {} instrument with target premium {} (max diff: {}), excluding: {}, minLtp: {}",
                optionType, targetPremium, maxPremiumDiff, exitedLegSymbol, exitedLegLtp);

        // Find the exited leg's strike to determine the search range
        double exitedLegStrike = findExitedLegStrike(instrumentIndex, exitedLegSymbol);
        if (exitedLegStrike < 0) {
            log.warn("Could not determine exited leg strike, falling back to full search");
            // Fallback: collect all candidates of the same option type
            exitedLegStrike = 0; // Will use wide range
        }

        List<Instrument> candidates = collectCandidates(instrumentIndex, optionType, exitedLegSymbol, exitedLegStrike);

        if (candidates.isEmpty()) {
            log.warn("No {} instruments found within ±{} strikes of exited leg", optionType, STRIKE_RANGE);
            return null;
        }

        log.debug("Found {} candidate {} instruments within strike range", candidates.size(), optionType);

        Map<String, LTPQuote> ltpMap = fetchLTPsForCandidates(candidates);
        if (ltpMap == null || ltpMap.isEmpty()) {
            log.warn("No LTP data received for candidate instruments");
            return null;
        }

        return findBestMatch(candidates, ltpMap, targetPremium, exitedLegLtp);
    }

    /**
     * Place a replacement leg order and add it to the position monitor.
     *
     * @param executionId         execution ID
     * @param instrumentIndex     instrument index for lookups
     * @param exitedLegSymbol     symbol of exited leg
     * @param legType             type of leg to add (CE or PE)
     * @param targetPremium       target premium
     * @param lossMakingLegSymbol symbol of loss-making leg (reference)
     * @param quantity            order quantity
     * @param monitor             PositionMonitor to update
     * @param exitedLegLtp        LTP of exited leg
     */
    public void placeReplacementLegOrder(String executionId,
                                         Map<?, Instrument> instrumentIndex,
                                         String exitedLegSymbol,
                                         String legType,
                                         double targetPremium,
                                         String lossMakingLegSymbol,
                                         int quantity,
                                         PositionMonitorV2 monitor,
                                         double exitedLegLtp) {

        String tradingMode = getTradingMode();
        log.info("[{}] Placing replacement {} leg for execution {}: exitedLeg={}, exitedLegLtp={}, " +
                        "targetPremium={}, referenceLeg={}",
                tradingMode, legType, executionId, exitedLegSymbol, exitedLegLtp, targetPremium, lossMakingLegSymbol);

        try {
            double maxPremiumDiff = targetPremium * 0.20;

            Instrument replacementInstrument = findInstrumentByTargetPremium(
                    instrumentIndex, legType, targetPremium, maxPremiumDiff, exitedLegSymbol, exitedLegLtp);

            if (replacementInstrument == null) {
                log.error("[{}] Could not find replacement {} instrument for execution {}",
                        tradingMode, legType, executionId);
                monitor.signalLegReplacementFailed("No suitable replacement instrument found");
                return;
            }

            log.info("[{}] Found replacement instrument: {}", tradingMode, replacementInstrument.tradingsymbol);

            OrderResponse orderResponse;
            try {
                orderResponse = placeSellOrder(replacementInstrument, quantity);
            } catch (KiteException | java.io.IOException e) {
                log.error("[{}] Failed to place replacement order: {}", tradingMode, e.getMessage());
                return;
            }

            if (!StrategyConstants.ORDER_STATUS_SUCCESS.equals(orderResponse.getStatus())) {
                log.error("[{}] Replacement order failed: {}", tradingMode, orderResponse.getMessage());
                return;
            }

            String newOrderId = orderResponse.getOrderId();
            double fillPrice = getFillPrice(replacementInstrument, targetPremium);

            log.info("[{}] Replacement order placed: orderId={}, fillPrice={}",
                    tradingMode, newOrderId, fillPrice);

            // Add to monitor
            monitor.addReplacementLeg(
                    newOrderId,
                    replacementInstrument.tradingsymbol,
                    replacementInstrument.instrument_token,
                    fillPrice,
                    quantity,
                    legType
            );

            // Subscribe to WebSocket
            webSocketService.addInstrumentToMonitoring(executionId, replacementInstrument.instrument_token);

            // Update execution state
            updateStrategyExecutionWithNewLeg(executionId, newOrderId, replacementInstrument, fillPrice, quantity, legType);

            log.info("[{}] Replacement leg added: symbol={}, newEntryPremium={}, targetLevel={}, slLevel={}",
                    tradingMode, replacementInstrument.tradingsymbol,
                    monitor.getEntryPremium(), monitor.getTargetPremiumLevel(), monitor.getStopLossPremiumLevel());

        } catch (Exception e) {
            log.error("[{}] Unexpected error during leg replacement for {}: {}",
                    tradingMode, executionId, e.getMessage(), e);
        }
    }

    // ==================== Private Helper Methods ====================

    /**
     * Collect candidate instruments within ±STRIKE_RANGE strikes of the exited leg.
     * This limits the search space to instruments close to the exited leg's strike.
     *
     * @param instrumentIndex  pre-built instrument index
     * @param optionType       option type (CE or PE)
     * @param exitedLegSymbol  symbol of exited leg (excluded)
     * @param exitedLegStrike  strike of the exited leg
     * @return list of candidate instruments within strike range
     */
    private List<Instrument> collectCandidates(Map<?, Instrument> instrumentIndex,
                                               String optionType,
                                               String exitedLegSymbol,
                                               double exitedLegStrike) {
        List<Instrument> candidates = new ArrayList<>();

        // Determine strike interval based on underlying (NIFTY=50, BANKNIFTY=100)
        double strikeInterval = getStrikeIntervalFromSymbol(exitedLegSymbol);

        // Calculate min and max strike range (±STRIKE_RANGE legs)
        double minStrike = exitedLegStrike - (STRIKE_RANGE * strikeInterval);
        double maxStrike = exitedLegStrike + (STRIKE_RANGE * strikeInterval);

        log.info("Collecting {} candidates within strike range [{}, {}] (exitedStrike: {}, interval: {})",
                optionType, minStrike, maxStrike, exitedLegStrike, strikeInterval);

        for (Map.Entry<?, Instrument> entry : instrumentIndex.entrySet()) {
            Instrument inst = entry.getValue();
            if (!optionType.equals(inst.instrument_type)) {
                continue;
            }

            // Exclude the recently closed leg
            if (Objects.equals(inst.tradingsymbol, exitedLegSymbol)) {
                log.debug("Excluding exited leg: {}", exitedLegSymbol);
                continue;
            }

            // Check if instrument strike is within range
            try {
                double instStrike = Double.parseDouble(inst.strike);
                if (instStrike >= minStrike && instStrike <= maxStrike) {
                    candidates.add(inst);
                }
            } catch (NumberFormatException e) {
                log.trace("Skipping instrument with invalid strike: {}", inst.tradingsymbol);
            }
        }

        log.debug("Found {} {} candidates within ±{} strikes of {}",
                candidates.size(), optionType, STRIKE_RANGE, exitedLegStrike);
        return candidates;
    }

    /**
     * Get strike interval based on the trading symbol (NIFTY=50, BANKNIFTY=100)
     */
    private double getStrikeIntervalFromSymbol(String tradingSymbol) {
        if (tradingSymbol == null || tradingSymbol.isEmpty()) {
            return DEFAULT_STRIKE_INTERVAL;
        }
        String upperSymbol = tradingSymbol.toUpperCase();
        if (upperSymbol.startsWith("BANKNIFTY")) {
            return 100.0;
        } else if (upperSymbol.startsWith("NIFTY")) {
            return 50.0;
        }
        return DEFAULT_STRIKE_INTERVAL;
    }

    /**
     * Find the strike price of the exited leg from the instrument index.
     *
     * @param instrumentIndex  pre-built instrument index
     * @param exitedLegSymbol  symbol of exited leg
     * @return strike price of the exited leg, or -1 if not found
     */
    private double findExitedLegStrike(Map<?, Instrument> instrumentIndex, String exitedLegSymbol) {
        for (Map.Entry<?, Instrument> entry : instrumentIndex.entrySet()) {
            Instrument inst = entry.getValue();
            if (Objects.equals(inst.tradingsymbol, exitedLegSymbol)) {
                try {
                    return Double.parseDouble(inst.strike);
                } catch (NumberFormatException e) {
                    log.warn("Invalid strike format for exited leg: {}", exitedLegSymbol);
                    return -1;
                }
            }
        }
        log.warn("Exited leg {} not found in instrument index", exitedLegSymbol);
        return -1;
    }

    private Map<String, LTPQuote> fetchLTPsForCandidates(List<Instrument> candidates) {
        int count = Math.min(candidates.size(), MAX_CANDIDATES);
        String[] identifiers = new String[count];

        for (int i = 0; i < count; i++) {
            identifiers[i] = "NFO:" + candidates.get(i).tradingsymbol;
        }

        try {
            return tradingService.getLTP(identifiers);
        } catch (Exception | KiteException e) {
            log.error("Failed to fetch LTPs: {}", e.getMessage(), e);
            return null;
        }
    }

    private Instrument findBestMatch(List<Instrument> candidates,
                                     Map<String, LTPQuote> ltpMap,
                                     double targetPremium,
                                     double exitedLegLtp) {
        Instrument bestMatch = null;
        double bestDifference = Double.MAX_VALUE;
        double bestMatchLtp = 0.0;

        int count = Math.min(candidates.size(), MAX_CANDIDATES);

        for (int i = 0; i < count; i++) {
            Instrument candidate = candidates.get(i);
            String identifier = "NFO:" + candidate.tradingsymbol;
            LTPQuote ltp = ltpMap.get(identifier);

            if (ltp == null || ltp.lastPrice <= 0) {
                continue;
            }

            double currentPremium = ltp.lastPrice;

            // Skip if LTP not greater than exited leg
            if (exitedLegLtp > 0 && currentPremium <= exitedLegLtp) {
//                log.debug("Skipping {} - LTP {} <= exitedLegLtp {}",
//                        candidate.tradingsymbol, currentPremium, exitedLegLtp);
                continue;
            }
            log.debug("tradingsymbol {} - LTP {}, exitedLegLtp {}",
                    candidate.tradingsymbol, currentPremium, exitedLegLtp);

            double difference = Math.abs(currentPremium - targetPremium);

            if (difference < bestDifference) {
                bestDifference = difference;
                bestMatch = candidate;
                bestMatchLtp = currentPremium;

                // Early termination for exact match
                if (difference < EXACT_MATCH_THRESHOLD) {
                    log.info("Found exact match {} with premium {}",
                            candidate.tradingsymbol, currentPremium);
                    break;
                }
            }
        }

        if (bestMatch != null) {
            log.info("Best match: {} with LTP {} (diff: {})",
                    bestMatch.tradingsymbol, bestMatchLtp, bestDifference);
        }

        return bestMatch;
    }

    private OrderResponse placeSellOrder(Instrument instrument, int quantity) throws KiteException, java.io.IOException {
        OrderRequest sellOrder = new OrderRequest();
        sellOrder.setTradingSymbol(instrument.tradingsymbol);
        sellOrder.setExchange(EXCHANGE_NFO);
        sellOrder.setTransactionType(StrategyConstants.TRANSACTION_SELL);
        sellOrder.setQuantity(quantity);
        sellOrder.setProduct(PRODUCT_MIS);
        sellOrder.setOrderType(ORDER_TYPE_MARKET);
        sellOrder.setValidity(VALIDITY_DAY);

        return unifiedTradingService.placeOrder(sellOrder);
    }

    private double getFillPrice(Instrument instrument, double defaultPrice) {
        try {
            String identifier = "NFO:" + instrument.tradingsymbol;
            Map<String, LTPQuote> ltpMap = tradingService.getLTP(new String[]{identifier});
            if (ltpMap != null && ltpMap.get(identifier) != null && ltpMap.get(identifier).lastPrice > 0) {
                return ltpMap.get(identifier).lastPrice;
            }
        } catch (Exception | KiteException e) {
            log.warn("Could not fetch LTP for fill price, using default: {}", e.getMessage());
        }
        return defaultPrice;
    }

    private void updateStrategyExecutionWithNewLeg(String executionId,
                                                   String orderId,
                                                   Instrument instrument,
                                                   double fillPrice,
                                                   int quantity,
                                                   String legType) {
        try {
            StrategyExecution execution = strategyService.getStrategy(executionId);
            if (execution != null && execution.getOrderLegs() != null) {
                StrategyExecution.OrderLeg newLeg = StrategyExecution.OrderLeg.builder()
                        .orderId(orderId)
                        .tradingSymbol(instrument.tradingsymbol)
                        .optionType(legType)
                        .entryPrice(fillPrice)
                        .quantity(quantity)
                        .entryTransactionType(StrategyConstants.TRANSACTION_SELL)
                        .entryTimestamp(System.currentTimeMillis())
                        .lifecycleState(StrategyExecution.LegLifecycleState.OPEN)
                        .build();

                execution.getOrderLegs().add(newLeg);
                log.info("Strategy execution updated with replacement leg");
            }
        } catch (Exception e) {
            log.warn("Could not update execution state: {}", e.getMessage());
        }
    }

    private String getTradingMode() {
        return unifiedTradingService.isPaperTradingEnabled()
                ? StrategyConstants.TRADING_MODE_PAPER
                : StrategyConstants.TRADING_MODE_LIVE;
    }
}





