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
import com.tradingbot.model.StrategyExecution;
import com.tradingbot.service.StrategyService;
import com.tradingbot.service.TradingService;
import com.tradingbot.service.UnifiedTradingService;
import com.tradingbot.service.greeks.DeltaCacheService;
import com.tradingbot.service.strategy.monitoring.PositionMonitorV2;
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
 * SELL ATM Straddle Strategy - Sell 1 ATM Call + Sell 1 ATM Put
 * <p>
 * Entry/exit thresholds match PositionMonitor (2.5 & 4 points).
 * Flow mirrors ATMStraddleStrategy but uses SELL instead of BUY.
 * <p>
 * HFT Optimizations:
 * <ul>
 *   <li>O(1) instrument lookup via pre-built HashMap index</li>
 *   <li>Pre-parsed strike values to avoid Double.parseDouble() on hot path</li>
 *   <li>Parallel order placement for both legs</li>
 *   <li>High-priority dedicated thread pool for exit orders</li>
 *   <li>Pre-computed string constants to avoid concatenation</li>
 * </ul>
 *
 * @since 4.0
 */
@Slf4j
@Component
public class SellATMStraddleStrategy extends BaseStrategy {

    private final WebSocketService webSocketService;
    private final StrategyConfig strategyConfig;
    private final StrategyService strategyService;
    private final VolatilityFilterService volatilityFilterService;
    private final VolatilityConfig volatilityConfig;
    private final StraddleExitHandler exitHandler;
    private final LegReplacementHandler legReplacementHandler;
    private final MonitoringSetupHelper monitoringSetupHelper;

    // ==================== HFT Thread Pool Configuration ====================
    private static final int HFT_THREAD_POOL_SIZE = 8;
    private static final AtomicInteger THREAD_COUNTER = new AtomicInteger(0);
    private static final ThreadFactory HFT_THREAD_FACTORY = r -> {
        Thread t = new Thread(r, "hft-sell-exit-" + THREAD_COUNTER.incrementAndGet());
        t.setDaemon(true);
        t.setPriority(Thread.MAX_PRIORITY);
        return t;
    };
    private static final ExecutorService EXIT_ORDER_EXECUTOR = Executors.newFixedThreadPool(
            HFT_THREAD_POOL_SIZE, HFT_THREAD_FACTORY);

    // Pre-computed constants for HFT
    private static final String CALL_SHORT_SUFFIX = "_SHORT";
    private static final String ANNOTATED_CALL_TYPE = StrategyConstants.OPTION_TYPE_CALL + CALL_SHORT_SUFFIX;
    private static final String ANNOTATED_PUT_TYPE = StrategyConstants.OPTION_TYPE_PUT + CALL_SHORT_SUFFIX;

    // ==================== HFT Instrument Index ====================
    /**
     * Immutable key for O(1) instrument lookup by strike and option type.
     */
    private record InstrumentKey(long strikeX100, String optionType) {
        static InstrumentKey of(double strike, String optionType) {
            return new InstrumentKey((long) (strike * 100), optionType.intern());
        }
    }

    private final Map<InstrumentKey, Instrument> instrumentIndex = new HashMap<>(256);

