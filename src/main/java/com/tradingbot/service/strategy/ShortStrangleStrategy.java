package com.tradingbot.service.strategy;

import com.tradingbot.config.StrategyConfig;
import com.tradingbot.config.VolatilityConfig;
import com.tradingbot.dto.BasketOrderRequest;
import com.tradingbot.dto.BasketOrderResponse;
import com.tradingbot.dto.OrderRequest;
import com.tradingbot.dto.OrderResponse;
import com.tradingbot.dto.StrategyExecutionResponse;
import com.tradingbot.dto.StrategyRequest;
import com.tradingbot.model.SlTargetMode;
import com.tradingbot.service.StrategyService;
import com.tradingbot.service.TradingService;
import com.tradingbot.service.UnifiedTradingService;
import com.tradingbot.service.greeks.DeltaCacheService;
import com.tradingbot.service.strategy.monitoring.WebSocketService;
import com.tradingbot.util.CurrentUserContext;
import com.tradingbot.util.StrategyConstants;
import com.zerodhatech.kiteconnect.kitehttp.exceptions.KiteException;
import com.zerodhatech.models.Instrument;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static com.tradingbot.service.TradingConstants.*;

/**
 * Short Strangle Strategy with Hedges - 4-leg options strategy.
 * <p>
 * <b>Sell OTM CE (~0.4Δ) + Sell OTM PE (~0.4Δ) + Buy OTM CE hedge (~0.1Δ) + Buy OTM PE hedge (~0.1Δ)</b>
 * <p>
 * This strategy profits from low volatility and time decay with defined risk via hedge legs.
 * The sell legs generate premium income while hedge legs cap maximum loss.
 * <p>
 * Strategy flow:
 * <ol>
 *   <li>Fetch spot price and compute delta for available strikes</li>
 *   <li>Select OTM CE and PE strikes nearest to 0.4 delta for sell legs</li>
 *   <li>Select far OTM CE and PE strikes nearest to 0.1 delta for hedge (buy) legs</li>
 *   <li>Place 4-leg basket order (2 SELL + 2 BUY)</li>
 *   <li>Set up PositionMonitorV2 with all 4 legs (SHORT direction)</li>
 *   <li>Monitor using same exit logic as SELL_ATM_STRADDLE</li>
 * </ol>
 * <p>
 * HFT Optimizations:
 * <ul>
 *   <li>O(1) instrument lookup via pre-built HashMap index</li>
 *   <li>Parallel order placement for all legs</li>
 *   <li>High-priority dedicated thread pool for exit orders</li>
 *   <li>Pre-computed string constants to avoid concatenation</li>
 * </ul>
 *
 * @since 4.3
 */
@Slf4j
@Component
public class ShortStrangleStrategy extends BaseStrategy {

    private final WebSocketService webSocketService;
    private final StrategyConfig strategyConfig;
    private final StrategyService strategyService;
    private final VolatilityFilterService volatilityFilterService;
    private final VolatilityConfig volatilityConfig;
    private final StraddleExitHandler exitHandler;
    private final MonitoringSetupHelper monitoringSetupHelper;

    // ==================== HFT Thread Pool Configuration ====================
    private static final int HFT_THREAD_POOL_SIZE = 8;
    private static final AtomicInteger THREAD_COUNTER = new AtomicInteger(0);
    private static final ThreadFactory HFT_THREAD_FACTORY = r -> {
        Thread t = new Thread(r, "hft-strangle-exit-" + THREAD_COUNTER.incrementAndGet());
        t.setDaemon(true);
        t.setPriority(Thread.MAX_PRIORITY);
        return t;
    };
    private static final ExecutorService EXIT_ORDER_EXECUTOR = Executors.newFixedThreadPool(
            HFT_THREAD_POOL_SIZE, HFT_THREAD_FACTORY);

    // Pre-computed constants for option type annotations
    private static final String SELL_CALL_TYPE = StrategyConstants.OPTION_TYPE_CALL + "_SHORT";
    private static final String SELL_PUT_TYPE = StrategyConstants.OPTION_TYPE_PUT + "_SHORT";
    private static final String HEDGE_CALL_TYPE = StrategyConstants.OPTION_TYPE_CALL + "_HEDGE";
    private static final String HEDGE_PUT_TYPE = StrategyConstants.OPTION_TYPE_PUT + "_HEDGE";

