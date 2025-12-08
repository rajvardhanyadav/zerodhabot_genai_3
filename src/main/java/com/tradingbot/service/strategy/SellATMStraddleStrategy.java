package com.tradingbot.service.strategy;

import com.tradingbot.config.StrategyConfig;
import com.tradingbot.dto.BasketOrderRequest;
import com.tradingbot.dto.BasketOrderResponse;
import com.tradingbot.dto.OrderRequest;
import com.tradingbot.dto.OrderResponse;
import com.tradingbot.dto.StrategyExecutionResponse;
import com.tradingbot.dto.StrategyRequest;
import com.tradingbot.model.StrategyCompletionReason;
import com.tradingbot.model.StrategyExecution;
import com.tradingbot.model.StrategyStatus;
import com.tradingbot.service.StrategyService;
import com.tradingbot.service.TradingService;
import com.tradingbot.service.UnifiedTradingService;
import com.tradingbot.service.greeks.DeltaCacheService;
import com.tradingbot.service.strategy.monitoring.PositionMonitor;
import com.tradingbot.service.strategy.monitoring.WebSocketService;
import com.tradingbot.util.CurrentUserContext;
import com.tradingbot.util.StrategyConstants;
import com.zerodhatech.kiteconnect.kitehttp.exceptions.KiteException;
import com.zerodhatech.models.Instrument;
import com.zerodhatech.models.Order;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

import static com.tradingbot.service.TradingConstants.*;
import static com.tradingbot.service.TradingConstants.STATUS_FAILED;
import static com.tradingbot.service.TradingConstants.STATUS_SUCCESS;
import static com.tradingbot.service.TradingConstants.VALIDITY_DAY;

/**
 * SELL ATM Straddle Strategy
 * Sell 1 ATM Call + Sell 1 ATM Put
 *
 * Entry/exit thresholds are same as PositionMonitor (2.5 & 4 points),
 * and overall flow mirrors ATMStraddleStrategy but uses SELL instead of BUY.
 */
@Slf4j
@Component
public class SellATMStraddleStrategy extends BaseStrategy {

    private final WebSocketService webSocketService;
    private final StrategyConfig strategyConfig;
    private final StrategyService strategyService;

    // ==================== HFT OPTIMIZATION: Thread Pool Configuration ====================
    // Dedicated executor for parallel exit order placement - critical for HFT
    // Using higher thread count and priority for minimum latency
    private static final int HFT_THREAD_POOL_SIZE = 8;
    private static final AtomicInteger THREAD_COUNTER = new AtomicInteger(0);
    private static final ThreadFactory HFT_THREAD_FACTORY = r -> {
        Thread t = new Thread(r, "hft-sell-exit-" + THREAD_COUNTER.incrementAndGet());
        t.setDaemon(true);
        t.setPriority(Thread.MAX_PRIORITY); // HFT: Maximize thread priority
        return t;
    };
    private static final ExecutorService EXIT_ORDER_EXECUTOR = Executors.newFixedThreadPool(
            HFT_THREAD_POOL_SIZE, HFT_THREAD_FACTORY);

    // Pre-computed constants for HFT - avoid repeated calculations
    private static final String CALL_SHORT_SUFFIX = "_SHORT";
    private static final String ANNOTATED_CALL_TYPE = StrategyConstants.OPTION_TYPE_CALL + CALL_SHORT_SUFFIX;
    private static final String ANNOTATED_PUT_TYPE = StrategyConstants.OPTION_TYPE_PUT + CALL_SHORT_SUFFIX;

    public SellATMStraddleStrategy(TradingService tradingService,
                                   UnifiedTradingService unifiedTradingService,
                                   Map<String, Integer> lotSizeCache,
                                   WebSocketService webSocketService,
                                   StrategyConfig strategyConfig,
                                   @Lazy StrategyService strategyService,
                                   DeltaCacheService deltaCacheService) {
        super(tradingService, unifiedTradingService, lotSizeCache, deltaCacheService);
        this.webSocketService = webSocketService;
        this.strategyConfig = strategyConfig;
        this.strategyService = strategyService;
    }

