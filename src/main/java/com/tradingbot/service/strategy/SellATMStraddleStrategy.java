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
import com.tradingbot.model.StrategyCompletionReason;
import com.tradingbot.model.StrategyExecution;
import com.tradingbot.model.StrategyStatus;
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
import com.zerodhatech.models.Order;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
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
 *
 * HFT Optimizations Applied:
 * - O(1) instrument lookup via pre-built HashMap index
 * - Pre-parsed strike values to avoid Double.parseDouble() on hot path
 * - Parallel order placement for both legs
 * - High-priority dedicated thread pool for exit orders
 * - Pre-computed string constants to avoid concatenation
 */
@Slf4j
@Component
public class SellATMStraddleStrategy extends BaseStrategy {

    private final WebSocketService webSocketService;
    private final StrategyConfig strategyConfig;
    private final StrategyService strategyService;
    private final VolatilityFilterService volatilityFilterService;
    private final VolatilityConfig volatilityConfig;

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

    // ==================== HFT OPTIMIZATION: Instrument Index Key ====================
    /**
     * Immutable key for O(1) instrument lookup by strike and option type.
     * Uses primitive long for strike (multiplied by 100 to avoid floating point)
     * and interned string for type to ensure fast hashCode/equals.
     */
    private record InstrumentKey(long strikeX100, String optionType) {
        static InstrumentKey of(double strike, String optionType) {
            return new InstrumentKey((long) (strike * 100), optionType.intern());
        }
    }

    // Pre-computed index for fast instrument lookup - reused across executions
    private final Map<InstrumentKey, Instrument> instrumentIndex = new HashMap<>(256);

    /**
     * HFT OPTIMIZATION: Build instrument index for O(1) lookups.
     * Converts O(n) linear search to O(1) HashMap lookup.
     * Pre-parses strike values to avoid Double.parseDouble() on hot path.
     *
     * @param instruments List of instruments to index
     */
    private void buildInstrumentIndex(List<Instrument> instruments) {
        // Clear previous index
        instrumentIndex.clear();

        final int size = instruments.size();
        for (int i = 0; i < size; i++) {
            final Instrument inst = instruments.get(i);
            final String optionType = inst.instrument_type;

            // Only index CE and PE options
            if (StrategyConstants.OPTION_TYPE_CALL.equals(optionType) ||
                StrategyConstants.OPTION_TYPE_PUT.equals(optionType)) {
                try {
                    final double strike = Double.parseDouble(inst.strike);
                    instrumentIndex.put(InstrumentKey.of(strike, optionType), inst);
                } catch (NumberFormatException e) {
                    // Skip instruments with invalid strike format
                    log.trace("Skipping instrument with invalid strike: {}", inst.tradingsymbol);
                }
            }
        }
        log.debug("Built instrument index with {} entries", instrumentIndex.size());
    }

    public SellATMStraddleStrategy(TradingService tradingService,
                                   UnifiedTradingService unifiedTradingService,
                                   Map<String, Integer> lotSizeCache,
                                   WebSocketService webSocketService,
                                   StrategyConfig strategyConfig,
                                   @Lazy StrategyService strategyService,
                                   DeltaCacheService deltaCacheService,
                                   VolatilityFilterService volatilityFilterService,
                                   VolatilityConfig volatilityConfig) {
        super(tradingService, unifiedTradingService, lotSizeCache, deltaCacheService);
        this.webSocketService = webSocketService;
        this.strategyConfig = strategyConfig;
        this.strategyService = strategyService;
        this.volatilityFilterService = volatilityFilterService;
        this.volatilityConfig = volatilityConfig;
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
        final double targetDecayPct = getTargetDecayPct(request);
        final double stopLossExpansionPct = getStopLossExpansionPct(request);
        final SlTargetMode slTargetMode = getSlTargetMode(request);
        final String tradingMode = getTradingMode();

        log.info(StrategyConstants.LOG_EXECUTING_STRATEGY,
                tradingMode, instrumentType, stopLossPoints, targetPoints);

        log.info("[{}] SL/Target mode: {}", tradingMode, slTargetMode);

        if (slTargetMode == SlTargetMode.PREMIUM || strategyConfig.isPremiumBasedExitEnabled()) {
            log.info("[{}] Premium-based exit parameters: targetDecayPct={}%, stopLossExpansionPct={}%",
                    tradingMode, targetDecayPct , stopLossExpansionPct);
        }

        // ==================== VOLATILITY FILTER CHECK ====================
        // Check VIX conditions before proceeding with straddle placement.
        // If VIX is flat or falling, skip this candle to avoid unfavorable conditions.
        /*if (volatilityConfig.isEnabled()) {
            // Determine if this is a backtest run (paper trading is NOT backtest)
            // For now, we treat all executions as live/paper (not historical replay)
            // TODO: Add explicit backtest flag to StrategyRequest if historical replay is needed
            boolean isBacktest = false;

            VolatilityFilterService.VolatilityFilterResult vixResult =
                    volatilityFilterService.shouldAllowTrade(isBacktest);

            if (!vixResult.allowed()) {
                log.warn("[{}] Volatility filter BLOCKED straddle placement for execution {}: {}",
                        tradingMode, executionId, vixResult.reason());
                log.info("[{}] VIX Details - Current: {}, PrevClose: {}, 5minAgo: {}, PctChange: {}%",
                        tradingMode,
                        vixResult.currentVix(),
                        vixResult.previousClose(),
                        vixResult.fiveMinuteAgoVix(),
                        vixResult.percentageChange());

                // Return SKIPPED response - no positions opened, no state transitions
                return buildSkippedResponse(executionId, tradingMode, vixResult);
            }

            log.info("[{}] Volatility filter PASSED for execution {}: {}",
                    tradingMode, executionId, vixResult.reason());
        }*/
        // ==================== END VOLATILITY FILTER ====================

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

        // HFT: Build instrument index for fast lookups
        buildInstrumentIndex(instruments);

        // HFT: Find both instruments using pre-computed index
        Instrument atmCall = instrumentIndex.get(InstrumentKey.of(atmStrike, StrategyConstants.OPTION_TYPE_CALL));
        Instrument atmPut = instrumentIndex.get(InstrumentKey.of(atmStrike, StrategyConstants.OPTION_TYPE_PUT));

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
                quantity, stopLossPoints, targetPoints,
                targetDecayPct, stopLossExpansionPct,
                slTargetMode, completionCallback);

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