    // Leg type constants for basket order
    private static final String LEG_TYPE_SELL_CALL = "SellCall";
    private static final String LEG_TYPE_SELL_PUT = "SellPut";
    private static final String LEG_TYPE_HEDGE_CALL = "HedgeCall";
    private static final String LEG_TYPE_HEDGE_PUT = "HedgePut";

    // ==================== HFT Instrument Index ====================
    private record InstrumentKey(long strikeX100, String optionType) {
        static InstrumentKey of(double strike, String optionType) {
            return new InstrumentKey((long) (strike * 100), optionType.intern());
        }
    }

    private final Map<InstrumentKey, Instrument> instrumentIndex = new HashMap<>(256);

    public ShortStrangleStrategy(TradingService tradingService,
                                  UnifiedTradingService unifiedTradingService,
                                  Map<String, Integer> lotSizeCache,
                                  WebSocketService webSocketService,
                                  StrategyConfig strategyConfig,
                                  @Lazy StrategyService strategyService,
                                  DeltaCacheService deltaCacheService,
                                  VolatilityFilterService volatilityFilterService,
                                  VolatilityConfig volatilityConfig,
                                  StraddleExitHandler exitHandler,
                                  MonitoringSetupHelper monitoringSetupHelper) {
        super(tradingService, unifiedTradingService, lotSizeCache, deltaCacheService);
        this.webSocketService = webSocketService;
        this.strategyConfig = strategyConfig;
        this.strategyService = strategyService;
        this.volatilityFilterService = volatilityFilterService;
        this.volatilityConfig = volatilityConfig;
        this.exitHandler = exitHandler;
        this.monitoringSetupHelper = monitoringSetupHelper;
    }