    @Override
    public StrategyExecutionResponse execute(StrategyRequest request, String executionId,
                                             StrategyCompletionCallback completionCallback)
            throws KiteException, IOException {

        // HFT: Cache request values locally to avoid repeated method calls
        final String instrumentType = request.getInstrumentType();
        final String expiry = request.getExpiry();
        final double stopLossPoints = getStopLossPoints(request);
        final double targetPoints = getTargetPoints(request);
        final String tradingMode = getTradingMode();

        log.info(StrategyConstants.LOG_EXECUTING_STRATEGY,
                tradingMode, instrumentType, stopLossPoints, targetPoints);

        // HFT: Use parallel fetch for spot price and instruments
        final double spotPrice = getCurrentSpotPrice(instrumentType);
        log.info("Current spot price: {}", spotPrice);

        // Get option instruments first (needed for delta calculation)
        final List<Instrument> instruments = getOptionInstruments(instrumentType, expiry);
        final int instrumentCount = instruments.size();
        log.info("Found {} option instruments for {}", instrumentCount, instrumentType);

        // Get expiry date from instruments for delta calculation
        final Date expiryDate = instrumentCount > 0 ? instruments.get(0).expiry : null;
        log.info("Using expiry date: {}", expiryDate);

        // Calculate ATM strike using delta-based selection (nearest to ±0.5)
        final double atmStrike = expiryDate != null
                ? getATMStrikeByDelta(spotPrice, instrumentType, expiryDate)
                : getATMStrike(spotPrice, instrumentType);

        log.info("ATM Strike (Delta-based): {}", atmStrike);

        // HFT: Find both instruments in single pass through collection
        Instrument atmCall = null;
        Instrument atmPut = null;
        for (int i = 0; i < instrumentCount && (atmCall == null || atmPut == null); i++) {
            Instrument inst = instruments.get(i);
            if (Double.parseDouble(inst.strike) == atmStrike) {
                if (StrategyConstants.OPTION_TYPE_CALL.equals(inst.instrument_type)) {
                    atmCall = inst;
                } else if (StrategyConstants.OPTION_TYPE_PUT.equals(inst.instrument_type)) {
                    atmPut = inst;
                }
            }
        }

        validateATMOptions(atmCall, atmPut, atmStrike);

        // HFT: Pre-compute quantity before order placement
        final int quantity = calculateOrderQuantity(request);
        final String orderType = getOrderType(request);

        // Place both legs as SELL using basket order for atomic execution
        final List<StrategyExecutionResponse.OrderDetail> orderDetails = placeBasketOrderForStraddle(
                atmCall, atmPut, quantity, orderType, tradingMode, executionId);

        // HFT: Optimized order detail extraction using indexed access instead of stream
        String callOrderId = null;
        String putOrderId = null;
        final int orderCount = orderDetails.size();
        for (int i = 0; i < orderCount; i++) {
            StrategyExecutionResponse.OrderDetail od = orderDetails.get(i);
            if (od.getOptionType().contains(StrategyConstants.OPTION_TYPE_CALL)) {
                callOrderId = od.getOrderId();
            } else if (od.getOptionType().contains(StrategyConstants.OPTION_TYPE_PUT)) {
                putOrderId = od.getOrderId();
            }
        }

        if (callOrderId == null) {
            throw new RuntimeException("Call order not found in basket response");
        }
        if (putOrderId == null) {
            throw new RuntimeException("Put order not found in basket response");
        }

        final double totalPremium = calculateTotalPremium(orderDetails);

        setupMonitoring(executionId, atmCall, atmPut,
                callOrderId, putOrderId,
                quantity, stopLossPoints, targetPoints, completionCallback);

        return buildSuccessResponse(executionId, orderDetails, totalPremium, stopLossPoints, targetPoints, tradingMode);
    }