        // HFT CRITICAL: For straddle, we MUST have both legs. Partial fill is a risk exposure.
        // If partial success, rollback the successful leg to avoid naked option risk.
        // NOTE: This is an ERROR PATH - not the hot path. HFT optimizations are less critical here
        // but we still avoid allocations where possible.
        if (!basketResponse.isAllSuccess()) {
            log.warn("[{}] Partial basket order detected - {} of {} orders placed. Initiating rollback.",
                    tradingMode, basketResponse.getSuccessCount(), basketResponse.getTotalOrders());

            // Attempt to rollback (exit) any successfully placed legs
            rollbackSuccessfulLegs(basketResponse, quantity, tradingMode, executionId);

            // Build detailed error message including failed leg info
            // HFT: Use StringBuilder with direct append (avoid String.format overhead)
            StringBuilder errorDetails = new StringBuilder(128);
            errorDetails.append("Straddle requires both legs. Partial fill detected: ");

            final List<BasketOrderResponse.BasketOrderResult> results = basketResponse.getOrderResults();
            final int resultCount = results.size();
            for (int i = 0; i < resultCount; i++) {
                BasketOrderResponse.BasketOrderResult result = results.get(i);
                if (!STATUS_SUCCESS.equals(result.getStatus())) {
                    errorDetails.append('[')
                            .append(result.getLegType())
                            .append(" leg FAILED: ")
                            .append(result.getMessage())
                            .append("] ");
                }
            }
            throw new RuntimeException(errorDetails.toString());
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

        // This check is now a safeguard - should not reach here with partial fills
        if (orderDetails.size() < 2) {
            String errorMsg = String.format("Expected 2 order details but got %d. Basket order validation failed.",
                    orderDetails.size());
            log.error(errorMsg);
            throw new RuntimeException(errorMsg);
        }

        log.info("[{}] Basket order completed - {} orders placed successfully", tradingMode, orderDetails.size());

        return orderDetails;
    }