    @Override
    public StrategyExecutionResponse execute(StrategyRequest request, String executionId,
                                             StrategyCompletionCallback completionCallback)
            throws KiteException, IOException {

        // Cache request parameters locally for HFT
        final String instrumentType = request.getInstrumentType();
        final String expiry = request.getExpiry();
        final double stopLossPoints = resolveStopLossPoints(request);
        final double targetPoints = resolveTargetPoints(request);
        final double targetDecayPct = resolveTargetDecayPct(request);
        final double stopLossExpansionPct = resolveStopLossExpansionPct(request);
        final SlTargetMode slTargetMode = resolveSlTargetMode(request);
        final String tradingMode = getTradingMode();
        final double sellDelta = strategyConfig.getShortStrangleSellDelta();
        final double hedgeDelta = strategyConfig.getShortStrangleHedgeDelta();

        logExecutionStart(tradingMode, instrumentType, stopLossPoints, targetPoints,
                slTargetMode, targetDecayPct, stopLossExpansionPct, sellDelta, hedgeDelta);

        // Get spot price and instruments
        final double spotPrice = getCurrentSpotPrice(instrumentType);
        log.info("Current spot price: {}", spotPrice);

        final List<Instrument> instruments = getOptionInstruments(instrumentType, expiry);
        log.info("Found {} option instruments for {}", instruments.size(), instrumentType);

        // Build instrument index for O(1) lookups
        buildInstrumentIndex(instruments);

        // Get expiry date for delta calculation
        final Date expiryDate = !instruments.isEmpty() ? instruments.get(0).expiry : null;
        if (expiryDate == null) {
            throw new RuntimeException("Could not determine expiry date from instruments");
        }

        // ==================== STRIKE SELECTION BY DELTA ====================
        // Sell legs: ~0.4 delta (OTM)
        final double sellCEStrike = getStrikeByDelta(spotPrice, instrumentType, expiryDate, sellDelta, OPTION_TYPE_CE);
        final double sellPEStrike = getStrikeByDelta(spotPrice, instrumentType, expiryDate, sellDelta, OPTION_TYPE_PE);
        log.info("Sell leg strikes - CE: {} (target Δ={}), PE: {} (target Δ={})",
                sellCEStrike, sellDelta, sellPEStrike, sellDelta);

        // Hedge legs: ~0.1 delta (far OTM)
        final double hedgeCEStrike = getStrikeByDelta(spotPrice, instrumentType, expiryDate, hedgeDelta, OPTION_TYPE_CE);
        final double hedgePEStrike = getStrikeByDelta(spotPrice, instrumentType, expiryDate, hedgeDelta, OPTION_TYPE_PE);
        log.info("Hedge leg strikes - CE: {} (target Δ={}), PE: {} (target Δ={})",
                hedgeCEStrike, hedgeDelta, hedgePEStrike, hedgeDelta);

        // Validate sell CE is OTM (strike > spot) and sell PE is OTM (strike < spot)
        if (sellCEStrike <= spotPrice) {
            log.warn("Sell CE strike {} is ATM/ITM (spot={}). Proceeding with delta-selected strike.", sellCEStrike, spotPrice);
        }
        if (sellPEStrike >= spotPrice) {
            log.warn("Sell PE strike {} is ATM/ITM (spot={}). Proceeding with delta-selected strike.", sellPEStrike, spotPrice);
        }

        // Validate hedge is further OTM than sell legs
        if (hedgeCEStrike <= sellCEStrike) {
            log.warn("Hedge CE strike {} is not further OTM than sell CE {} - adjusting hedge", hedgeCEStrike, sellCEStrike);
        }
        if (hedgePEStrike >= sellPEStrike) {
            log.warn("Hedge PE strike {} is not further OTM than sell PE {} - adjusting hedge", hedgePEStrike, sellPEStrike);
        }

        // Find instruments for all 4 strikes
        Instrument sellCallInst = instrumentIndex.get(InstrumentKey.of(sellCEStrike, StrategyConstants.OPTION_TYPE_CALL));
        Instrument sellPutInst = instrumentIndex.get(InstrumentKey.of(sellPEStrike, StrategyConstants.OPTION_TYPE_PUT));
        Instrument hedgeCallInst = instrumentIndex.get(InstrumentKey.of(hedgeCEStrike, StrategyConstants.OPTION_TYPE_CALL));
        Instrument hedgePutInst = instrumentIndex.get(InstrumentKey.of(hedgePEStrike, StrategyConstants.OPTION_TYPE_PUT));

        validateInstruments(sellCallInst, sellPutInst, hedgeCallInst, hedgePutInst,
                sellCEStrike, sellPEStrike, hedgeCEStrike, hedgePEStrike);

        // Place 4-leg basket order
        final int quantity = calculateOrderQuantity(request);
        final String orderType = resolveOrderType(request);

        List<StrategyExecutionResponse.OrderDetail> orderDetails = placeBasketOrder(
                sellCallInst, sellPutInst, hedgeCallInst, hedgePutInst,
                quantity, orderType, tradingMode, executionId);

        // Extract order IDs per leg type
        String sellCallOrderId = null, sellPutOrderId = null;
        String hedgeCallOrderId = null, hedgePutOrderId = null;

        for (StrategyExecutionResponse.OrderDetail od : orderDetails) {
            String optType = od.getOptionType();
            if (SELL_CALL_TYPE.equals(optType)) sellCallOrderId = od.getOrderId();
            else if (SELL_PUT_TYPE.equals(optType)) sellPutOrderId = od.getOrderId();
            else if (HEDGE_CALL_TYPE.equals(optType)) hedgeCallOrderId = od.getOrderId();
            else if (HEDGE_PUT_TYPE.equals(optType)) hedgePutOrderId = od.getOrderId();
        }

        validateOrderIds(sellCallOrderId, sellPutOrderId, hedgeCallOrderId, hedgePutOrderId);

        // Setup 4-leg monitoring
        setupMonitoring(executionId, sellCallInst, sellPutInst, hedgeCallInst, hedgePutInst,
                sellCallOrderId, sellPutOrderId, hedgeCallOrderId, hedgePutOrderId,
                quantity, stopLossPoints, targetPoints, targetDecayPct, stopLossExpansionPct,
                slTargetMode, completionCallback);

        return buildSuccessResponse(executionId, orderDetails,
                calculateNetPremium(orderDetails), stopLossPoints, targetPoints, tradingMode);
    }

    // ==================== Basket Order Handling ====================