    public SellATMStraddleStrategy(TradingService tradingService,
                                   UnifiedTradingService unifiedTradingService,
                                   Map<String, Integer> lotSizeCache,
                                   WebSocketService webSocketService,
                                   StrategyConfig strategyConfig,
                                   @Lazy StrategyService strategyService,
                                   DeltaCacheService deltaCacheService,
                                   VolatilityFilterService volatilityFilterService,
                                   VolatilityConfig volatilityConfig,
                                   StraddleExitHandler exitHandler,
                                   LegReplacementHandler legReplacementHandler,
                                   MonitoringSetupHelper monitoringSetupHelper) {
        super(tradingService, unifiedTradingService, lotSizeCache, deltaCacheService);
        this.webSocketService = webSocketService;
        this.strategyConfig = strategyConfig;
        this.strategyService = strategyService;
        this.volatilityFilterService = volatilityFilterService;
        this.volatilityConfig = volatilityConfig;
        this.exitHandler = exitHandler;
        this.legReplacementHandler = legReplacementHandler;
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

        logExecutionStart(tradingMode, instrumentType, stopLossPoints, targetPoints, slTargetMode, targetDecayPct, stopLossExpansionPct);

        // Get spot price and instruments
        final double spotPrice = getCurrentSpotPrice(instrumentType);
        log.info("Current spot price: {}", spotPrice);

        final List<Instrument> instruments = getOptionInstruments(instrumentType, expiry);
        log.info("Found {} option instruments for {}", instruments.size(), instrumentType);

        // Calculate ATM strike using delta-based selection
        final Date expiryDate = !instruments.isEmpty() ? instruments.get(0).expiry : null;
        final double atmStrike = expiryDate != null
                ? getATMStrikeByDelta(spotPrice, instrumentType, expiryDate)
                : getATMStrike(spotPrice, instrumentType);
        log.info("ATM Strike (Delta-based): {}", atmStrike);

        // Build instrument index and find ATM options
        buildInstrumentIndex(instruments);
        Instrument atmCall = instrumentIndex.get(InstrumentKey.of(atmStrike, StrategyConstants.OPTION_TYPE_CALL));
        Instrument atmPut = instrumentIndex.get(InstrumentKey.of(atmStrike, StrategyConstants.OPTION_TYPE_PUT));
        validateATMOptions(atmCall, atmPut, atmStrike);

        // Place basket order
        final int quantity = calculateOrderQuantity(request);
        final String orderType = resolveOrderType(request);

        List<StrategyExecutionResponse.OrderDetail> orderDetails = placeBasketOrderForStraddle(
                atmCall, atmPut, quantity, orderType, tradingMode, executionId);

        // Extract order IDs
        String callOrderId = null, putOrderId = null;
        for (StrategyExecutionResponse.OrderDetail od : orderDetails) {
            if (od.getOptionType().contains(StrategyConstants.OPTION_TYPE_CALL)) {
                callOrderId = od.getOrderId();
            } else if (od.getOptionType().contains(StrategyConstants.OPTION_TYPE_PUT)) {
                putOrderId = od.getOrderId();
            }
        }
        validateOrderIds(callOrderId, putOrderId);

        // Setup monitoring
        setupMonitoring(executionId, atmCall, atmPut, callOrderId, putOrderId,
                quantity, stopLossPoints, targetPoints, targetDecayPct, stopLossExpansionPct,
                slTargetMode, completionCallback);

        return buildSuccessResponse(executionId, orderDetails,
                calculateTotalPremium(orderDetails), stopLossPoints, targetPoints, tradingMode);
    }

    // ==================== Basket Order Handling ====================

    private List<StrategyExecutionResponse.OrderDetail> placeBasketOrderForStraddle(
            Instrument atmCall, Instrument atmPut, int quantity, String orderType,
            String tradingMode, String executionId) {

        log.info("[{}] Placing basket order - Call: {}, Put: {}, Qty: {}",
                tradingMode, atmCall.tradingsymbol, atmPut.tradingsymbol, quantity);

        BasketOrderRequest basketRequest = buildBasketOrderRequest(atmCall, atmPut, quantity, orderType, executionId);
        BasketOrderResponse basketResponse = unifiedTradingService.placeBasketOrder(basketRequest);

        log.info("[{}] Basket response - Status: {}, Success: {}/{}",
                tradingMode, basketResponse.getStatus(),
                basketResponse.getSuccessCount(), basketResponse.getTotalOrders());

        validateBasketResponse(basketResponse, atmCall, atmPut, quantity, tradingMode, executionId);

        return convertToOrderDetails(basketResponse, atmCall, atmPut, quantity);
    }

    private BasketOrderRequest buildBasketOrderRequest(Instrument atmCall, Instrument atmPut,
                                                       int quantity, String orderType, String executionId) {
        List<BasketOrderRequest.BasketOrderItem> items = new ArrayList<>(2);

        items.add(BasketOrderRequest.BasketOrderItem.builder()
                .tradingSymbol(atmCall.tradingsymbol)
                .exchange(EXCHANGE_NFO)
                .transactionType(StrategyConstants.TRANSACTION_SELL)
                .quantity(quantity)
                .product(PRODUCT_MIS)
                .orderType(orderType)
                .validity(VALIDITY_DAY)
                .legType(StrategyConstants.LEG_TYPE_CALL)
                .instrumentToken(atmCall.instrument_token)
                .build());

        items.add(BasketOrderRequest.BasketOrderItem.builder()
                .tradingSymbol(atmPut.tradingsymbol)
                .exchange(EXCHANGE_NFO)
                .transactionType(StrategyConstants.TRANSACTION_SELL)
                .quantity(quantity)
                .product(PRODUCT_MIS)
                .orderType(orderType)
                .validity(VALIDITY_DAY)
                .legType(StrategyConstants.LEG_TYPE_PUT)
                .instrumentToken(atmPut.instrument_token)
                .build());

        return BasketOrderRequest.builder()
                .orders(items)
                .tag("SELL_STRADDLE_" + executionId)
                .build();
    }