    /**
     * HFT CRITICAL: Rollback (exit) successfully placed legs when basket order is partial.
     * This prevents naked option exposure from incomplete straddles.
     *
     * For SELL straddle, we BUY back the successful leg to close the position.
     *
     * NOTE: This executes only on ERROR PATH (partial fill), not the normal hot path.
     * Still optimized to minimize latency for risk management.
     *
     * @param basketResponse The basket order response containing order results
     * @param quantity The quantity to exit
     * @param tradingMode Trading mode (PAPER/LIVE) for logging
     * @param executionId Execution ID for traceability
     */
    private void rollbackSuccessfulLegs(BasketOrderResponse basketResponse, int quantity,
                                         String tradingMode, String executionId) {
        log.warn("[{}] ROLLBACK: Initiating rollback for execution {} due to partial fill",
                tradingMode, executionId);

        final List<BasketOrderResponse.BasketOrderResult> results = basketResponse.getOrderResults();
        final int resultCount = results.size();

        // HFT: Pre-size ArrayList to avoid resizing (max 2 legs for straddle)
        List<CompletableFuture<Boolean>> rollbackFutures = new ArrayList<>(2);

        // HFT: Use indexed loop to avoid iterator allocation
        for (int i = 0; i < resultCount; i++) {
            final BasketOrderResponse.BasketOrderResult result = results.get(i);

            if (STATUS_SUCCESS.equals(result.getStatus()) && result.getOrderId() != null) {
                // HFT: Capture final variables for lambda (avoids repeated getter calls)
                final String tradingSymbol = result.getTradingSymbol();
                final String legType = result.getLegType();

                log.warn("[{}] ROLLBACK: Exiting successful {} leg - Symbol: {}, OrderId: {}",
                        tradingMode, legType, tradingSymbol, result.getOrderId());

                // Execute rollback asynchronously for speed, but track completion
                // Capture user context for executor thread
                final String capturedUserId = CurrentUserContext.getUserId();
                CompletableFuture<Boolean> rollbackFuture = CompletableFuture.supplyAsync(() -> {
                    // Restore user context in executor thread
                    if (capturedUserId != null) {
                        CurrentUserContext.setUserId(capturedUserId);
                    }
                    try {
                        // BUY back to close the SELL position
                        OrderRequest exitOrder = createOrderRequest(
                                tradingSymbol,
                                StrategyConstants.TRANSACTION_BUY,
                                quantity,
                                StrategyConstants.ORDER_TYPE_MARKET
                        );

                        OrderResponse exitResponse;
                        try {
                            exitResponse = unifiedTradingService.placeOrder(exitOrder);
                        } catch (KiteException | IOException e) {
                            log.error("[{}] ROLLBACK ERROR: Failed to exit {} leg {} (API error): {}",
                                    tradingMode, legType, tradingSymbol, e.getMessage(), e);
                            return Boolean.FALSE;
                        }

                        if (StrategyConstants.ORDER_STATUS_SUCCESS.equals(exitResponse.getStatus())) {
                            log.info("[{}] ROLLBACK SUCCESS: {} leg exited - ExitOrderId: {}",
                                    tradingMode, legType, exitResponse.getOrderId());
                            return Boolean.TRUE;
                        } else {
                            log.error("[{}] ROLLBACK FAILED: {} leg exit failed - Message: {}",
                                    tradingMode, legType, exitResponse.getMessage());
                            return Boolean.FALSE;
                        }
                    } catch (Exception e) {
                        log.error("[{}] ROLLBACK ERROR: Failed to exit {} leg {}: {}",
                                tradingMode, legType, tradingSymbol, e.getMessage(), e);
                        return Boolean.FALSE;
                    } finally {
                        CurrentUserContext.clear();
                    }
                }, EXIT_ORDER_EXECUTOR);

                rollbackFutures.add(rollbackFuture);
            }
        }

        // Wait for all rollbacks to complete (with timeout for HFT safety)
        final int futureCount = rollbackFutures.size();
        if (futureCount > 0) {
            try {
                CompletableFuture<Void> allRollbacks = CompletableFuture.allOf(
                        rollbackFutures.toArray(new CompletableFuture[0])
                );

                // Wait max 5 seconds for rollbacks - critical for risk management
                allRollbacks.get(5, java.util.concurrent.TimeUnit.SECONDS);

                // HFT: Count results using primitive loop (avoid stream overhead)
                int successCount = 0;
                for (int i = 0; i < futureCount; i++) {
                    if (Boolean.TRUE.equals(rollbackFutures.get(i).join())) {
                        successCount++;
                    }
                }

                log.info("[{}] ROLLBACK COMPLETE: {}/{} legs successfully rolled back for execution {}",
                        tradingMode, successCount, futureCount, executionId);

                if (successCount < futureCount) {
                    log.error("[{}] ROLLBACK INCOMPLETE: {} legs could not be rolled back. MANUAL INTERVENTION REQUIRED!",
                            tradingMode, futureCount - successCount);
                }

            } catch (java.util.concurrent.TimeoutException e) {
                log.error("[{}] ROLLBACK TIMEOUT: Rollback did not complete within 5 seconds for execution {}. " +
                        "MANUAL INTERVENTION REQUIRED!", tradingMode, executionId);
            } catch (Exception e) {
                log.error("[{}] ROLLBACK ERROR: Unexpected error during rollback for execution {}: {}",
                        tradingMode, executionId, e.getMessage(), e);
            }
        }
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

    /**
     * Resolve target decay percentage from request with config fallback.
     * Used for premium-based exit when enabled.
     *
     * @param request the strategy request
     * @return target decay percentage (e.g., 0.05 for 5%)
     */
    private double getTargetDecayPct(StrategyRequest request) {
        return request.getTargetDecayPct() != null
                ? request.getTargetDecayPct()
                : strategyConfig.getTargetDecayPct();
    }

    /**
     * Resolve stop loss expansion percentage from request with config fallback.
     * Used for premium-based exit when enabled.
     *
     * @param request the strategy request
     * @return stop loss expansion percentage (e.g., 0.10 for 10%)
     */
    private double getStopLossExpansionPct(StrategyRequest request) {
        return request.getStopLossExpansionPct() != null
                ? request.getStopLossExpansionPct()
                : strategyConfig.getStopLossExpansionPct();
    }

    /**
     * Resolve SL/Target calculation mode from request.
     * Converts frontend string values to internal enum representation.
     * <p>
     * Frontend values:
     * <ul>
     *   <li>"points" → POINTS: Fixed point-based exits using stopLossPoints/targetPoints</li>
     *   <li>"percentage" → PREMIUM: Percentage-based on combined entry premium using targetDecayPct/stopLossExpansionPct</li>
     * </ul>
     *
     * @param request the strategy request
     * @return SlTargetMode enum, defaults to POINTS if not specified (or PREMIUM if config has premiumBasedExitEnabled)
     */
    private SlTargetMode getSlTargetMode(StrategyRequest request) {
        String modeFromRequest = request.getSlTargetMode();

        if (modeFromRequest != null && !modeFromRequest.isBlank()) {
            String normalizedMode = modeFromRequest.trim().toLowerCase();

            return switch (normalizedMode) {
                case "percentage", "premium" -> SlTargetMode.PREMIUM;
                case "points", "fixed" -> SlTargetMode.POINTS;
                case "mtm" -> SlTargetMode.MTM;
                default -> {
                    log.warn("Unknown slTargetMode '{}', defaulting to POINTS", modeFromRequest);
                    yield SlTargetMode.POINTS;
                }
            };
        }

        // Default based on config: if premiumBasedExitEnabled is true in config, default to PREMIUM; else POINTS
        return strategyConfig.isPremiumBasedExitEnabled() ? SlTargetMode.PREMIUM : SlTargetMode.POINTS;
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

    /**
     * Build a SKIPPED response when the volatility filter blocks straddle placement.
     * No positions are opened, no state transitions occur.
     *
     * @param executionId The execution ID
     * @param tradingMode Trading mode (PAPER/LIVE)
     * @param vixResult The volatility filter result with reasoning
     * @return StrategyExecutionResponse with SKIPPED status
     */
    private StrategyExecutionResponse buildSkippedResponse(String executionId, String tradingMode,
                                                           VolatilityFilterService.VolatilityFilterResult vixResult) {
        StrategyExecutionResponse response = new StrategyExecutionResponse();
        response.setExecutionId(executionId);
        response.setStatus(StrategyStatus.SKIPPED.name());

        // Build detailed message with VIX values
        StringBuilder message = new StringBuilder();
        message.append("[").append(tradingMode).append("] Straddle placement SKIPPED - VIX conditions unfavorable. ");
        message.append(vixResult.reason());

        if (vixResult.currentVix() != null) {
            message.append(" | VIX=").append(vixResult.currentVix());
        }
        if (vixResult.previousClose() != null) {
            message.append(", PrevClose=").append(vixResult.previousClose());
        }
        if (vixResult.percentageChange() != null) {
            message.append(", 5minChange=").append(vixResult.percentageChange()).append("%");
        }

        response.setMessage(message.toString());
        response.setOrders(Collections.emptyList());
        response.setTotalPremium(0.0);
        response.setCurrentValue(0.0);
        response.setProfitLoss(0.0);
        response.setProfitLossPercentage(0.0);

        log.info("[{}] Strategy execution SKIPPED for {}: {}", tradingMode, executionId, vixResult.reason());
        return response;
    }

    private void setupMonitoring(String executionId, Instrument callInstrument, Instrument putInstrument,
                                 String callOrderId, String putOrderId,
                                 int quantity,
                                 double stopLossPoints, double targetPoints,
                                 double targetDecayPct, double stopLossExpansionPct,
                                 SlTargetMode slTargetMode,
                                 StrategyCompletionCallback completionCallback) {

        // HFT: Spawn async task for monitoring setup to avoid blocking the main thread
        final String ownerUserId = CurrentUserContext.getUserId();
        if (ownerUserId == null || ownerUserId.isBlank()) {
            log.error("No user context available for monitoring setup, executionId: {}", executionId);
            return;
        }

        CompletableFuture.runAsync(
            CurrentUserContext.wrapWithContext(() -> {
                setupMonitoringInternal(executionId, callInstrument, putInstrument,
                        callOrderId, putOrderId, quantity, stopLossPoints, targetPoints,
                        targetDecayPct, stopLossExpansionPct, slTargetMode, completionCallback);
            }), EXIT_ORDER_EXECUTOR
        ).exceptionally(ex -> {
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
                                         double targetDecayPct, double stopLossExpansionPct,
                                         SlTargetMode slTargetMode,
                                         StrategyCompletionCallback completionCallback) {
        try {
            // HFT: Parallel fetch of order histories for both legs
            CompletableFuture<List<Order>> callHistoryFuture = CompletableFuture.supplyAsync(
                CurrentUserContext.wrapSupplier(() -> {
                    try {
                        return unifiedTradingService.getOrderHistory(callOrderId);
                    } catch (Exception e) {
                        log.error("Failed to fetch call order history: {}", e.getMessage());
                        return Collections.<Order>emptyList();
                    } catch (KiteException e) {
                        throw new RuntimeException(e);
                    }
                }), EXIT_ORDER_EXECUTOR);

            CompletableFuture<List<Order>> putHistoryFuture = CompletableFuture.supplyAsync(
                CurrentUserContext.wrapSupplier(() -> {
                    try {
                        return unifiedTradingService.getOrderHistory(putOrderId);
                    } catch (Exception | KiteException e) {
                        log.error("Failed to fetch put order history: {}", e.getMessage());
                        return Collections.<Order>emptyList();
                    }
                }), EXIT_ORDER_EXECUTOR);

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
            CompletableFuture<Double> callPriceFuture = CompletableFuture.supplyAsync(
                CurrentUserContext.wrapSupplier(() -> {
                    try {
                        return getOrderPrice(callOrderId);
                    } catch (Exception | KiteException e) {
                        log.error("Failed to fetch call order price: {}", e.getMessage());
                        return 0.0;
                    }
                }), EXIT_ORDER_EXECUTOR);

            CompletableFuture<Double> putPriceFuture = CompletableFuture.supplyAsync(
                CurrentUserContext.wrapSupplier(() -> {
                    try {
                        return getOrderPrice(putOrderId);
                    } catch (Exception | KiteException e) {
                        log.error("Failed to fetch put order price: {}", e.getMessage());
                        return 0.0;
                    }
                }), EXIT_ORDER_EXECUTOR);

            final double callEntryPrice = callPriceFuture.join();
            final double putEntryPrice = putPriceFuture.join();

            if (!validateEntryPrices(callEntryPrice, putEntryPrice)) {
                return;
            }

            log.info("Orders validated - Call: {} (Price: {}), Put: {} (Price: {})",
                    latestCallOrder.status, callEntryPrice, latestPutOrder.status, putEntryPrice);

            PositionMonitorV2 monitor = createPositionMonitor(executionId, stopLossPoints, targetPoints,
                    targetDecayPct, stopLossExpansionPct, slTargetMode,
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

    private PositionMonitorV2 createPositionMonitor(String executionId, double stopLossPoints, double targetPoints,
                                                  double targetDecayPct, double stopLossExpansionPct,
                                                  SlTargetMode slTargetMode,
                                                  String callOrderId, String putOrderId,
                                                  Instrument callInstrument, Instrument putInstrument,
                                                  double callEntryPrice, double putEntryPrice,
                                                  int quantity,
                                                  StrategyCompletionCallback completionCallback) {

        // SELL ATM straddle: short volatility exposure -> use SHORT direction
        // Include trailing stop loss and forced exit time configuration from StrategyConfig
        LocalTime forcedExitTime = parseForcedExitTime(strategyConfig.getAutoSquareOffTime());

        // Calculate combined entry premium for premium-based exit mode
        double combinedEntryPremium = callEntryPrice + putEntryPrice;

        // Determine if premium-based exit should be enabled based on slTargetMode
        // If slTargetMode is PREMIUM, override config setting; otherwise use config default
        boolean premiumBasedExitEnabled = (slTargetMode == SlTargetMode.PREMIUM)
                || (slTargetMode == null && strategyConfig.isPremiumBasedExitEnabled());

        PositionMonitorV2 monitor = new PositionMonitorV2(
                executionId,
                stopLossPoints,
                targetPoints,
                PositionMonitorV2.PositionDirection.SHORT,
                strategyConfig.isTrailingStopEnabled(),
                strategyConfig.getTrailingActivationPoints(),
                strategyConfig.getTrailingDistancePoints(),
                strategyConfig.isAutoSquareOffEnabled(),
                forcedExitTime,
                // Premium-based exit configuration - use resolved values from request/config
                premiumBasedExitEnabled,
                combinedEntryPremium,
                targetDecayPct,
                stopLossExpansionPct,
                slTargetMode
        );

        monitor.addLeg(callOrderId, callInstrument.tradingsymbol, callInstrument.instrument_token,
                callEntryPrice, quantity, StrategyConstants.OPTION_TYPE_CALL);

        monitor.addLeg(putOrderId, putInstrument.tradingsymbol, putInstrument.instrument_token,
                putEntryPrice, quantity, StrategyConstants.OPTION_TYPE_PUT);

        // CLOUD RUN: Capture and store the owner userId in the monitor for context propagation
        // This ensures callbacks can restore the correct user context even in executor/WebSocket threads
        String ownerUserId = com.tradingbot.util.CurrentUserContext.getUserId();
        monitor.setOwnerUserId(ownerUserId);

        monitor.setExitCallback(reason -> {
            String previousUser = com.tradingbot.util.CurrentUserContext.getUserId();
            try {
                // CLOUD RUN: Restore user context from monitor's stored ownerUserId
                String monitorOwner = monitor.getOwnerUserId();
                if (monitorOwner != null && !monitorOwner.isBlank()) {
                    com.tradingbot.util.CurrentUserContext.setUserId(monitorOwner);
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
                // CLOUD RUN: Restore user context from monitor's stored ownerUserId
                String monitorOwner = monitor.getOwnerUserId();
                if (monitorOwner != null && !monitorOwner.isBlank()) {
                    com.tradingbot.util.CurrentUserContext.setUserId(monitorOwner);
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

        // Set leg replacement callback for premium-based individual leg exit
        // This is triggered when a profitable leg is exited and needs to be replaced
        // with a new leg having similar premium to the loss-making leg
        monitor.setLegReplacementCallback((exitedLegSymbol, legTypeToAdd, targetPremium, lossMakingLegSymbol) -> {
            String previousUser = com.tradingbot.util.CurrentUserContext.getUserId();
            try {
                // CLOUD RUN: Restore user context from monitor's stored ownerUserId
                String monitorOwner = monitor.getOwnerUserId();
                if (monitorOwner != null && !monitorOwner.isBlank()) {
                    com.tradingbot.util.CurrentUserContext.setUserId(monitorOwner);
                }
                log.info("Leg replacement triggered for execution {}: exitedLeg={}, newLegType={}, " +
                                "targetPremium={}, referenceLeg={}",
                        executionId, exitedLegSymbol, legTypeToAdd, targetPremium, lossMakingLegSymbol);

                // Execute leg replacement asynchronously to avoid blocking WebSocket thread
                CompletableFuture.runAsync(() -> {
                    String innerPreviousUser = com.tradingbot.util.CurrentUserContext.getUserId();
                    try {
                        String innerMonitorOwner = monitor.getOwnerUserId();
                        if (innerMonitorOwner != null && !innerMonitorOwner.isBlank()) {
                            com.tradingbot.util.CurrentUserContext.setUserId(innerMonitorOwner);
                        }
                        placeReplacementLegOrder(executionId, exitedLegSymbol, legTypeToAdd,
                                targetPremium, lossMakingLegSymbol, quantity, monitor);
                    } finally {
                        if (innerPreviousUser != null && !innerPreviousUser.isBlank()) {
                            com.tradingbot.util.CurrentUserContext.setUserId(innerPreviousUser);
                        } else {
                            com.tradingbot.util.CurrentUserContext.clear();
                        }
                    }
                }, EXIT_ORDER_EXECUTOR);
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

    private void startWebSocketMonitoring(String executionId, PositionMonitorV2 monitor,
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
                    } catch (Exception e) {
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
                    } catch (Exception e) {
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
                                   PositionMonitorV2 monitor, StrategyCompletionCallback completionCallback) {
        String tradingMode = getTradingMode();
        String legType = legSymbol.contains(StrategyConstants.OPTION_TYPE_CALL) ? "Call" : "Put";

        log.info("[{}] Exiting individual {} leg for execution {}: Symbol={}, Reason={}",
                tradingMode, legType, executionId, legSymbol, reason);

        // Retrieve the StrategyExecution and find the matching OrderLeg
        StrategyExecution execution = strategyService.getStrategy(executionId);
        if (execution == null) {
            log.error("Cannot exit individual leg: execution not found for {}", executionId);
            return;
        }

        List<StrategyExecution.OrderLeg> orderLegs = execution.getOrderLegs();
        if (orderLegs == null || orderLegs.isEmpty()) {
            log.error("Cannot exit individual leg: no order legs found for execution {}", executionId);
            return;
        }

        // Find the matching OrderLeg by trading symbol
        StrategyExecution.OrderLeg matchingLeg = orderLegs.stream()
                .filter(leg -> legSymbol.equals(leg.getTradingSymbol()))
                .findFirst()
                .orElse(null);

        if (matchingLeg == null) {
            log.error("Cannot exit individual leg: leg with symbol {} not found in execution {}", legSymbol, executionId);
            return;
        }

        // Skip if leg is already exited or exit is pending
        if (matchingLeg.getLifecycleState() == StrategyExecution.LegLifecycleState.EXITED ||
            matchingLeg.getLifecycleState() == StrategyExecution.LegLifecycleState.EXIT_PENDING) {
            log.warn("Leg {} already {} for execution {}, skipping exit",
                    legSymbol, matchingLeg.getLifecycleState(), executionId);
            return;
        }

        // Delegate to processLegExit for the actual exit order placement
        Map<String, String> result = processLegExit(matchingLeg, tradingMode);

        if (STATUS_SUCCESS.equals(result.get("status"))) {
            log.info(StrategyConstants.LOG_LEG_EXITED, tradingMode, legType, result.get("exitOrderId"));
        } else {
            log.error("Failed to exit {} leg: {}", legType, result.get("message"));
        }

        // Check if all legs have been closed and handle post-exit logic
        boolean allLegsExited = orderLegs.stream()
                .allMatch(leg -> leg.getLifecycleState() == StrategyExecution.LegLifecycleState.EXITED);

        if (allLegsExited || monitor.getLegs().isEmpty()) {
            log.info("All legs have been closed individually for execution {}, stopping monitoring", executionId);
            try {
                webSocketService.stopMonitoring(executionId);
            } catch (Exception e) {
                log.error("Error stopping monitoring for execution {}: {}", executionId, e.getMessage());
            }

            if (completionCallback != null) {
                StrategyCompletionReason mappedReason = reason != null && reason.toUpperCase().contains("STOP")
                        ? StrategyCompletionReason.STOPLOSS_HIT
                        : StrategyCompletionReason.TARGET_HIT;
                completionCallback.onStrategyCompleted(executionId, mappedReason);
            }
        } else {
            long remainingLegs = orderLegs.stream()
                    .filter(leg -> leg.getLifecycleState() != StrategyExecution.LegLifecycleState.EXITED)
                    .count();
            log.info("Remaining legs still being monitored for execution {}: {}", executionId, remainingLegs);
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
            exitFutures.add(CompletableFuture.supplyAsync(
                CurrentUserContext.wrapSupplier(() -> processLegExit(leg, tradingMode)),
                EXIT_ORDER_EXECUTOR
            ));
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
            StrategyCompletionReason mappedReason = mapExitReasonToCompletionReason(reason);
            completionCallback.onStrategyCompleted(executionId, mappedReason);
        }

        log.info("[{} MODE] Exited all legs for execution {} - {} closed successfully, {} failed",
                tradingMode, executionId, successCount, failureCount);
    }

    /**
     * Process a single leg exit - extracted for parallel execution.
     * <p>
     * Thread-safe: validates leg lifecycle state before processing to prevent duplicate exits.
     * If the leg is already in a terminal state (EXITED, EXIT_PENDING, or EXIT_FAILED),
     * returns immediately with an appropriate status without placing any orders.
     *
     * @param leg         the order leg to exit
     * @param tradingMode current trading mode (PAPER or LIVE)
     * @return map containing exit result with keys: tradingSymbol, optionType, status, message, exitOrderId (if applicable)
     */
    private Map<String, String> processLegExit(StrategyExecution.OrderLeg leg, String tradingMode) {
        Map<String, String> orderResult = new HashMap<>();
        orderResult.put("tradingSymbol", leg.getTradingSymbol());
        orderResult.put("optionType", leg.getOptionType());

        // Validate leg lifecycle state - prevent duplicate exit orders
        StrategyExecution.LegLifecycleState currentState = leg.getLifecycleState();
        if (currentState == StrategyExecution.LegLifecycleState.EXITED) {
            log.info("Leg {} is already EXITED, skipping exit order placement", leg.getTradingSymbol());
            orderResult.put("status", STATUS_SUCCESS);
            orderResult.put("message", "Leg already exited");
            orderResult.put("exitOrderId", leg.getExitOrderId());
            return orderResult;
        }
        if (currentState == StrategyExecution.LegLifecycleState.EXIT_PENDING) {
            log.warn("Leg {} has exit already pending (orderId: {}), skipping duplicate exit",
                    leg.getTradingSymbol(), leg.getExitOrderId());
            orderResult.put("status", STATUS_SUCCESS);
            orderResult.put("message", "Exit already pending");
            orderResult.put("exitOrderId", leg.getExitOrderId());
            return orderResult;
        }
        if (currentState == StrategyExecution.LegLifecycleState.EXIT_FAILED) {
            log.warn("Leg {} previous exit failed, retrying exit order", leg.getTradingSymbol());
            // Allow retry for failed exits - fall through to normal processing
        }

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

    /**
     * Parse the forced exit time from configuration string.
     * <p>
     * Expected format: "HH:mm" (e.g., "15:10" for 3:10 PM IST)
     * Falls back to default 15:10 if parsing fails.
     *
     * @param timeString time string in HH:mm format
     * @return LocalTime representing the forced exit cutoff
     */
    private LocalTime parseForcedExitTime(String timeString) {
        if (timeString == null || timeString.isBlank()) {
            log.warn("Forced exit time not configured, using default 15:10 IST");
            return LocalTime.of(15, 10);
        }
        try {
            return LocalTime.parse(timeString, DateTimeFormatter.ofPattern("HH:mm"));
        } catch (Exception e) {
            log.warn("Failed to parse forced exit time '{}', using default 15:10 IST: {}",
                    timeString, e.getMessage());
            return LocalTime.of(15, 10);
        }
    }

    /**
     * Map exit reason string to StrategyCompletionReason enum.
     * <p>
     * Parses the reason string to determine the appropriate completion reason:
     * - TIME_BASED_FORCED_EXIT → TIME_BASED_EXIT
     * - PREMIUM_DECAY_TARGET_HIT → TARGET_HIT (profit)
     * - PREMIUM_EXPANSION_SL_HIT → STOPLOSS_HIT (loss)
     * - Contains "STOP" → STOPLOSS_HIT
     * - Default → TARGET_HIT
     *
     * @param reason exit reason string from PositionMonitor
     * @return appropriate StrategyCompletionReason
     */
    private StrategyCompletionReason mapExitReasonToCompletionReason(String reason) {
        if (reason == null) {
            return StrategyCompletionReason.TARGET_HIT;
        }
        String upperReason = reason.toUpperCase();
        if (upperReason.contains("TIME_BASED_FORCED_EXIT")) {
            return StrategyCompletionReason.TIME_BASED_EXIT;
        }
        // Premium-based exit mappings
        if (upperReason.contains("PREMIUM_DECAY_TARGET_HIT")) {
            return StrategyCompletionReason.TARGET_HIT;  // Premium decay = profit for SHORT
        }
        if (upperReason.contains("PREMIUM_EXPANSION_SL_HIT")) {
            return StrategyCompletionReason.STOPLOSS_HIT;  // Premium expansion = loss for SHORT
        }
        // Fixed-point MTM mappings
        if (upperReason.contains("STOP")) {
            return StrategyCompletionReason.STOPLOSS_HIT;
        }
        return StrategyCompletionReason.TARGET_HIT;
    }

    // ==================== LEG REPLACEMENT SUPPORT ====================

    /**
     * Find an option instrument with premium closest to the target premium.
     * <p>
     * This method searches through the pre-built instrument index to find an option
     * of the specified type (CE or PE) whose current LTP is closest to the target premium.
     * Used for leg replacement when the profitable leg is exited.
     * <p>
     * HFT Optimizations:
     * <ul>
     *   <li>Uses pre-built instrument index for fast iteration</li>
     *   <li>Fetches quotes in batch for efficiency</li>
     *   <li>Early termination when exact match found</li>
     * </ul>
     *
     * @param optionType option type to search for (CE or PE)
     * @param targetPremium target premium to match
     * @param maxPremiumDifference maximum acceptable premium difference (e.g., 10% of target)
     * @return Instrument with closest premium, or null if none found within tolerance
     */
    private Instrument findInstrumentByTargetPremium(String optionType, double targetPremium,
                                                      double maxPremiumDifference) {
        log.info("Finding {} instrument with target premium {} (max diff: {})",
                optionType, targetPremium, maxPremiumDifference);

        // Collect candidate instruments of the specified type
        List<Instrument> candidates = new ArrayList<>();
        for (Map.Entry<InstrumentKey, Instrument> entry : instrumentIndex.entrySet()) {
            if (entry.getKey().optionType().equals(optionType)) {
                candidates.add(entry.getValue());
            }
        }

        if (candidates.isEmpty()) {
            log.warn("No {} instruments found in index", optionType);
            return null;
        }

        log.debug("Found {} candidate {} instruments", candidates.size(), optionType);

        // Batch fetch LTPs for all candidates (more efficient than individual calls)
        // Limit to reasonable number to avoid API overload
        int maxCandidates = Math.min(candidates.size(), 50);
        String[] instrumentIdentifiers = new String[maxCandidates];
        for (int i = 0; i < maxCandidates; i++) {
            instrumentIdentifiers[i] = "NFO:" + candidates.get(i).tradingsymbol;
        }

        Map<String, com.zerodhatech.models.LTPQuote> ltpMap;
        try {
            ltpMap = tradingService.getLTP(instrumentIdentifiers);
        } catch (Exception e) {
            log.error("Failed to fetch LTPs for candidate instruments: {}", e.getMessage(), e);
            return null;
        } catch (KiteException e) {
            throw new RuntimeException(e);
        }

        if (ltpMap == null || ltpMap.isEmpty()) {
            log.warn("No LTP data received for candidate instruments");
            return null;
        }

        // Find instrument with closest premium to target
        Instrument bestMatch = null;
        double bestDifference = Double.MAX_VALUE;

        for (int i = 0; i < maxCandidates; i++) {
            Instrument candidate = candidates.get(i);
            String identifier = "NFO:" + candidate.tradingsymbol;
            com.zerodhatech.models.LTPQuote ltp = ltpMap.get(identifier);

            if (ltp == null || ltp.lastPrice <= 0) {
                continue;
            }

            double currentPremium = ltp.lastPrice;
            double difference = Math.abs(currentPremium - targetPremium);

            if (difference < bestDifference) {
                bestDifference = difference;
                bestMatch = candidate;

                // Early termination if we find an exact or very close match
                if (difference < 0.5) {
                    log.info("Found exact match {} with premium {} (target: {})",
                            candidate.tradingsymbol, currentPremium, targetPremium);
                    break;
                }
            }
        }

        if (bestMatch != null && bestDifference <= maxPremiumDifference) {
            log.info("Found best match {} with premium difference {} (within tolerance {})",
                    bestMatch.tradingsymbol, bestDifference, maxPremiumDifference);
            return bestMatch;
        }

        log.warn("No instrument found within premium tolerance {} for target {}",
                maxPremiumDifference, targetPremium);
        return null;
    }

    /**
     * Place a replacement leg order and add it to the position monitor.
     * <p>
     * This method is called when the leg replacement callback is triggered.
     * It finds an instrument with similar premium to the target, places a SELL order,
     * and adds the new leg to the monitor with adjusted thresholds.
     *
     * @param executionId execution ID for logging and state tracking
     * @param exitedLegSymbol symbol of the exited leg (for logging)
     * @param legType type of leg to add (CE or PE)
     * @param targetPremium target premium for the new leg
     * @param lossMakingLegSymbol symbol of the loss-making leg (for reference)
     * @param quantity order quantity
     * @param monitor PositionMonitor to add the new leg to
     */
    private void placeReplacementLegOrder(String executionId, String exitedLegSymbol,
                                           String legType, double targetPremium,
                                           String lossMakingLegSymbol, int quantity,
                                           PositionMonitorV2 monitor) {
        String tradingMode = getTradingMode();
        log.info("[{}] Placing replacement {} leg for execution {}: exitedLeg={}, targetPremium={}, " +
                        "referenceLeg={}",
                tradingMode, legType, executionId, exitedLegSymbol, targetPremium, lossMakingLegSymbol);

        try {
            // Allow up to 20% premium difference for finding a replacement
            double maxPremiumDiff = targetPremium * 0.20;
            Instrument replacementInstrument = findInstrumentByTargetPremium(legType, targetPremium, maxPremiumDiff);

            if (replacementInstrument == null) {
                log.error("[{}] Could not find replacement {} instrument with target premium {} for execution {}",
                        tradingMode, legType, targetPremium, executionId);
                // Continue monitoring with remaining legs - don't stop the strategy
                return;
            }

            log.info("[{}] Found replacement instrument: {} for execution {}",
                    tradingMode, replacementInstrument.tradingsymbol, executionId);

            // Place SELL order for the replacement leg
            OrderRequest sellOrder = createOrderRequest(
                    replacementInstrument.tradingsymbol,
                    StrategyConstants.TRANSACTION_SELL,
                    quantity,
                    StrategyConstants.ORDER_TYPE_MARKET
            );

            OrderResponse orderResponse;
            try {
                orderResponse = unifiedTradingService.placeOrder(sellOrder);
            } catch (Exception e) {
                log.error("[{}] Failed to place replacement order for execution {}: {}",
                        tradingMode, executionId, e.getMessage(), e);
                return;
            } catch (KiteException e) {
                throw new RuntimeException(e);
            }

            if (!StrategyConstants.ORDER_STATUS_SUCCESS.equals(orderResponse.getStatus())) {
                log.error("[{}] Replacement order failed for execution {}: {}",
                        tradingMode, executionId, orderResponse.getMessage());
                return;
            }

            String newOrderId = orderResponse.getOrderId();
            log.info("[{}] Replacement order placed successfully for execution {}: orderId={}",
                    tradingMode, executionId, newOrderId);

            // Get the fill price for the new leg (fetch LTP since OrderResponse doesn't have average price)
            double fillPrice = targetPremium; // Default to target premium
            try {
                String identifier = "NFO:" + replacementInstrument.tradingsymbol;
                Map<String, com.zerodhatech.models.LTPQuote> ltpMap = tradingService.getLTP(new String[]{identifier});
                if (ltpMap != null && ltpMap.get(identifier) != null && ltpMap.get(identifier).lastPrice > 0) {
                    fillPrice = ltpMap.get(identifier).lastPrice;
                }
            } catch (Exception e) {
                log.warn("Could not fetch LTP for fill price, using target premium: {}", e.getMessage());
            } catch (KiteException e) {
                throw new RuntimeException(e);
            }

            log.info("[{}] Replacement leg fill price for execution {}: {}", tradingMode, executionId, fillPrice);

            // Add the replacement leg to the monitor and adjust thresholds
            monitor.addReplacementLeg(
                    newOrderId,
                    replacementInstrument.tradingsymbol,
                    replacementInstrument.instrument_token,
                    fillPrice,
                    quantity,
                    legType
            );

            // Subscribe to WebSocket updates for the new instrument
            webSocketService.addInstrumentToMonitoring(executionId, replacementInstrument.instrument_token);

            // Update the strategy execution state with the new leg
            try {
                StrategyExecution execution = strategyService.getStrategy(executionId);
                if (execution != null && execution.getOrderLegs() != null) {
                    StrategyExecution.OrderLeg newLeg = StrategyExecution.OrderLeg.builder()
                            .orderId(newOrderId)
                            .tradingSymbol(replacementInstrument.tradingsymbol)
                            .optionType(legType)
                            .entryPrice(fillPrice)
                            .quantity(quantity)
                            .entryTransactionType(StrategyConstants.TRANSACTION_SELL)
                            .entryTimestamp(System.currentTimeMillis())
                            .lifecycleState(StrategyExecution.LegLifecycleState.OPEN)
                            .build();

                    execution.getOrderLegs().add(newLeg);
                    log.info("[{}] Strategy execution state updated with replacement leg for {}",
                            tradingMode, executionId);
                }
            } catch (Exception e) {
                log.warn("[{}] Could not update strategy execution state with new leg: {}",
                        tradingMode, e.getMessage());
                // Non-fatal: monitoring will continue regardless
            }

            log.info("[{}] Replacement leg successfully added to monitoring for execution {}: " +
                            "symbol={}, price={}, newEntryPremium={}, newTargetLevel={}, newSlLevel={}",
                    tradingMode, executionId, replacementInstrument.tradingsymbol, fillPrice,
                    monitor.getEntryPremium(), monitor.getTargetPremiumLevel(), monitor.getStopLossPremiumLevel());

        } catch (Exception e) {
            log.error("[{}] Unexpected error during leg replacement for execution {}: {}",
                    tradingMode, executionId, e.getMessage(), e);
        }
    }
}