    private List<StrategyExecutionResponse.OrderDetail> placeBasketOrder(
            Instrument sellCall, Instrument sellPut,
            Instrument hedgeCall, Instrument hedgePut,
            int quantity, String orderType, String tradingMode, String executionId) {

        log.info("[{}] Placing 4-leg basket order - SellCE: {}, SellPE: {}, HedgeCE: {}, HedgePE: {}, Qty: {}",
                tradingMode, sellCall.tradingsymbol, sellPut.tradingsymbol,
                hedgeCall.tradingsymbol, hedgePut.tradingsymbol, quantity);

        BasketOrderRequest basketRequest = buildBasketOrderRequest(
                sellCall, sellPut, hedgeCall, hedgePut, quantity, orderType, executionId);
        BasketOrderResponse basketResponse = unifiedTradingService.placeBasketOrder(basketRequest);

        log.info("[{}] Basket response - Status: {}, Success: {}/{}",
                tradingMode, basketResponse.getStatus(),
                basketResponse.getSuccessCount(), basketResponse.getTotalOrders());

        validateBasketResponse(basketResponse, quantity, tradingMode, executionId);

        return convertToOrderDetails(basketResponse, sellCall, sellPut, hedgeCall, hedgePut, quantity);
    }

    private BasketOrderRequest buildBasketOrderRequest(
            Instrument sellCall, Instrument sellPut,
            Instrument hedgeCall, Instrument hedgePut,
            int quantity, String orderType, String executionId) {

        List<BasketOrderRequest.BasketOrderItem> items = new ArrayList<>(4);

        // Sell CE leg
        items.add(BasketOrderRequest.BasketOrderItem.builder()
                .tradingSymbol(sellCall.tradingsymbol)
                .exchange(EXCHANGE_NFO)
                .transactionType(StrategyConstants.TRANSACTION_SELL)
                .quantity(quantity)
                .product(PRODUCT_MIS)
                .orderType(orderType)
                .validity(VALIDITY_DAY)
                .legType(LEG_TYPE_SELL_CALL)
                .instrumentToken(sellCall.instrument_token)
                .build());

        // Sell PE leg
        items.add(BasketOrderRequest.BasketOrderItem.builder()
                .tradingSymbol(sellPut.tradingsymbol)
                .exchange(EXCHANGE_NFO)
                .transactionType(StrategyConstants.TRANSACTION_SELL)
                .quantity(quantity)
                .product(PRODUCT_MIS)
                .orderType(orderType)
                .validity(VALIDITY_DAY)
                .legType(LEG_TYPE_SELL_PUT)
                .instrumentToken(sellPut.instrument_token)
                .build());

        // Hedge CE leg (BUY)
        items.add(BasketOrderRequest.BasketOrderItem.builder()
                .tradingSymbol(hedgeCall.tradingsymbol)
                .exchange(EXCHANGE_NFO)
                .transactionType(StrategyConstants.TRANSACTION_BUY)
                .quantity(quantity)
                .product(PRODUCT_MIS)
                .orderType(orderType)
                .validity(VALIDITY_DAY)
                .legType(LEG_TYPE_HEDGE_CALL)
                .instrumentToken(hedgeCall.instrument_token)
                .build());

        // Hedge PE leg (BUY)
        items.add(BasketOrderRequest.BasketOrderItem.builder()
                .tradingSymbol(hedgePut.tradingsymbol)
                .exchange(EXCHANGE_NFO)
                .transactionType(StrategyConstants.TRANSACTION_BUY)
                .quantity(quantity)
                .product(PRODUCT_MIS)
                .orderType(orderType)
                .validity(VALIDITY_DAY)
                .legType(LEG_TYPE_HEDGE_PUT)
                .instrumentToken(hedgePut.instrument_token)
                .build());

        return BasketOrderRequest.builder()
                .orders(items)
                .tag("SHORT_STRANGLE_" + executionId)
                .build();
    }

    private void validateBasketResponse(BasketOrderResponse response, int quantity,
                                        String tradingMode, String executionId) {
        if (!response.hasAnySuccess()) {
            throw new RuntimeException("Strangle basket order failed: " + response.getMessage());
        }

        if (!response.isAllSuccess()) {
            log.warn("[{}] Partial basket order - {} of {} orders. Initiating rollback.",
                    tradingMode, response.getSuccessCount(), response.getTotalOrders());
            rollbackSuccessfulLegs(response, quantity, tradingMode, executionId);
            throw new RuntimeException(buildPartialFillErrorMessage(response));
        }
    }