    /**
     * Place basket order for straddle - places both Call and Put SELL orders atomically
     */
    private List<StrategyExecutionResponse.OrderDetail> placeBasketOrderForStraddle(
            Instrument atmCall, Instrument atmPut, int quantity, String orderType,
            String tradingMode, String executionId) {

        log.info("[{}] Placing basket order for Sell ATM Straddle - Call: {}, Put: {}, Qty: {}",
                tradingMode, atmCall.tradingsymbol, atmPut.tradingsymbol, quantity);

        // Build basket order request with both legs
        List<BasketOrderRequest.BasketOrderItem> basketItems = new ArrayList<>();

        // Call leg
        basketItems.add(BasketOrderRequest.BasketOrderItem.builder()
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

        // Put leg
        basketItems.add(BasketOrderRequest.BasketOrderItem.builder()
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

        BasketOrderRequest basketRequest = BasketOrderRequest.builder()
                .orders(basketItems)
                .tag("SELL_STRADDLE_" + executionId)
                .build();

        // Place basket order
        BasketOrderResponse basketResponse = unifiedTradingService.placeBasketOrder(basketRequest);

        log.info("[{}] Basket order response - Status: {}, Success: {}/{}",
                tradingMode, basketResponse.getStatus(),
                basketResponse.getSuccessCount(), basketResponse.getTotalOrders());

        // Validate basket order response
        if (!basketResponse.hasAnySuccess()) {
            String errorMsg = "Basket order failed: " + basketResponse.getMessage();
            log.error(errorMsg);
            throw new RuntimeException(errorMsg);
        }

        if (!basketResponse.isAllSuccess()) {
            log.warn("[{}] Partial basket order success - {} of {} orders placed",
                    tradingMode, basketResponse.getSuccessCount(), basketResponse.getTotalOrders());
            // For straddle, we need both legs - if partial, we should handle appropriately
            // For now, we'll proceed with what was successful but log a warning
        }

        // Convert basket response to order details
        List<StrategyExecutionResponse.OrderDetail> orderDetails = new ArrayList<>();

        for (BasketOrderResponse.BasketOrderResult result : basketResponse.getOrderResults()) {
            if (STATUS_SUCCESS.equals(result.getStatus())) {
                // Get the corresponding instrument
                Instrument instrument = StrategyConstants.LEG_TYPE_CALL.equals(result.getLegType())
                        ? atmCall : atmPut;

                // Get order price
                double price = getOrderPriceFromBasketResult(result);

                orderDetails.add(createOrderDetailFromBasketResult(result, instrument, quantity, price));
            }
        }

        if (orderDetails.size() < 2) {
            String errorMsg = String.format("Expected 2 order details but got %d. Basket order may have partially failed.",
                    orderDetails.size());
            log.error(errorMsg);
            throw new RuntimeException(errorMsg);
        }

        log.info("[{}] Basket order completed - {} orders placed successfully", tradingMode, orderDetails.size());

        return orderDetails;
    }

    /**
     * Get order price from basket result, falling back to order history if not available
     */
    private double getOrderPriceFromBasketResult(BasketOrderResponse.BasketOrderResult result) {
        if (result.getExecutionPrice() != null && result.getExecutionPrice() > 0) {
            return result.getExecutionPrice();
        }
        // Fallback to order history lookup
        try {
            return getOrderPrice(result.getOrderId());
        } catch (KiteException | IOException e) {
            log.error("Failed to get order price for order {}: {}", result.getOrderId(), e.getMessage());
            return 0.0;
        }
    }

    /**
     * Create order detail from basket result
     */
    private StrategyExecutionResponse.OrderDetail createOrderDetailFromBasketResult(
            BasketOrderResponse.BasketOrderResult result, Instrument instrument, int quantity, double price) {

        // HFT: Use pre-computed annotated types to avoid string concatenation on hot path
        final String annotatedType = StrategyConstants.LEG_TYPE_CALL.equals(result.getLegType())
                ? ANNOTATED_CALL_TYPE
                : ANNOTATED_PUT_TYPE;

        return new StrategyExecutionResponse.OrderDetail(
                result.getOrderId(),
                result.getTradingSymbol(),
                annotatedType,
                Double.parseDouble(instrument.strike), // HFT: Avoid Double.valueOf boxing
                quantity,
                price,
                StrategyConstants.ORDER_STATUS_COMPLETE
        );
    }

    private double getStopLossPoints(StrategyRequest request) {
        return request.getStopLossPoints() != null
                ? request.getStopLossPoints()
                : strategyConfig.getDefaultStopLossPoints();
    }

    private double getTargetPoints(StrategyRequest request) {
        return request.getTargetPoints() != null
                ? request.getTargetPoints()
                : strategyConfig.getDefaultTargetPoints();
    }

    private String getTradingMode() {
        return unifiedTradingService.isPaperTradingEnabled()
                ? StrategyConstants.TRADING_MODE_PAPER
                : StrategyConstants.TRADING_MODE_LIVE;
    }

    private String getOrderType(StrategyRequest request) {
        return request.getOrderType() != null
                ? request.getOrderType()
                : StrategyConstants.ORDER_TYPE_MARKET;
    }

    private void validateATMOptions(Instrument atmCall, Instrument atmPut, double atmStrike) {
        log.info("ATM Call: {}, ATM Put: {}",
                atmCall != null ? atmCall.tradingsymbol : StrategyConstants.NULL_STRING,
                atmPut != null ? atmPut.tradingsymbol : StrategyConstants.NULL_STRING);

        if (atmCall == null || atmPut == null) {
            throw new RuntimeException(StrategyConstants.ERROR_ATM_OPTIONS_NOT_FOUND + atmStrike);
        }
    }

    private void placeCallLeg(Instrument atmCall, int quantity, String orderType,
                              List<StrategyExecutionResponse.OrderDetail> orderDetails,
                              String tradingMode) throws KiteException, IOException {
        placeLeg(atmCall, quantity, orderType, orderDetails, tradingMode, StrategyConstants.LEG_TYPE_CALL);
    }

    private void placePutLeg(Instrument atmPut, int quantity, String orderType,
                             List<StrategyExecutionResponse.OrderDetail> orderDetails,
                             String tradingMode) throws KiteException, IOException {
        placeLeg(atmPut, quantity, orderType, orderDetails, tradingMode, StrategyConstants.LEG_TYPE_PUT);
    }

    private void placeLeg(Instrument instrument, int quantity, String orderType,
                          List<StrategyExecutionResponse.OrderDetail> orderDetails,
                          String tradingMode, String legType) throws KiteException, IOException {
        log.info(StrategyConstants.LOG_PLACING_ORDER, tradingMode, legType, instrument.tradingsymbol);

        OrderRequest orderRequest = createOrderRequest(instrument.tradingsymbol, StrategyConstants.TRANSACTION_SELL,
                quantity, orderType);
        OrderResponse orderResponse = unifiedTradingService.placeOrder(orderRequest);

        validateOrderResponse(orderResponse, legType);

        double price = getOrderPrice(orderResponse.getOrderId());
        orderDetails.add(createOrderDetail(orderResponse.getOrderId(), instrument, quantity, price));
    }

    private void validateOrderResponse(OrderResponse orderResponse, String legType) {
        if (orderResponse == null || orderResponse.getOrderId() == null ||
                !StrategyConstants.ORDER_STATUS_SUCCESS.equals(orderResponse.getStatus())) {
            String errorMsg = orderResponse != null ? orderResponse.getMessage() : StrategyConstants.ERROR_NO_RESPONSE;
            log.error("{} {}{}", legType, StrategyConstants.ERROR_ORDER_PLACEMENT_FAILED, errorMsg);
            throw new RuntimeException(legType + " " + StrategyConstants.ERROR_ORDER_PLACEMENT_FAILED + errorMsg);
        }
    }

    private StrategyExecutionResponse.OrderDetail createOrderDetail(String orderId, Instrument instrument,
                                                                    int quantity, double price) {
        // HFT: Use pre-computed annotated types to avoid string concatenation on hot path
        final String annotatedType = StrategyConstants.OPTION_TYPE_CALL.equals(instrument.instrument_type)
                ? ANNOTATED_CALL_TYPE
                : ANNOTATED_PUT_TYPE;

        return new StrategyExecutionResponse.OrderDetail(
                orderId,
                instrument.tradingsymbol,
                annotatedType,
                Double.parseDouble(instrument.strike), // HFT: Avoid Double.valueOf boxing
                quantity,
                price,
                StrategyConstants.ORDER_STATUS_COMPLETE
        );
    }

    private double calculateTotalPremium(List<StrategyExecutionResponse.OrderDetail> orderDetails) {
        // HFT: Direct indexed access instead of stream operations
        final StrategyExecutionResponse.OrderDetail firstOrder = orderDetails.get(0);
        final StrategyExecutionResponse.OrderDetail secondOrder = orderDetails.get(1);
        final double callPrice = firstOrder.getPrice();
        final double putPrice = secondOrder.getPrice();
        final int quantity = firstOrder.getQuantity();

        log.info(StrategyConstants.LOG_BOTH_LEGS_PLACED, getTradingMode(), callPrice, putPrice);

        return (callPrice + putPrice) * quantity;
    }

    private StrategyExecutionResponse buildSuccessResponse(String executionId,
                                                           List<StrategyExecutionResponse.OrderDetail> orderDetails,
                                                           double totalPremium,
                                                           double stopLossPoints,
                                                           double targetPoints,
                                                           String tradingMode) {
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

    private void setupMonitoring(String executionId, Instrument callInstrument, Instrument putInstrument,
                                 String callOrderId, String putOrderId,
                                 int quantity,
                                 double stopLossPoints, double targetPoints,
                                 StrategyCompletionCallback completionCallback) {

        // HFT: Spawn async task for monitoring setup to avoid blocking the main thread
        final String ownerUserId = CurrentUserContext.getUserId();
        CompletableFuture.runAsync(() -> {
            // Restore user context in executor thread
            if (ownerUserId != null && !ownerUserId.isBlank()) {
                CurrentUserContext.setUserId(ownerUserId);
            }
            try {
                setupMonitoringInternal(executionId, callInstrument, putInstrument,
                        callOrderId, putOrderId, quantity, stopLossPoints, targetPoints, completionCallback);
            } finally {
                CurrentUserContext.clear();
            }
        }, EXIT_ORDER_EXECUTOR).exceptionally(ex -> {
            log.error("Error setting up monitoring for execution {}: {}", executionId, ex.getMessage(), ex);
            return null;
        });
    }

    /**
     * Internal monitoring setup - runs asynchronously for HFT optimization
     */
    private void setupMonitoringInternal(String executionId, Instrument callInstrument, Instrument putInstrument,
                                         String callOrderId, String putOrderId,
                                         int quantity,
                                         double stopLossPoints, double targetPoints,
                                         StrategyCompletionCallback completionCallback) {
        try {
            // HFT: Parallel fetch of order histories for both legs
            CompletableFuture<List<Order>> callHistoryFuture = CompletableFuture.supplyAsync(() -> {
                try {
                    return unifiedTradingService.getOrderHistory(callOrderId);
                } catch (Exception | KiteException e) {
                    log.error("Failed to fetch call order history: {}", e.getMessage());
                    return Collections.<Order>emptyList();
                }
            }, EXIT_ORDER_EXECUTOR);

            CompletableFuture<List<Order>> putHistoryFuture = CompletableFuture.supplyAsync(() -> {
                try {
                    return unifiedTradingService.getOrderHistory(putOrderId);
                } catch (Exception | KiteException e) {
                    log.error("Failed to fetch put order history: {}", e.getMessage());
                    return Collections.<Order>emptyList();
                }
            }, EXIT_ORDER_EXECUTOR);

            // HFT: Wait for both histories in parallel
            List<Order> callOrderHistory = callHistoryFuture.join();
            List<Order> putOrderHistory = putHistoryFuture.join();

            if (!validateOrderHistories(callOrderHistory, putOrderHistory, callOrderId, putOrderId)) {
                return;
            }

            final Order latestCallOrder = getLatestOrder(callOrderHistory);
            final Order latestPutOrder = getLatestOrder(putOrderHistory);

            if (!validateOrderCompletion(latestCallOrder, latestPutOrder, callOrderId, putOrderId)) {
                return;
            }

            // HFT: Parallel fetch of order prices
            CompletableFuture<Double> callPriceFuture = CompletableFuture.supplyAsync(() -> {
                try {
                    return getOrderPrice(callOrderId);
                } catch (Exception | KiteException e) {
                    log.error("Failed to fetch call order price: {}", e.getMessage());
                    return 0.0;
                }
            }, EXIT_ORDER_EXECUTOR);

            CompletableFuture<Double> putPriceFuture = CompletableFuture.supplyAsync(() -> {
                try {
                    return getOrderPrice(putOrderId);
                } catch (Exception | KiteException e) {
                    log.error("Failed to fetch put order price: {}", e.getMessage());
                    return 0.0;
                }
            }, EXIT_ORDER_EXECUTOR);

            final double callEntryPrice = callPriceFuture.join();
            final double putEntryPrice = putPriceFuture.join();

            if (!validateEntryPrices(callEntryPrice, putEntryPrice)) {
                return;
            }

            log.info("Orders validated - Call: {} (Price: {}), Put: {} (Price: {})",
                    latestCallOrder.status, callEntryPrice, latestPutOrder.status, putEntryPrice);

            PositionMonitor monitor = createPositionMonitor(executionId, stopLossPoints, targetPoints,
                    callOrderId, putOrderId, callInstrument, putInstrument,
                    callEntryPrice, putEntryPrice, quantity, completionCallback);

            startWebSocketMonitoring(executionId, monitor, callEntryPrice, putEntryPrice);

        } catch (Exception e) {
            log.error("Error setting up monitoring for execution {}: {}", executionId, e.getMessage(), e);
        }
    }

    private boolean validateOrderHistories(List<Order> callOrderHistory, List<Order> putOrderHistory,
                                           String callOrderId, String putOrderId) {
        if (callOrderHistory.isEmpty() || putOrderHistory.isEmpty()) {
            log.error(StrategyConstants.ERROR_ORDER_HISTORY_FETCH, callOrderId, putOrderId);
            return false;
        }
        return true;
    }

    private Order getLatestOrder(List<Order> orderHistory) {
        return orderHistory.get(orderHistory.size() - 1);
    }

    private boolean validateOrderCompletion(Order latestCallOrder, Order latestPutOrder,
                                            String callOrderId, String putOrderId) {
        if (!StrategyConstants.ORDER_STATUS_COMPLETE.equals(latestCallOrder.status)) {
            log.warn(StrategyConstants.LOG_ORDER_NOT_COMPLETE, "Call", callOrderId, latestCallOrder.status);
            return false;
        }

        if (!StrategyConstants.ORDER_STATUS_COMPLETE.equals(latestPutOrder.status)) {
            log.warn(StrategyConstants.LOG_ORDER_NOT_COMPLETE, "Put", putOrderId, latestPutOrder.status);
            return false;
        }

        return true;
    }

    private boolean validateEntryPrices(double callEntryPrice, double putEntryPrice) {
        if (callEntryPrice == 0.0 || putEntryPrice == 0.0) {
            log.error(StrategyConstants.ERROR_INVALID_ENTRY_PRICE, callEntryPrice, putEntryPrice);
            return false;
        }
        return true;
    }

    private PositionMonitor createPositionMonitor(String executionId, double stopLossPoints, double targetPoints,
                                                  String callOrderId, String putOrderId,
                                                  Instrument callInstrument, Instrument putInstrument,
                                                  double callEntryPrice, double putEntryPrice,
                                                  int quantity,
                                                  StrategyCompletionCallback completionCallback) {

        // SELL ATM straddle: short volatility exposure -> use SHORT direction
        PositionMonitor monitor = new PositionMonitor(executionId, stopLossPoints, targetPoints,
                PositionMonitor.PositionDirection.SHORT);

        monitor.addLeg(callOrderId, callInstrument.tradingsymbol, callInstrument.instrument_token,
                callEntryPrice, quantity, StrategyConstants.OPTION_TYPE_CALL);

        monitor.addLeg(putOrderId, putInstrument.tradingsymbol, putInstrument.instrument_token,
                putEntryPrice, quantity, StrategyConstants.OPTION_TYPE_PUT);

        String ownerUserId = com.tradingbot.util.CurrentUserContext.getUserId();

        monitor.setExitCallback(reason -> {
            String previousUser = com.tradingbot.util.CurrentUserContext.getUserId();
            try {
                if (ownerUserId != null && !ownerUserId.isBlank()) {
                    com.tradingbot.util.CurrentUserContext.setUserId(ownerUserId);
                }
                log.warn("Exit triggered for execution {}: {}", executionId, reason);
                exitAllLegs(executionId, callInstrument.tradingsymbol,
                        putInstrument.tradingsymbol, quantity, reason, completionCallback);
            } finally {
                if (previousUser != null && !previousUser.isBlank()) {
                    com.tradingbot.util.CurrentUserContext.setUserId(previousUser);
                } else {
                    com.tradingbot.util.CurrentUserContext.clear();
                }
            }
        });

        monitor.setIndividualLegExitCallback((legSymbol, reason) -> {
            String previousUser = com.tradingbot.util.CurrentUserContext.getUserId();
            try {
                if (ownerUserId != null && !ownerUserId.isBlank()) {
                    com.tradingbot.util.CurrentUserContext.setUserId(ownerUserId);
                }
                log.warn("Individual leg exit triggered for execution {}: leg={}, reason={}",
                        executionId, legSymbol, reason);
                exitIndividualLeg(executionId, legSymbol, quantity, reason, monitor, completionCallback);
            } finally {
                if (previousUser != null && !previousUser.isBlank()) {
                    com.tradingbot.util.CurrentUserContext.setUserId(previousUser);
                } else {
                    com.tradingbot.util.CurrentUserContext.clear();
                }
            }
        });

        return monitor;
    }

    private void startWebSocketMonitoring(String executionId, PositionMonitor monitor,
                                          double callEntryPrice, double putEntryPrice) {
        double totalPremium = (callEntryPrice + putEntryPrice) * monitor.getLegs().get(0).getQuantity();
        log.info("Starting monitoring for execution {} with total premium: {}", executionId, totalPremium);

        if (!webSocketService.isConnected()) {
            webSocketService.connect();
        }

        webSocketService.startMonitoring(executionId, monitor);
        log.info("Position monitoring started for execution: {}", executionId);
    }

    private void exitAllLegs(String executionId, String callSymbol, String putSymbol,
                             int quantity, String reason, StrategyCompletionCallback completionCallback) {

        exitAllLegsAlternate(executionId, reason, completionCallback);
        /*try {
            String tradingMode = getTradingMode();
            log.info(StrategyConstants.LOG_EXITING_LEGS, tradingMode, executionId, reason);

            webSocketService.getMonitor(executionId).ifPresent(monitor -> {
                if (monitor.getLegsBySymbol().containsKey(callSymbol)) {
                    try {
                        OrderRequest callExitOrder = createOrderRequest(callSymbol, StrategyConstants.TRANSACTION_BUY,
                                quantity, StrategyConstants.ORDER_TYPE_MARKET);
                        OrderResponse callExitResponse = unifiedTradingService.placeOrder(callExitOrder);

                        if (StrategyConstants.ORDER_STATUS_SUCCESS.equals(callExitResponse.getStatus())) {
                            log.info(StrategyConstants.LOG_LEG_EXITED, tradingMode, "Call", callExitResponse.getOrderId());
                        } else {
                            log.error("Failed to exit Call leg: {}", callExitResponse.getMessage());
                        }
                    } catch (Exception | KiteException e) {
                        log.error("Error exiting Call leg for execution {}: {}", executionId, e.getMessage(), e);
                    }
                } else {
                    log.info("Call leg already exited or not monitored for execution {}", executionId);
                }

                if (monitor.getLegsBySymbol().containsKey(putSymbol)) {
                    try {
                        OrderRequest putExitOrder = createOrderRequest(putSymbol, StrategyConstants.TRANSACTION_BUY,
                                quantity, StrategyConstants.ORDER_TYPE_MARKET);
                        OrderResponse putExitResponse = unifiedTradingService.placeOrder(putExitOrder);

                        if (StrategyConstants.ORDER_STATUS_SUCCESS.equals(putExitResponse.getStatus())) {
                            log.info(StrategyConstants.LOG_LEG_EXITED, tradingMode, "Put", putExitResponse.getOrderId());
                        } else {
                            log.error("Failed to exit Put leg: {}", putExitResponse.getMessage());
                        }
                    } catch (Exception | KiteException e) {
                        log.error("Error exiting Put leg for execution {}: {}", executionId, e.getMessage(), e);
                    }
                } else {
                    log.info("Put leg already exited or not monitored for execution {}", executionId);
                }

                webSocketService.stopMonitoring(executionId);

                if (completionCallback != null) {
                    StrategyCompletionReason mappedReason = reason != null && reason.toUpperCase().contains("STOP")
                            ? StrategyCompletionReason.STOPLOSS_HIT
                            : StrategyCompletionReason.TARGET_HIT;
                    completionCallback.onStrategyCompleted(executionId, mappedReason);
                }

                log.info(StrategyConstants.LOG_ALL_LEGS_EXITED, tradingMode, executionId);
                monitor.stop();
            });
        } catch (Exception e) {
            log.error("Error during exitAllLegs for execution {}: {}", executionId, e.getMessage(), e);
        }*/
    }

    private void exitIndividualLeg(String executionId, String legSymbol, int quantity, String reason,
                                   PositionMonitor monitor, StrategyCompletionCallback completionCallback) {
        try {
            String tradingMode = getTradingMode();
            String legType = legSymbol.contains(StrategyConstants.OPTION_TYPE_CALL) ? "Call" : "Put";

            log.info("[{}] Exiting individual {} leg for execution {}: Symbol={}, Reason={}",
                    tradingMode, legType, executionId, legSymbol, reason);

            // BUY back the leg to exit short
            OrderRequest exitOrder = createOrderRequest(legSymbol, StrategyConstants.TRANSACTION_BUY,
                    quantity, StrategyConstants.ORDER_TYPE_MARKET);
            OrderResponse exitResponse = unifiedTradingService.placeOrder(exitOrder);

            if (StrategyConstants.ORDER_STATUS_SUCCESS.equals(exitResponse.getStatus())) {
                log.info(StrategyConstants.LOG_LEG_EXITED, tradingMode, legType, exitResponse.getOrderId());
            } else {
                log.error("Failed to exit {} leg: {}", legType, exitResponse.getMessage());
            }

            if (monitor.getLegs().isEmpty()) {
                log.info("All legs have been closed individually for execution {}, stopping monitoring", executionId);
                webSocketService.stopMonitoring(executionId);

                if (completionCallback != null) {
                    completionCallback.onStrategyCompleted(executionId, StrategyCompletionReason.STOPLOSS_HIT);
                }
            } else {
                log.info("Remaining legs still being monitored for execution {}: {}", executionId, monitor.getLegs().size());
            }
        } catch (Exception | KiteException e) {
            log.error("Error exiting individual leg {} for execution {}: {}", legSymbol, executionId, e.getMessage(), e);
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

    private void exitAllLegsAlternate(String executionId, String reason, StrategyCompletionCallback completionCallback){
        String userId = CurrentUserContext.getRequiredUserId();
        log.info("Stopping strategy: {} by user {}", executionId, userId);

        StrategyExecution execution = strategyService.getStrategy(executionId);
        if (execution == null || !userId.equals(execution.getUserId())) {
            throw new IllegalArgumentException("Strategy not found: " + executionId);
        }

        if (execution.getStatus() != StrategyStatus.ACTIVE) {
            throw new IllegalStateException("Strategy is not active. Current status: " + execution.getStatus());
        }

        List<StrategyExecution.OrderLeg> orderLegs = execution.getOrderLegs();
        if (orderLegs == null || orderLegs.isEmpty()) {
            throw new IllegalStateException("No order legs found for strategy: " + executionId);
        }

        final String tradingMode = getTradingMode();

        // OPTIMIZED: Process all exit orders in parallel for minimum latency
        List<CompletableFuture<Map<String, String>>> exitFutures = new ArrayList<>(orderLegs.size());

        for (StrategyExecution.OrderLeg leg : orderLegs) {
            exitFutures.add(CompletableFuture.supplyAsync(() -> {
                // Restore user context in executor thread
                CurrentUserContext.setUserId(userId);
                try {
                    return processLegExit(leg, tradingMode);
                } finally {
                    CurrentUserContext.clear();
                }
            }, EXIT_ORDER_EXECUTOR));
        }

        // Wait for all exit orders to complete and collect results
        List<Map<String, String>> exitOrders = new ArrayList<>();
        int successCount = 0;
        int failureCount = 0;

        for (CompletableFuture<Map<String, String>> future : exitFutures) {
            try {
                Map<String, String> result = future.join();
                exitOrders.add(result);
                if (STATUS_SUCCESS.equals(result.get("status"))) {
                    successCount++;
                } else {
                    failureCount++;
                }
            } catch (Exception e) {
                failureCount++;
                log.error("Error waiting for exit order: {}", e.getMessage());
            }
        }

        execution.setOrderLegs(orderLegs);

        // Stop monitoring for this execution
        try {
            webSocketService.stopMonitoring(executionId);
            log.info("[{} MODE] Stopped monitoring for execution {}", tradingMode, executionId);
        } catch (Exception e) {
            log.error("Error stopping monitoring for execution {}: {}", executionId, e.getMessage());
        }

        if (completionCallback != null) {
            StrategyCompletionReason mappedReason = reason != null && reason.toUpperCase().contains("STOP")
                    ? StrategyCompletionReason.STOPLOSS_HIT
                    : StrategyCompletionReason.TARGET_HIT;
            completionCallback.onStrategyCompleted(executionId, mappedReason);
        }

        log.info("[{} MODE] Exited all legs for execution {} - {} closed successfully, {} failed",
                tradingMode, executionId, successCount, failureCount);
    }

    /**
     * Process a single leg exit - extracted for parallel execution
     */
    private Map<String, String> processLegExit(StrategyExecution.OrderLeg leg, String tradingMode) {
        Map<String, String> orderResult = new HashMap<>();
        orderResult.put("tradingSymbol", leg.getTradingSymbol());
        orderResult.put("optionType", leg.getOptionType());

        try {
            leg.setLifecycleState(StrategyExecution.LegLifecycleState.EXIT_PENDING);
            leg.setExitRequestedAt(System.currentTimeMillis());

            String exitTransactionType = strategyService.determineExitTransactionType(leg);

            OrderRequest exitOrder = new OrderRequest();
            exitOrder.setTradingSymbol(leg.getTradingSymbol());
            exitOrder.setExchange(EXCHANGE_NFO);
            exitOrder.setTransactionType(exitTransactionType);
            exitOrder.setQuantity(leg.getQuantity());
            exitOrder.setProduct(PRODUCT_MIS);
            exitOrder.setOrderType(ORDER_TYPE_MARKET);
            exitOrder.setValidity(VALIDITY_DAY);

            OrderResponse response = unifiedTradingService.placeOrder(exitOrder);

            leg.setExitOrderId(response.getOrderId());
            leg.setExitTransactionType(exitTransactionType);
            leg.setExitQuantity(leg.getQuantity());
            leg.setExitStatus(response.getStatus());
            leg.setExitMessage(response.getMessage());
            leg.setExitTimestamp(System.currentTimeMillis());

            Double exitPrice = strategyService.resolveOrderFillPrice(response.getOrderId());
            if (exitPrice != null) {
                leg.setExitPrice(exitPrice);
                leg.setRealizedPnl(strategyService.calculateRealizedPnl(leg, exitPrice));
            }

            orderResult.put("exitOrderId", response.getOrderId());
            orderResult.put("status", response.getStatus());
            orderResult.put("message", response.getMessage());

            if (STATUS_SUCCESS.equals(response.getStatus())) {
                leg.setLifecycleState(StrategyExecution.LegLifecycleState.EXITED);
            } else {
                leg.setLifecycleState(StrategyExecution.LegLifecycleState.EXIT_FAILED);
                log.error("Failed to close {} leg: {} - {}", leg.getOptionType(), leg.getTradingSymbol(), response.getMessage());
            }

        } catch (KiteException | IOException e) {
            leg.setLifecycleState(StrategyExecution.LegLifecycleState.EXIT_FAILED);
            log.error("Error closing {} leg: {}", leg.getOptionType(), leg.getTradingSymbol(), e);
            orderResult.put("status", STATUS_FAILED);
            orderResult.put("message", "Exception: " + e.getMessage());
        } catch (Exception e) {
            leg.setLifecycleState(StrategyExecution.LegLifecycleState.EXIT_FAILED);
            log.error("Unexpected error closing {} leg: {}", leg.getOptionType(), leg.getTradingSymbol(), e);
            orderResult.put("status", STATUS_FAILED);
            orderResult.put("message", "Exception: " + e.getMessage());
        }

        return orderResult;
    }
}