    private void validateBasketResponse(BasketOrderResponse response, Instrument atmCall, Instrument atmPut,
                                        int quantity, String tradingMode, String executionId) {
        if (!response.hasAnySuccess()) {
            throw new RuntimeException("Basket order failed: " + response.getMessage());
        }

        if (!response.isAllSuccess()) {
            log.warn("[{}] Partial basket order - {} of {} orders. Initiating rollback.",
                    tradingMode, response.getSuccessCount(), response.getTotalOrders());
            rollbackSuccessfulLegs(response, quantity, tradingMode, executionId);
            throw new RuntimeException(buildPartialFillErrorMessage(response));
        }
    }

    private String buildPartialFillErrorMessage(BasketOrderResponse response) {
        StringBuilder sb = new StringBuilder("Straddle requires both legs. Partial fill detected: ");
        for (BasketOrderResponse.BasketOrderResult result : response.getOrderResults()) {
            if (!STATUS_SUCCESS.equals(result.getStatus())) {
                sb.append('[').append(result.getLegType())
                        .append(" leg FAILED: ").append(result.getMessage()).append("] ");
            }
        }
        return sb.toString();
    }

    private List<StrategyExecutionResponse.OrderDetail> convertToOrderDetails(
            BasketOrderResponse response, Instrument atmCall, Instrument atmPut, int quantity) {

        List<StrategyExecutionResponse.OrderDetail> orderDetails = new ArrayList<>(2);

        for (BasketOrderResponse.BasketOrderResult result : response.getOrderResults()) {
            if (STATUS_SUCCESS.equals(result.getStatus())) {
                Instrument instrument = StrategyConstants.LEG_TYPE_CALL.equals(result.getLegType())
                        ? atmCall : atmPut;
                double price = getOrderPriceFromResult(result);
                orderDetails.add(createOrderDetail(result, instrument, quantity, price));
            }
        }

        if (orderDetails.size() < 2) {
            throw new RuntimeException("Expected 2 order details but got " + orderDetails.size());
        }

        return orderDetails;
    }