    private String buildPartialFillErrorMessage(BasketOrderResponse response) {
        StringBuilder sb = new StringBuilder("Strangle requires all 4 legs. Partial fill detected: ");
        for (BasketOrderResponse.BasketOrderResult result : response.getOrderResults()) {
            if (!STATUS_SUCCESS.equals(result.getStatus())) {
                sb.append('[').append(result.getLegType())
                        .append(" leg FAILED: ").append(result.getMessage()).append("] ");
            }
        }
        return sb.toString();
    }

    private List<StrategyExecutionResponse.OrderDetail> convertToOrderDetails(
            BasketOrderResponse response,
            Instrument sellCall, Instrument sellPut,
            Instrument hedgeCall, Instrument hedgePut,
            int quantity) {

        List<StrategyExecutionResponse.OrderDetail> orderDetails = new ArrayList<>(4);

        for (BasketOrderResponse.BasketOrderResult result : response.getOrderResults()) {
            if (STATUS_SUCCESS.equals(result.getStatus())) {
                Instrument instrument;
                String annotatedType;

                switch (result.getLegType()) {
                    case LEG_TYPE_SELL_CALL -> { instrument = sellCall; annotatedType = SELL_CALL_TYPE; }
                    case LEG_TYPE_SELL_PUT -> { instrument = sellPut; annotatedType = SELL_PUT_TYPE; }
                    case LEG_TYPE_HEDGE_CALL -> { instrument = hedgeCall; annotatedType = HEDGE_CALL_TYPE; }
                    case LEG_TYPE_HEDGE_PUT -> { instrument = hedgePut; annotatedType = HEDGE_PUT_TYPE; }
                    default -> {
                        log.warn("Unknown leg type: {}", result.getLegType());
                        continue;
                    }
                }

                double price = getOrderPriceFromResult(result);
                orderDetails.add(new StrategyExecutionResponse.OrderDetail(
                        result.getOrderId(),
                        result.getTradingSymbol(),
                        annotatedType,
                        Double.parseDouble(instrument.strike),
                        quantity,
                        price,
                        StrategyConstants.ORDER_STATUS_COMPLETE
                ));
            }
        }

        if (orderDetails.size() < 4) {
            throw new RuntimeException("Expected 4 order details but got " + orderDetails.size());
        }

        return orderDetails;
    }

    private double getOrderPriceFromResult(BasketOrderResponse.BasketOrderResult result) {
        if (result.getExecutionPrice() != null && result.getExecutionPrice() > 0) {
            return result.getExecutionPrice();
        }
        try {
            return getOrderPrice(result.getOrderId());
        } catch (KiteException | IOException e) {
            log.error("Failed to get order price for {}: {}", result.getOrderId(), e.getMessage());
            return 0.0;
        }
    }

    // ==================== Rollback Handling ====================

    private void rollbackSuccessfulLegs(BasketOrderResponse response, int quantity,
                                        String tradingMode, String executionId) {
        log.warn("[{}] ROLLBACK: Initiating for execution {} due to partial fill", tradingMode, executionId);

        final String capturedUserId = CurrentUserContext.getUserId();
        List<CompletableFuture<Boolean>> rollbackFutures = new ArrayList<>(4);

        for (BasketOrderResponse.BasketOrderResult result : response.getOrderResults()) {
            if (STATUS_SUCCESS.equals(result.getStatus()) && result.getOrderId() != null) {
                rollbackFutures.add(CompletableFuture.supplyAsync(
                        () -> executeRollback(result, quantity, tradingMode, capturedUserId),
                        EXIT_ORDER_EXECUTOR));
            }
        }

        waitForRollbacks(rollbackFutures, tradingMode, executionId);
    }

