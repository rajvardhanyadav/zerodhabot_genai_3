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
    private static final int MAX_CANDIDATES = 50;
    // Threshold for "exact" premium match
    private static final double EXACT_MATCH_THRESHOLD = 0.5;

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
     *   <li>Must not be the same as the exited leg</li>
     *   <li>Must have LTP greater than the exited leg's LTP</li>
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

        List<Instrument> candidates = collectCandidates(instrumentIndex, optionType, exitedLegSymbol);

        if (candidates.isEmpty()) {
            log.warn("No {} instruments found in index", optionType);
            return null;
        }

        log.debug("Found {} candidate {} instruments", candidates.size(), optionType);

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

    private List<Instrument> collectCandidates(Map<?, Instrument> instrumentIndex,
                                               String optionType,
                                               String exitedLegSymbol) {
        List<Instrument> candidates = new ArrayList<>();

        for (Map.Entry<?, Instrument> entry : instrumentIndex.entrySet()) {
            Instrument inst = entry.getValue();
            if (optionType.equals(inst.instrument_type)) {
                if (Objects.equals(inst.tradingsymbol, exitedLegSymbol)) {
                    log.debug("Excluding exited leg: {}", exitedLegSymbol);
                    continue;
                }
                candidates.add(inst);
            }
        }
        return candidates;
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
                log.debug("Skipping {} - LTP {} <= exitedLegLtp {}",
                        candidate.tradingsymbol, currentPremium, exitedLegLtp);
                continue;
            }

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