    private StrategyExecutionResponse.OrderDetail createOrderDetail(
            BasketOrderResponse.BasketOrderResult result, Instrument instrument, int quantity, double price) {

        String annotatedType = StrategyConstants.LEG_TYPE_CALL.equals(result.getLegType())
                ? ANNOTATED_CALL_TYPE : ANNOTATED_PUT_TYPE;

        return new StrategyExecutionResponse.OrderDetail(
                result.getOrderId(),
                result.getTradingSymbol(),
                annotatedType,
                Double.parseDouble(instrument.strike),
                quantity,
                price,
                StrategyConstants.ORDER_STATUS_COMPLETE
        );
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
        List<CompletableFuture<Boolean>> rollbackFutures = new ArrayList<>(2);

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
            OrderRequest exitOrder = createOrderRequest(
                    result.getTradingSymbol(),
                    StrategyConstants.TRANSACTION_BUY,
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
        } catch (Exception e) {
            log.error("[{}] ROLLBACK ERROR: {} leg - {}", tradingMode, result.getLegType(), e.getMessage(), e);
            return false;
        } catch (KiteException e) {
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

    private void setupMonitoring(String executionId, Instrument callInstrument, Instrument putInstrument,
                                 String callOrderId, String putOrderId, int quantity,
                                 double stopLossPoints, double targetPoints,
                                 double targetDecayPct, double stopLossExpansionPct,
                                 SlTargetMode slTargetMode, StrategyCompletionCallback completionCallback) {

        MonitoringSetupHelper.MonitoringParams params = new MonitoringSetupHelper.MonitoringParams(
                executionId, callInstrument, putInstrument,
                callOrderId, putOrderId, quantity,
                stopLossPoints, targetPoints, targetDecayPct, stopLossExpansionPct,
                slTargetMode, completionCallback
        );

        MonitoringSetupHelper.MonitorCallbacks callbacks = new MonitoringSetupHelper.MonitorCallbacks(
                reason -> exitAllLegs(executionId, reason, completionCallback),
                (legSymbol, reason) -> exitIndividualLeg(executionId, legSymbol, quantity, reason, completionCallback),
                (exitedLegSymbol, legTypeToAdd, targetPremium, lossMakingLegSymbol, exitedLegLtp) ->
                        CompletableFuture.runAsync(() -> handleLegReplacement(
                                executionId, exitedLegSymbol, legTypeToAdd, targetPremium,
                                lossMakingLegSymbol, quantity, exitedLegLtp), EXIT_ORDER_EXECUTOR)
        );

        monitoringSetupHelper.setupMonitoringAsync(params, callbacks, EXIT_ORDER_EXECUTOR);
    }

    // ==================== Exit Operations ====================

    private void exitAllLegs(String executionId, String reason, StrategyCompletionCallback completionCallback) {
        exitHandler.exitAllLegs(executionId, reason, completionCallback, EXIT_ORDER_EXECUTOR);
    }

    private void exitIndividualLeg(String executionId, String legSymbol, int quantity,
                                   String reason, StrategyCompletionCallback completionCallback) {
        String tradingMode = getTradingMode();
        String legType = legSymbol.contains(StrategyConstants.OPTION_TYPE_CALL) ? "Call" : "Put";

        log.info("[{}] Exiting individual {} leg for {}: Symbol={}, Reason={}",
                tradingMode, legType, executionId, legSymbol, reason);

        StrategyExecution execution = strategyService.getStrategy(executionId);
        if (execution == null) {
            log.error("Execution not found: {}", executionId);
            return;
        }

        List<StrategyExecution.OrderLeg> orderLegs = execution.getOrderLegs();
        if (orderLegs == null || orderLegs.isEmpty()) {
            log.error("No order legs found for {}", executionId);
            return;
        }

        StrategyExecution.OrderLeg matchingLeg = orderLegs.stream()
                .filter(leg -> legSymbol.equals(leg.getTradingSymbol()))
                .findFirst()
                .orElse(null);

        if (matchingLeg == null) {
            log.error("Leg {} not found in execution {}", legSymbol, executionId);
            return;
        }

        if (matchingLeg.getLifecycleState() == StrategyExecution.LegLifecycleState.EXITED ||
                matchingLeg.getLifecycleState() == StrategyExecution.LegLifecycleState.EXIT_PENDING) {
            log.warn("Leg {} already {}, skipping", legSymbol, matchingLeg.getLifecycleState());
            return;
        }

        Map<String, String> result = exitHandler.processLegExit(matchingLeg, tradingMode);

        if (STATUS_SUCCESS.equals(result.get("status"))) {
            log.info(StrategyConstants.LOG_LEG_EXITED, tradingMode, legType, result.get("exitOrderId"));
        } else {
            log.error("Failed to exit {} leg: {}", legType, result.get("message"));
        }

        checkAllLegsExited(orderLegs, executionId, reason, completionCallback);
    }

    private void checkAllLegsExited(List<StrategyExecution.OrderLeg> orderLegs, String executionId,
                                    String reason, StrategyCompletionCallback completionCallback) {
        boolean allExited = orderLegs.stream()
                .allMatch(leg -> leg.getLifecycleState() == StrategyExecution.LegLifecycleState.EXITED);

        if (allExited) {
            log.info("All legs closed for {}, stopping monitoring", executionId);
            try {
                webSocketService.stopMonitoring(executionId);
            } catch (Exception e) {
                log.error("Error stopping monitoring for {}: {}", executionId, e.getMessage());
            }

            if (completionCallback != null) {
                completionCallback.onStrategyCompleted(executionId,
                        exitHandler.mapExitReasonToCompletionReason(reason));
            }
        } else {
            long remaining = orderLegs.stream()
                    .filter(leg -> leg.getLifecycleState() != StrategyExecution.LegLifecycleState.EXITED)
                    .count();
            log.info("Remaining legs for {}: {}", executionId, remaining);
        }
    }

    // ==================== Leg Replacement ====================

    private void handleLegReplacement(String executionId, String exitedLegSymbol, String legTypeToAdd,
                                      double targetPremium, String lossMakingLegSymbol,
                                      int quantity, double exitedLegLtp) {
        String previousUser = CurrentUserContext.getUserId();
        try {
            webSocketService.getMonitor(executionId).ifPresent(monitor -> {
                String ownerUserId = monitor.getOwnerUserId();
                if (ownerUserId != null && !ownerUserId.isBlank()) {
                    CurrentUserContext.setUserId(ownerUserId);
                }

                legReplacementHandler.placeReplacementLegOrder(
                        executionId, instrumentIndex, exitedLegSymbol, legTypeToAdd,
                        targetPremium, lossMakingLegSymbol, quantity,
                        (PositionMonitorV2) monitor, exitedLegLtp);
            });
        } finally {
            if (previousUser != null && !previousUser.isBlank()) {
                CurrentUserContext.setUserId(previousUser);
            } else {
                CurrentUserContext.clear();
            }
        }
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

    private void validateATMOptions(Instrument atmCall, Instrument atmPut, double atmStrike) {
        log.info("ATM Call: {}, ATM Put: {}",
                atmCall != null ? atmCall.tradingsymbol : "NULL",
                atmPut != null ? atmPut.tradingsymbol : "NULL");

        if (atmCall == null || atmPut == null) {
            throw new RuntimeException(StrategyConstants.ERROR_ATM_OPTIONS_NOT_FOUND + atmStrike);
        }
    }

    private void validateOrderIds(String callOrderId, String putOrderId) {
        if (callOrderId == null) {
            throw new RuntimeException("Call order not found in basket response");
        }
        if (putOrderId == null) {
            throw new RuntimeException("Put order not found in basket response");
        }
    }

    private double calculateTotalPremium(List<StrategyExecutionResponse.OrderDetail> orderDetails) {
        double callPrice = orderDetails.get(0).getPrice();
        double putPrice = orderDetails.get(1).getPrice();
        int quantity = orderDetails.get(0).getQuantity();

        log.info(StrategyConstants.LOG_BOTH_LEGS_PLACED, getTradingMode(), callPrice, putPrice);
        return (callPrice + putPrice) * quantity;
    }

    private StrategyExecutionResponse buildSuccessResponse(String executionId,
                                                           List<StrategyExecutionResponse.OrderDetail> orderDetails,
                                                           double totalPremium, double stopLossPoints,
                                                           double targetPoints, String tradingMode) {
        StrategyExecutionResponse response = new StrategyExecutionResponse();
        response.setExecutionId(executionId);
        response.setStatus(StrategyConstants.STRATEGY_STATUS_ACTIVE);
        response.setMessage(String.format(StrategyConstants.MSG_STRATEGY_SUCCESS,
                tradingMode, stopLossPoints, targetPoints));
        response.setOrders(orderDetails);
        response.setTotalPremium(totalPremium);
        response.setCurrentValue(totalPremium);
        response.setProfitLoss(0.0);
        response.setProfitLossPercentage(0.0);

        log.info(StrategyConstants.LOG_STRATEGY_EXECUTED, tradingMode, totalPremium);
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
                                   SlTargetMode slTargetMode, double targetDecayPct, double stopLossExpansionPct) {
        log.info(StrategyConstants.LOG_EXECUTING_STRATEGY,
                tradingMode, instrumentType, stopLossPoints, targetPoints);
        log.info("[{}] SL/Target mode: {}", tradingMode, slTargetMode);

        if (slTargetMode == SlTargetMode.PREMIUM || strategyConfig.isPremiumBasedExitEnabled()) {
            log.info("[{}] Premium-based exit: targetDecayPct={}%, stopLossExpansionPct={}%",
                    tradingMode, targetDecayPct, stopLossExpansionPct);
        }
    }

    @Override
    public String getStrategyName() {
        return "Sell ATM Straddle";
    }

    @Override
    public String getStrategyDescription() {
        return String.format("Sell ATM Call + Sell ATM Put (Short volatility strategy with SL=%.1fpts, Target=%.1fpts) - Supports Paper & Live Trading",
                strategyConfig.getDefaultStopLossPoints(),
                strategyConfig.getDefaultTargetPoints());
    }
}