    private boolean executeRollback(BasketOrderResponse.BasketOrderResult result,
                                    int quantity, String tradingMode, String userId) {
        if (userId != null) {
            CurrentUserContext.setUserId(userId);
        }
        try {
            // Determine the reverse transaction type
            String reverseTransaction;
            if (LEG_TYPE_SELL_CALL.equals(result.getLegType()) || LEG_TYPE_SELL_PUT.equals(result.getLegType())) {
                // SELL legs need BUY to rollback
                reverseTransaction = StrategyConstants.TRANSACTION_BUY;
            } else {
                // BUY (hedge) legs need SELL to rollback
                reverseTransaction = StrategyConstants.TRANSACTION_SELL;
            }

            OrderRequest exitOrder = createOrderRequest(
                    result.getTradingSymbol(),
                    reverseTransaction,
                    quantity,
                    StrategyConstants.ORDER_TYPE_MARKET);

            OrderResponse exitResponse = unifiedTradingService.placeOrder(exitOrder);

            if (StrategyConstants.ORDER_STATUS_SUCCESS.equals(exitResponse.getStatus())) {
                log.info("[{}] ROLLBACK SUCCESS: {} leg exited", tradingMode, result.getLegType());
                return true;
            } else {
                log.error("[{}] ROLLBACK FAILED: {} leg - {}", tradingMode, result.getLegType(), exitResponse.getMessage());
                return false;
            }
        } catch (Exception | KiteException e) {
            log.error("[{}] ROLLBACK ERROR: {} leg - {}", tradingMode, result.getLegType(), e.getMessage(), e);
            return false;
        } finally {
            CurrentUserContext.clear();
        }
    }

    private void waitForRollbacks(List<CompletableFuture<Boolean>> futures, String tradingMode, String executionId) {
        if (futures.isEmpty()) return;

        try {
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                    .get(5, TimeUnit.SECONDS);

            int successCount = (int) futures.stream()
                    .filter(f -> Boolean.TRUE.equals(f.join()))
                    .count();

            log.info("[{}] ROLLBACK COMPLETE: {}/{} legs rolled back for {}",
                    tradingMode, successCount, futures.size(), executionId);

            if (successCount < futures.size()) {
                log.error("[{}] ROLLBACK INCOMPLETE: MANUAL INTERVENTION REQUIRED!", tradingMode);
            }
        } catch (java.util.concurrent.TimeoutException e) {
            log.error("[{}] ROLLBACK TIMEOUT for {}. MANUAL INTERVENTION REQUIRED!", tradingMode, executionId);
        } catch (Exception e) {
            log.error("[{}] ROLLBACK ERROR for {}: {}", tradingMode, executionId, e.getMessage(), e);
        }
    }

    // ==================== Monitoring Setup ====================

    private void setupMonitoring(String executionId,
                                 Instrument sellCall, Instrument sellPut,
                                 Instrument hedgeCall, Instrument hedgePut,
                                 String sellCallOrderId, String sellPutOrderId,
                                 String hedgeCallOrderId, String hedgePutOrderId,
                                 int quantity, double stopLossPoints, double targetPoints,
                                 double targetDecayPct, double stopLossExpansionPct,
                                 SlTargetMode slTargetMode, StrategyCompletionCallback completionCallback) {

        MonitoringSetupHelper.StrangleMonitoringParams params = new MonitoringSetupHelper.StrangleMonitoringParams(
                executionId, sellCall, sellPut, hedgeCall, hedgePut,
                sellCallOrderId, sellPutOrderId, hedgeCallOrderId, hedgePutOrderId,
                quantity, stopLossPoints, targetPoints, targetDecayPct, stopLossExpansionPct,
                slTargetMode, completionCallback
        );

        // For strangle, disable leg replacement — only support full exit of all legs
        MonitoringSetupHelper.MonitorCallbacks callbacks = new MonitoringSetupHelper.MonitorCallbacks(
                reason -> exitAllLegs(executionId, reason, completionCallback),
                (legSymbol, reason) -> exitAllLegs(executionId,
                        "Individual leg exit (" + legSymbol + "): " + reason, completionCallback),
                // No leg replacement for strangle — noop callback
                (exitedLegSymbol, legTypeToAdd, targetPremium, lossMakingLegSymbol, exitedLegLtp) ->
                        log.info("Leg replacement not supported for SHORT_STRANGLE; ignoring for {}", executionId)
        );

        monitoringSetupHelper.setupStrangleMonitoringAsync(params, callbacks, EXIT_ORDER_EXECUTOR);
    }

    // ==================== Exit Operations ====================

