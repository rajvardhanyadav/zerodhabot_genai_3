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
import com.tradingbot.model.NeutralMarketEvaluation;
import com.tradingbot.model.StrategyExecution;
import com.tradingbot.model.StrategyStatus;
import com.tradingbot.service.InstrumentCacheService;
import com.tradingbot.service.MarketDataEngine;
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
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.time.LocalTime;
import java.time.ZoneId;
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
 * Optionally includes hedge legs: Buy 0.1Δ OTM CE + Buy 0.1Δ OTM PE to reduce margin
 * requirements and cap maximum loss. Hedge is configurable via
 * {@code strategy.sell-straddle-hedge-enabled} or per-request via {@code hedgeEnabled}.
 * <p>
 * Entry/exit thresholds match PositionMonitorV2 (2.5 & 4 points).
 * Flow mirrors ATMStraddleStrategy but uses SELL instead of BUY.
 * <p>
 * HFT Optimizations:
 * <ul>
 *   <li>O(1) instrument lookup via pre-built HashMap index</li>
 *   <li>Pre-parsed strike values to avoid Double.parseDouble() on hot path</li>
 *   <li>Parallel order placement for all legs</li>
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
    private final NeutralMarketDetector neutralMarketDetectorService;

    // ==================== HFT Thread Pool Configuration ====================
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final int HFT_THREAD_POOL_SIZE = 8;
    private static final AtomicInteger THREAD_COUNTER = new AtomicInteger(0);
    private static final ThreadFactory HFT_THREAD_FACTORY = r -> {
        Thread t = new Thread(r, "hft-sell-exit-" + THREAD_COUNTER.incrementAndGet());
        t.setDaemon(true);
        // NOTE: Thread.MAX_PRIORITY is a JVM hint to the OS scheduler.
        // On Linux (Cloud Run), this maps to a lower nice value but is not guaranteed.
        // Actual exit latency depends on system load, not thread priority alone.
        t.setPriority(Thread.MAX_PRIORITY);
        return t;
    };
    private static final ExecutorService EXIT_ORDER_EXECUTOR = Executors.newFixedThreadPool(
            HFT_THREAD_POOL_SIZE, HFT_THREAD_FACTORY);

    // Pre-computed constants for HFT
    private static final String CALL_SHORT_SUFFIX = "_SHORT";
    private static final String ANNOTATED_CALL_TYPE = StrategyConstants.OPTION_TYPE_CALL + CALL_SHORT_SUFFIX;
    private static final String ANNOTATED_PUT_TYPE = StrategyConstants.OPTION_TYPE_PUT + CALL_SHORT_SUFFIX;

    // Hedge leg constants (BUY far OTM options for margin reduction)
    private static final String HEDGE_CALL_TYPE = StrategyConstants.OPTION_TYPE_CALL + "_HEDGE";
    private static final String HEDGE_PUT_TYPE = StrategyConstants.OPTION_TYPE_PUT + "_HEDGE";
    private static final String LEG_TYPE_SELL_CALL = "SellCall";
    private static final String LEG_TYPE_SELL_PUT = "SellPut";
    private static final String LEG_TYPE_HEDGE_CALL = "HedgeCall";
    private static final String LEG_TYPE_HEDGE_PUT = "HedgePut";

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
                                   // CYCLE B: StrategyService → StrategyFactory → SellATMStraddleStrategy → StrategyService.
                                   // Strategy calls strategyService.completeExecution() / updateLegLifecycleState().
                                   // Decoupling plan: ObjectProvider<StrategyService> or StrategyCompletionEvent.
                                   @Lazy StrategyService strategyService,
                                   DeltaCacheService deltaCacheService,
                                   VolatilityFilterService volatilityFilterService,
                                   VolatilityConfig volatilityConfig,
                                   StraddleExitHandler exitHandler,
                                   LegReplacementHandler legReplacementHandler,
                                   MonitoringSetupHelper monitoringSetupHelper,
                                   @Qualifier("neutralMarketDetectorV3") NeutralMarketDetector neutralMarketDetectorService,
                                   MarketDataEngine marketDataEngine,
                                   InstrumentCacheService instrumentCacheService) {
        super(tradingService, unifiedTradingService, lotSizeCache, deltaCacheService, marketDataEngine, instrumentCacheService);
        this.webSocketService = webSocketService;
        this.strategyConfig = strategyConfig;
        this.strategyService = strategyService;
        this.volatilityFilterService = volatilityFilterService;
        this.volatilityConfig = volatilityConfig;
        this.exitHandler = exitHandler;
        this.legReplacementHandler = legReplacementHandler;
        this.monitoringSetupHelper = monitoringSetupHelper;
        this.neutralMarketDetectorService = neutralMarketDetectorService;
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
        final boolean hedgeEnabled = resolveHedgeEnabled(request);
        final double hedgeDelta = strategyConfig.getSellStraddleHedgeDelta();

        logExecutionStart(tradingMode, instrumentType, stopLossPoints, targetPoints, slTargetMode, targetDecayPct, stopLossExpansionPct);
        if (hedgeEnabled) {
            log.info("[{}] Hedge legs ENABLED with delta={}", tradingMode, hedgeDelta);
        }

        // ==================== GATE 0: ENTRY TIME WINDOW ====================
        LocalTime now = LocalTime.now(IST);
        LocalTime entryStart = LocalTime.parse(strategyConfig.getEntryWindowStart());
        LocalTime entryEnd = LocalTime.parse(strategyConfig.getEntryWindowEnd());
        if (now.isBefore(entryStart) || now.isAfter(entryEnd)) {
            String reason = String.format("Outside entry window: %s not in [%s, %s]", now, entryStart, entryEnd);
            log.info("[{}] Skipping straddle entry: {}", tradingMode, reason);
            return buildSkippedResponse(executionId, reason);
        }

        // ==================== GATE 1: VIX VOLATILITY FILTER ====================
        VolatilityFilterService.VolatilityFilterResult vixResult =
                volatilityFilterService.shouldAllowTrade(false);
        log.info("[{}] VIX filter: allowed={}, reason={}, vix={}",
                tradingMode, vixResult.allowed(), vixResult.reason(), vixResult.currentVix());

        if (!vixResult.allowed()) {
            String reason = "VIX filter blocked: " + vixResult.reason();
            log.info("[{}] Skipping straddle entry: VIX conditions not favourable. Reason: {}",
                    tradingMode, vixResult.reason());
            return buildSkippedResponse(executionId, reason);
        }

        // ==================== GATE 2: NEUTRAL MARKET FILTER ====================
        log.info("[{}] Evaluating ATM straddle entry for {}. Checking neutral market conditions...",
                tradingMode, instrumentType);

        NeutralMarketEvaluation neutralResult =
                neutralMarketDetectorService.evaluate(instrumentType);
        log.info("[{}] Neutral market check: instrument={}, score={}/{}, regime={}, minimumRequired={}, neutral={}, signals=[{}]",
                tradingMode, instrumentType, neutralResult.totalScore(), neutralResult.maxScore(),
                neutralResult.getRegimeLabel(), neutralResult.minimumRequired(), neutralResult.neutral(), neutralResult.summary());

        if (!neutralResult.neutral()) {
            String failedSignals = neutralResult.summary();
            log.info("[{}] Skipping straddle entry because market conditions are not neutral. " +
                            "score={}/{} (minimum={}). Failed signals: {}",
                    tradingMode, neutralResult.totalScore(), neutralResult.maxScore(),
                    neutralResult.minimumRequired(), failedSignals);
            return buildSkippedResponse(executionId,
                    "Neutral market filter BLOCKED: score=" + neutralResult.totalScore()
                    + "/" + neutralResult.maxScore() + " (minimum=" + neutralResult.minimumRequired()
                    + "). " + neutralResult.summary());
        }

        log.info("[{}] Neutral market confirmed for {}. Proceeding with ATM straddle placement.",
                tradingMode, instrumentType);

        // Get spot price and instruments
        final double spotPrice = getCurrentSpotPrice(instrumentType);
        log.info("Current spot price: {}", spotPrice);

        final List<Instrument> instruments = getOptionInstruments(instrumentType, expiry);
        log.info("Found {} option instruments for {}", instruments.size(), instrumentType);

        // Calculate ATM strike using delta-based selection
        final Date expiryDate = !instruments.isEmpty() ? instruments.get(0).expiry : null;

        // ==================== GATE 3: EXPIRY DAY AWARENESS ====================
        boolean isExpiryDay = expiryDate != null && isSameDay(expiryDate, new Date());
        if (isExpiryDay) {
            log.info("[{}] EXPIRY DAY detected — applying tighter thresholds", tradingMode);

            if (!strategyConfig.isExpiryDayEnabled()) {
                return buildSkippedResponse(executionId, "Expiry day trading is disabled");
            }

            // Enforce tighter entry end time on expiry day
            LocalTime expiryEntryEnd = LocalTime.parse(strategyConfig.getExpiryDayEntryEndTime());
            LocalTime currentTime = LocalTime.now(IST);
            if (currentTime.isAfter(expiryEntryEnd)) {
                String reason = String.format("Expiry day: past entry cutoff %s (current=%s)", expiryEntryEnd, currentTime);
                log.info("[{}] Skipping straddle entry on expiry day: {}", tradingMode, reason);
                return buildSkippedResponse(executionId, reason);
            }
        }

        final double atmStrike = expiryDate != null
                ? getATMStrikeByDelta(spotPrice, instrumentType, expiryDate)
                : getATMStrike(spotPrice, instrumentType);
        log.info("ATM Strike (Delta-based): {}", atmStrike);
        log.info("[{}] Neutral market confirmed. Placing ATM straddle at strike={}, price={}, instrument={}",
                tradingMode, atmStrike, spotPrice, instrumentType);

        // Build instrument index and find ATM options
        buildInstrumentIndex(instruments);
        Instrument atmCall = instrumentIndex.get(InstrumentKey.of(atmStrike, StrategyConstants.OPTION_TYPE_CALL));
        Instrument atmPut = instrumentIndex.get(InstrumentKey.of(atmStrike, StrategyConstants.OPTION_TYPE_PUT));
        validateATMOptions(atmCall, atmPut, atmStrike);

        // ==================== HEDGE STRIKE SELECTION (conditional) ====================
        Instrument hedgeCall = null;
        Instrument hedgePut = null;

        if (hedgeEnabled) {
            if (expiryDate == null) {
                throw new RuntimeException("Could not determine expiry date for hedge delta calculation");
            }

            final double hedgeCEStrike = getStrikeByDelta(spotPrice, instrumentType, expiryDate, hedgeDelta, OPTION_TYPE_CE);
            final double hedgePEStrike = getStrikeByDelta(spotPrice, instrumentType, expiryDate, hedgeDelta, OPTION_TYPE_PE);
            log.info("Hedge leg strikes - CE: {} (target Δ={}), PE: {} (target Δ={})",
                    hedgeCEStrike, hedgeDelta, hedgePEStrike, hedgeDelta);

            // Validate hedge is further OTM than ATM
            if (hedgeCEStrike <= atmStrike) {
                log.warn("Hedge CE strike {} is not further OTM than ATM CE {} - proceeding with delta-selected strike", hedgeCEStrike, atmStrike);
            }
            if (hedgePEStrike >= atmStrike) {
                log.warn("Hedge PE strike {} is not further OTM than ATM PE {} - proceeding with delta-selected strike", hedgePEStrike, atmStrike);
            }

            hedgeCall = instrumentIndex.get(InstrumentKey.of(hedgeCEStrike, StrategyConstants.OPTION_TYPE_CALL));
            hedgePut = instrumentIndex.get(InstrumentKey.of(hedgePEStrike, StrategyConstants.OPTION_TYPE_PUT));
            validateHedgeInstruments(hedgeCall, hedgePut, hedgeCEStrike, hedgePEStrike);
        }

        // Place basket order
        final int quantity = calculateOrderQuantity(request);
        final String orderType = resolveOrderType(request);

        List<StrategyExecutionResponse.OrderDetail> orderDetails;
        if (hedgeEnabled) {
            orderDetails = placeHedgedBasketOrder(atmCall, atmPut, hedgeCall, hedgePut,
                    quantity, orderType, tradingMode, executionId);
        } else {
            orderDetails = placeBasketOrderForStraddle(atmCall, atmPut, quantity, orderType, tradingMode, executionId);
        }

        // Extract order IDs based on whether hedging is active
        if (hedgeEnabled) {
            String sellCallOrderId = null, sellPutOrderId = null;
            String hedgeCallOrderId = null, hedgePutOrderId = null;

            for (StrategyExecutionResponse.OrderDetail od : orderDetails) {
                String optType = od.getOptionType();
                if (ANNOTATED_CALL_TYPE.equals(optType)) sellCallOrderId = od.getOrderId();
                else if (ANNOTATED_PUT_TYPE.equals(optType)) sellPutOrderId = od.getOrderId();
                else if (HEDGE_CALL_TYPE.equals(optType)) hedgeCallOrderId = od.getOrderId();
                else if (HEDGE_PUT_TYPE.equals(optType)) hedgePutOrderId = od.getOrderId();
            }
            validateHedgedOrderIds(sellCallOrderId, sellPutOrderId, hedgeCallOrderId, hedgePutOrderId);

            // Setup 4-leg monitoring (strangle-style) — hedge legs tracked with -1.0 direction
            setupHedgedMonitoring(executionId, atmCall, atmPut, hedgeCall, hedgePut,
                    sellCallOrderId, sellPutOrderId, hedgeCallOrderId, hedgePutOrderId,
                    quantity, stopLossPoints, targetPoints, targetDecayPct, stopLossExpansionPct,
                    slTargetMode, completionCallback);
        } else {
            // Original 2-leg flow
            String callOrderId = null, putOrderId = null;
            for (StrategyExecutionResponse.OrderDetail od : orderDetails) {
                if (od.getOptionType().contains(StrategyConstants.OPTION_TYPE_CALL)) {
                    callOrderId = od.getOrderId();
                } else if (od.getOptionType().contains(StrategyConstants.OPTION_TYPE_PUT)) {
                    putOrderId = od.getOrderId();
                }
            }
            validateOrderIds(callOrderId, putOrderId);

            // Setup original 2-leg monitoring with leg replacement
            setupMonitoring(executionId, atmCall, atmPut, callOrderId, putOrderId,
                    quantity, stopLossPoints, targetPoints, targetDecayPct, stopLossExpansionPct,
                    slTargetMode, completionCallback);
        }

        double premium = hedgeEnabled ? calculateNetPremium(orderDetails) : calculateTotalPremium(orderDetails);
        return buildSuccessResponse(executionId, orderDetails, premium, stopLossPoints, targetPoints, tradingMode);
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

        validateBasketResponse(basketResponse, quantity, tradingMode, executionId);

        return convertToOrderDetails(basketResponse, atmCall, atmPut, quantity);
    }

    // ==================== Hedged 4-Leg Basket Order ====================

    private List<StrategyExecutionResponse.OrderDetail> placeHedgedBasketOrder(
            Instrument atmCall, Instrument atmPut,
            Instrument hedgeCall, Instrument hedgePut,
            int quantity, String orderType, String tradingMode, String executionId) {

        log.info("[{}] Placing hedged 4-leg basket order - SellCE: {}, SellPE: {}, HedgeCE: {}, HedgePE: {}, Qty: {}",
                tradingMode, atmCall.tradingsymbol, atmPut.tradingsymbol,
                hedgeCall.tradingsymbol, hedgePut.tradingsymbol, quantity);

        BasketOrderRequest basketRequest = buildHedgedBasketOrderRequest(
                atmCall, atmPut, hedgeCall, hedgePut, quantity, orderType, executionId);
        BasketOrderResponse basketResponse = unifiedTradingService.placeBasketOrder(basketRequest);

        log.info("[{}] Hedged basket response - Status: {}, Success: {}/{}",
                tradingMode, basketResponse.getStatus(),
                basketResponse.getSuccessCount(), basketResponse.getTotalOrders());

        validateBasketResponse(basketResponse, quantity, tradingMode, executionId);

        return convertHedgedToOrderDetails(basketResponse, atmCall, atmPut, hedgeCall, hedgePut, quantity);
    }

    private BasketOrderRequest buildHedgedBasketOrderRequest(
            Instrument atmCall, Instrument atmPut,
            Instrument hedgeCall, Instrument hedgePut,
            int quantity, String orderType, String executionId) {

        List<BasketOrderRequest.BasketOrderItem> items = new ArrayList<>(4);

        // Hedge BUY legs placed FIRST for margin benefit
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

        // Sell ATM legs
        items.add(BasketOrderRequest.BasketOrderItem.builder()
                .tradingSymbol(atmCall.tradingsymbol)
                .exchange(EXCHANGE_NFO)
                .transactionType(StrategyConstants.TRANSACTION_SELL)
                .quantity(quantity)
                .product(PRODUCT_MIS)
                .orderType(orderType)
                .validity(VALIDITY_DAY)
                .legType(LEG_TYPE_SELL_CALL)
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
                .legType(LEG_TYPE_SELL_PUT)
                .instrumentToken(atmPut.instrument_token)
                .build());

        return BasketOrderRequest.builder()
                .orders(items)
                .tag("HEDGED_SELL_STRADDLE_" + executionId)
                .build();
    }

    private List<StrategyExecutionResponse.OrderDetail> convertHedgedToOrderDetails(
            BasketOrderResponse response,
            Instrument atmCall, Instrument atmPut,
            Instrument hedgeCall, Instrument hedgePut,
            int quantity) {

        List<StrategyExecutionResponse.OrderDetail> orderDetails = new ArrayList<>(4);

        for (BasketOrderResponse.BasketOrderResult result : response.getOrderResults()) {
            if (STATUS_SUCCESS.equals(result.getStatus())) {
                Instrument instrument;
                String annotatedType;

                switch (result.getLegType()) {
                    case LEG_TYPE_SELL_CALL -> { instrument = atmCall; annotatedType = ANNOTATED_CALL_TYPE; }
                    case LEG_TYPE_SELL_PUT -> { instrument = atmPut; annotatedType = ANNOTATED_PUT_TYPE; }
                    case LEG_TYPE_HEDGE_CALL -> { instrument = hedgeCall; annotatedType = HEDGE_CALL_TYPE; }
                    case LEG_TYPE_HEDGE_PUT -> { instrument = hedgePut; annotatedType = HEDGE_PUT_TYPE; }
                    default -> {
                        log.warn("Unknown leg type in hedged straddle: {}", result.getLegType());
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
            throw new RuntimeException("Expected 4 order details for hedged straddle but got " + orderDetails.size());
        }

        return orderDetails;
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

    private void validateBasketResponse(BasketOrderResponse response, int quantity,
                                        String tradingMode, String executionId) {
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
        StringBuilder sb = new StringBuilder("Straddle requires all legs. Partial fill detected: ");
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
            // Determine the reverse transaction type based on leg type
            String reverseTransaction;
            if (LEG_TYPE_SELL_CALL.equals(result.getLegType()) || LEG_TYPE_SELL_PUT.equals(result.getLegType())
                    || StrategyConstants.LEG_TYPE_CALL.equals(result.getLegType())
                    || StrategyConstants.LEG_TYPE_PUT.equals(result.getLegType())) {
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

    /**
     * Setup 4-leg monitoring for hedged straddle using StrangleMonitoringParams.
     * Hedge legs are tracked with -1.0 direction multiplier (net P&L contribution)
     * but are NOT independently monitored for SL/target.
     * Leg replacement is disabled for hedged positions.
     */
    private void setupHedgedMonitoring(String executionId,
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

        // Disable leg replacement for hedged straddle — only support full exit of all legs
        MonitoringSetupHelper.MonitorCallbacks callbacks = new MonitoringSetupHelper.MonitorCallbacks(
                reason -> exitAllLegs(executionId, reason, completionCallback),
                (legSymbol, reason) -> exitAllLegs(executionId,
                        "Individual leg exit (" + legSymbol + "): " + reason, completionCallback),
                // No leg replacement for hedged straddle — noop callback
                (exitedLegSymbol, legTypeToAdd, targetPremium, lossMakingLegSymbol, exitedLegLtp) ->
                        log.info("Leg replacement not supported for HEDGED_SELL_STRADDLE; ignoring for {}", executionId)
        );

        monitoringSetupHelper.setupStrangleMonitoringAsync(params, callbacks, EXIT_ORDER_EXECUTOR);
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

    private void validateHedgeInstruments(Instrument hedgeCall, Instrument hedgePut,
                                          double hedgeCEStrike, double hedgePEStrike) {
        StringBuilder errors = new StringBuilder();
        if (hedgeCall == null) errors.append("Hedge CE not found for strike ").append(hedgeCEStrike).append(". ");
        if (hedgePut == null) errors.append("Hedge PE not found for strike ").append(hedgePEStrike).append(". ");

        if (!errors.isEmpty()) {
            throw new RuntimeException("Hedge instruments not found: " + errors);
        }

        log.info("Hedge instruments - HedgeCE: {}, HedgePE: {}", hedgeCall.tradingsymbol, hedgePut.tradingsymbol);
    }

    private void validateOrderIds(String callOrderId, String putOrderId) {
        if (callOrderId == null) {
            throw new RuntimeException("Call order not found in basket response");
        }
        if (putOrderId == null) {
            throw new RuntimeException("Put order not found in basket response");
        }
    }

    private void validateHedgedOrderIds(String sellCallOrderId, String sellPutOrderId,
                                        String hedgeCallOrderId, String hedgePutOrderId) {
        if (sellCallOrderId == null) throw new RuntimeException("Sell Call order not found in basket response");
        if (sellPutOrderId == null) throw new RuntimeException("Sell Put order not found in basket response");
        if (hedgeCallOrderId == null) throw new RuntimeException("Hedge Call order not found in basket response");
        if (hedgePutOrderId == null) throw new RuntimeException("Hedge Put order not found in basket response");
    }

    private double calculateTotalPremium(List<StrategyExecutionResponse.OrderDetail> orderDetails) {
        double callPrice = orderDetails.get(0).getPrice();
        double putPrice = orderDetails.get(1).getPrice();
        int quantity = orderDetails.get(0).getQuantity();

        log.info(StrategyConstants.LOG_BOTH_LEGS_PLACED, getTradingMode(), callPrice, putPrice);
        return (callPrice + putPrice) * quantity;
    }

    /**
     * Calculate net premium for hedged straddle: sellPremium - hedgePremium.
     * This represents the net credit received after paying for hedge legs.
     */
    private double calculateNetPremium(List<StrategyExecutionResponse.OrderDetail> orderDetails) {
        double sellPremium = 0.0;
        double hedgePremium = 0.0;
        int quantity = 0;

        for (StrategyExecutionResponse.OrderDetail od : orderDetails) {
            if (quantity == 0 && od.getQuantity() != null) quantity = od.getQuantity();
            String optType = od.getOptionType();
            if (ANNOTATED_CALL_TYPE.equals(optType) || ANNOTATED_PUT_TYPE.equals(optType)) {
                sellPremium += od.getPrice();
            } else {
                hedgePremium += od.getPrice();
            }
        }

        double netPremiumPerUnit = sellPremium - hedgePremium;
        log.info("[{}] Hedged straddle legs placed - SellPremium: {}, HedgePremium: {}, NetPremium/unit: {}",
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
     * Build a SKIPPED response when a pre-flight gate blocks entry.
     * Returns a proper response (not an exception) so StrategyService marks it as SKIPPED,
     * and StrategyRestartScheduler can re-register the execution for later retry.
     */
    private StrategyExecutionResponse buildSkippedResponse(String executionId, String reason) {
        StrategyExecutionResponse response = new StrategyExecutionResponse();
        response.setExecutionId(executionId);
        response.setStatus(StrategyStatus.SKIPPED.name());
        response.setMessage(reason);
        return response;
    }

    /**
     * Fast same-day check without Calendar allocation.
     * Converts both dates to IST day-epoch for O(1) comparison.
     */
    private boolean isSameDay(Date d1, Date d2) {
        long IST_OFFSET_MS = 19800000L;
        long MS_PER_DAY = 86400000L;
        long day1 = (d1.getTime() + IST_OFFSET_MS) / MS_PER_DAY;
        long day2 = (d2.getTime() + IST_OFFSET_MS) / MS_PER_DAY;
        return day1 == day2;
    }

    // ==================== Configuration Resolvers ====================

    private boolean resolveHedgeEnabled(StrategyRequest request) {
        return request.getHedgeEnabled() != null
                ? request.getHedgeEnabled()
                : strategyConfig.isSellStraddleHedgeEnabled();
    }

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