    private void exitAllLegs(String executionId, String reason, StrategyCompletionCallback completionCallback) {
        exitHandler.exitAllLegs(executionId, reason, completionCallback, EXIT_ORDER_EXECUTOR);
    }

    // ==================== Helper Methods ====================

    private void buildInstrumentIndex(List<Instrument> instruments) {
        instrumentIndex.clear();
        for (Instrument inst : instruments) {
            String optionType = inst.instrument_type;
            if (StrategyConstants.OPTION_TYPE_CALL.equals(optionType) ||
                    StrategyConstants.OPTION_TYPE_PUT.equals(optionType)) {
                try {
                    double strike = Double.parseDouble(inst.strike);
                    instrumentIndex.put(InstrumentKey.of(strike, optionType), inst);
                } catch (NumberFormatException e) {
                    log.trace("Skipping instrument with invalid strike: {}", inst.tradingsymbol);
                }
            }
        }
        log.debug("Built instrument index with {} entries", instrumentIndex.size());
    }

    private void validateInstruments(Instrument sellCall, Instrument sellPut,
                                     Instrument hedgeCall, Instrument hedgePut,
                                     double sellCEStrike, double sellPEStrike,
                                     double hedgeCEStrike, double hedgePEStrike) {
        StringBuilder errors = new StringBuilder();
        if (sellCall == null) errors.append("Sell CE not found for strike ").append(sellCEStrike).append(". ");
        if (sellPut == null) errors.append("Sell PE not found for strike ").append(sellPEStrike).append(". ");
        if (hedgeCall == null) errors.append("Hedge CE not found for strike ").append(hedgeCEStrike).append(". ");
        if (hedgePut == null) errors.append("Hedge PE not found for strike ").append(hedgePEStrike).append(". ");

        if (!errors.isEmpty()) {
            throw new RuntimeException("Strangle instruments not found: " + errors);
        }

        log.info("Strangle instruments - SellCE: {}, SellPE: {}, HedgeCE: {}, HedgePE: {}",
                sellCall.tradingsymbol, sellPut.tradingsymbol,
                hedgeCall.tradingsymbol, hedgePut.tradingsymbol);
    }

    private void validateOrderIds(String sellCallOrderId, String sellPutOrderId,
                                  String hedgeCallOrderId, String hedgePutOrderId) {
        if (sellCallOrderId == null) throw new RuntimeException("Sell Call order not found in basket response");
        if (sellPutOrderId == null) throw new RuntimeException("Sell Put order not found in basket response");
        if (hedgeCallOrderId == null) throw new RuntimeException("Hedge Call order not found in basket response");
        if (hedgePutOrderId == null) throw new RuntimeException("Hedge Put order not found in basket response");
    }

    private double calculateNetPremium(List<StrategyExecutionResponse.OrderDetail> orderDetails) {
        double sellPremium = 0.0;
        double hedgePremium = 0.0;
        int quantity = 0;

        for (StrategyExecutionResponse.OrderDetail od : orderDetails) {
            if (quantity == 0 && od.getQuantity() != null) quantity = od.getQuantity();
            String optType = od.getOptionType();
            if (SELL_CALL_TYPE.equals(optType) || SELL_PUT_TYPE.equals(optType)) {
                sellPremium += od.getPrice();
            } else {
                hedgePremium += od.getPrice();
            }
        }

        double netPremiumPerUnit = sellPremium - hedgePremium;
        log.info("[{}] Strangle legs placed - SellPremium: {}, HedgePremium: {}, NetPremium/unit: {}",
                getTradingMode(), sellPremium, hedgePremium, netPremiumPerUnit);

        return netPremiumPerUnit * quantity;
    }

    private StrategyExecutionResponse buildSuccessResponse(String executionId,
                                                           List<StrategyExecutionResponse.OrderDetail> orderDetails,
                                                           double totalPremium, double stopLossPoints,
                                                           double targetPoints, String tradingMode) {
        StrategyExecutionResponse response = new StrategyExecutionResponse();
        response.setExecutionId(executionId);
        response.setStatus(StrategyConstants.STRATEGY_STATUS_ACTIVE);
        response.setMessage(String.format("[%s MODE] Short Strangle executed successfully. Monitoring with SL=%.1fpts, Target=%.1fpts",
                tradingMode, stopLossPoints, targetPoints));
        response.setOrders(orderDetails);
        response.setTotalPremium(totalPremium);
        response.setCurrentValue(totalPremium);
        response.setProfitLoss(0.0);
        response.setProfitLossPercentage(0.0);

        log.info("[{} MODE] Short Strangle executed successfully. Net Premium: {}. Real-time monitoring started.",
                tradingMode, totalPremium);
        return response;
    }

    // ==================== Configuration Resolvers ====================

    private double resolveStopLossPoints(StrategyRequest request) {
        return request.getStopLossPoints() != null
                ? request.getStopLossPoints()
                : strategyConfig.getDefaultStopLossPoints();
    }

    private double resolveTargetPoints(StrategyRequest request) {
        return request.getTargetPoints() != null
                ? request.getTargetPoints()
                : strategyConfig.getDefaultTargetPoints();
    }

    private double resolveTargetDecayPct(StrategyRequest request) {
        return request.getTargetDecayPct() != null
                ? request.getTargetDecayPct()
                : strategyConfig.getTargetDecayPct();
    }

    private double resolveStopLossExpansionPct(StrategyRequest request) {
        return request.getStopLossExpansionPct() != null
                ? request.getStopLossExpansionPct()
                : strategyConfig.getStopLossExpansionPct();
    }

    private SlTargetMode resolveSlTargetMode(StrategyRequest request) {
        String mode = request.getSlTargetMode();
        if (mode != null && !mode.isBlank()) {
            return switch (mode.trim().toLowerCase()) {
                case "percentage", "premium" -> SlTargetMode.PREMIUM;
                case "points", "fixed" -> SlTargetMode.POINTS;
                case "mtm" -> SlTargetMode.MTM;
                default -> {
                    log.warn("Unknown slTargetMode '{}', defaulting to POINTS", mode);
                    yield SlTargetMode.POINTS;
                }
            };
        }
        return strategyConfig.isPremiumBasedExitEnabled() ? SlTargetMode.PREMIUM : SlTargetMode.POINTS;
    }

    private String resolveOrderType(StrategyRequest request) {
        return request.getOrderType() != null
                ? request.getOrderType()
                : StrategyConstants.ORDER_TYPE_MARKET;
    }

    private String getTradingMode() {
        return unifiedTradingService.isPaperTradingEnabled()
                ? StrategyConstants.TRADING_MODE_PAPER
                : StrategyConstants.TRADING_MODE_LIVE;
    }

    private void logExecutionStart(String tradingMode, String instrumentType,
                                   double stopLossPoints, double targetPoints,
                                   SlTargetMode slTargetMode, double targetDecayPct,
                                   double stopLossExpansionPct, double sellDelta, double hedgeDelta) {
        log.info("[{} MODE] Executing Short Strangle for {} with SL={}pts, Target={}pts, SellΔ={}, HedgeΔ={}",
                tradingMode, instrumentType, stopLossPoints, targetPoints, sellDelta, hedgeDelta);
        log.info("[{}] SL/Target mode: {}", tradingMode, slTargetMode);

        if (slTargetMode == SlTargetMode.PREMIUM || strategyConfig.isPremiumBasedExitEnabled()) {
            log.info("[{}] Premium-based exit: targetDecayPct={}%, stopLossExpansionPct={}%",
                    tradingMode, targetDecayPct, stopLossExpansionPct);
        }
    }

    @Override
    public String getStrategyName() {
        return "Short Strangle";
    }

    @Override
    public String getStrategyDescription() {
        return String.format("Sell OTM CE (%.1fΔ) + Sell OTM PE (%.1fΔ) + Buy Hedge CE (%.1fΔ) + Buy Hedge PE (%.1fΔ) " +
                        "(Short volatility strategy with defined risk, SL=%.1fpts, Target=%.1fpts)",
                strategyConfig.getShortStrangleSellDelta(),
                strategyConfig.getShortStrangleSellDelta(),
                strategyConfig.getShortStrangleHedgeDelta(),
                strategyConfig.getShortStrangleHedgeDelta(),
                strategyConfig.getDefaultStopLossPoints(),
                strategyConfig.getDefaultTargetPoints());
    }
}




