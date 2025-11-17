package com.tradingbot.service.strategy;

import com.tradingbot.config.StrategyConfig;
import com.tradingbot.dto.OrderRequest;
import com.tradingbot.dto.OrderResponse;
import com.tradingbot.dto.StrategyExecutionResponse;
import com.tradingbot.dto.StrategyRequest;
import com.tradingbot.model.StrategyCompletionReason;
import com.tradingbot.service.TradingService;
import com.tradingbot.service.UnifiedTradingService;
import com.tradingbot.service.strategy.monitoring.PositionMonitor;
import com.tradingbot.service.strategy.monitoring.WebSocketService;
import com.tradingbot.util.StrategyConstants;
import com.zerodhatech.kiteconnect.kitehttp.exceptions.KiteException;
import com.zerodhatech.models.Instrument;
import com.zerodhatech.models.Order;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;

/**
 * Intraday ATM option scalping strategy.
 *
 * v1 implementation:
 * - Single-leg BUY on ATM Call option.
 * - Tight SL/Target, using StrategyConfig scalping defaults when request doesn't override.
 * - Uses PositionMonitor + WebSocketService for real-time P&L and exits.
 * - One entry per execute() call (no internal loops / re-entries).
 */
@Slf4j
@Component
public class ScalpingAtmOptionStrategy extends BaseStrategy {

    private final WebSocketService webSocketService;
    private final StrategyConfig strategyConfig;

    public ScalpingAtmOptionStrategy(TradingService tradingService,
                                     UnifiedTradingService unifiedTradingService,
                                     Map<String, Integer> lotSizeCache,
                                     WebSocketService webSocketService,
                                     StrategyConfig strategyConfig) {
        super(tradingService, unifiedTradingService, lotSizeCache);
        this.webSocketService = webSocketService;
        this.strategyConfig = strategyConfig;
    }

    @Override
    public StrategyExecutionResponse execute(StrategyRequest request,
                                             String executionId,
                                             StrategyCompletionCallback completionCallback)
            throws KiteException, IOException {

        double stopLossPoints = resolveStopLoss(request);
        double targetPoints = resolveTarget(request);
        String tradingMode = unifiedTradingService.isPaperTradingEnabled()
                ? StrategyConstants.TRADING_MODE_PAPER
                : StrategyConstants.TRADING_MODE_LIVE;

        if (StrategyConstants.TRADING_MODE_LIVE.equals(tradingMode) && !strategyConfig.isScalpingEnabledInLive()) {
            log.warn("[LIVE MODE] Intraday scalping is disabled via configuration. Aborting execution {}.", executionId);
            throw new IllegalStateException("Intraday scalping is disabled in LIVE mode. Enable strategy.scalpingEnabledInLive to allow it.");
        }

        log.info("[{} MODE] Executing Intraday ATM Scalping for {} with SL={}pts, Target={}pts",
                tradingMode, request.getInstrumentType(), stopLossPoints, targetPoints);

        double spotPrice = getCurrentSpotPrice(request.getInstrumentType());
        log.info("Current spot price: {}", spotPrice);

        List<Instrument> instruments = getOptionInstruments(request.getInstrumentType(), request.getExpiry());
        log.info("Found {} option instruments for {}", instruments.size(), request.getInstrumentType());

        Date expiryDate = instruments.isEmpty() ? null : instruments.get(0).expiry;
        log.info("Using expiry date: {}", expiryDate);

        double atmStrike = expiryDate != null
                ? getATMStrikeByDelta(spotPrice, request.getInstrumentType(), expiryDate)
                : getATMStrike(spotPrice, request.getInstrumentType());

        log.info("ATM Strike (Delta-based for scalping): {}", atmStrike);

        Instrument atmCall = findOptionInstrument(instruments, atmStrike, StrategyConstants.OPTION_TYPE_CALL);

        if (atmCall == null) {
            log.error("ATM Call option not found for strike {}", atmStrike);
            throw new RuntimeException("ATM Call option not found for strike " + atmStrike);
        }

        int quantity = calculateOrderQuantity(request);
        String orderType = request.getOrderType() != null
                ? request.getOrderType()
                : StrategyConstants.ORDER_TYPE_MARKET;

        enforcePerTradeRiskLimit(stopLossPoints, quantity);

        List<StrategyExecutionResponse.OrderDetail> orderDetails = new ArrayList<>();

        log.info(StrategyConstants.LOG_PLACING_ORDER, tradingMode, StrategyConstants.LEG_TYPE_CALL, atmCall.tradingsymbol);

        OrderRequest orderRequest = createOrderRequest(atmCall.tradingsymbol,
                StrategyConstants.TRANSACTION_BUY,
                quantity,
                orderType);

        OrderResponse orderResponse = unifiedTradingService.placeOrder(orderRequest);

        if (orderResponse == null || orderResponse.getOrderId() == null ||
                !StrategyConstants.ORDER_STATUS_SUCCESS.equals(orderResponse.getStatus())) {
            String errorMsg = orderResponse != null ? orderResponse.getMessage() : StrategyConstants.ERROR_NO_RESPONSE;
            log.error("Call {}{}", StrategyConstants.ERROR_ORDER_PLACEMENT_FAILED, errorMsg);
            throw new RuntimeException("Call " + StrategyConstants.ERROR_ORDER_PLACEMENT_FAILED + errorMsg);
        }

        double entryPrice = getOrderPrice(orderResponse.getOrderId());
        if (entryPrice == 0.0) {
            log.error("Unable to fetch valid entry price for Call order {}. Monitoring will not start.", orderResponse.getOrderId());
        }

        StrategyExecutionResponse.OrderDetail orderDetail = new StrategyExecutionResponse.OrderDetail(
                orderResponse.getOrderId(),
                atmCall.tradingsymbol,
                StrategyConstants.OPTION_TYPE_CALL,
                Double.valueOf(atmCall.strike),
                quantity,
                entryPrice,
                StrategyConstants.ORDER_STATUS_COMPLETE
        );
        orderDetails.add(orderDetail);

        double totalPremium = entryPrice * quantity;

        setupMonitoring(executionId,
                atmCall,
                orderResponse.getOrderId(),
                quantity,
                stopLossPoints,
                targetPoints,
                completionCallback);

        StrategyExecutionResponse response = new StrategyExecutionResponse();
        response.setExecutionId(executionId);
        response.setStatus(StrategyConstants.STRATEGY_STATUS_ACTIVE);
        response.setMessage(String.format("[%s MODE] Intraday ATM Scalping executed. Monitoring with SL=%.1fpts, Target=%.1fpts",
                tradingMode, stopLossPoints, targetPoints));
        response.setOrders(orderDetails);
        response.setTotalPremium(totalPremium);
        response.setCurrentValue(totalPremium);
        response.setProfitLoss(0.0);
        response.setProfitLossPercentage(0.0);

        log.info("[{} MODE] Intraday ATM Scalping executed successfully. Total Premium: {}. Monitoring started.",
                tradingMode, totalPremium);

        return response;
    }

    private double resolveStopLoss(StrategyRequest request) {
        if (request.getStopLossPoints() != null) {
            return request.getStopLossPoints();
        }
        if (strategyConfig.getScalpingStopLossPoints() > 0) {
            return strategyConfig.getScalpingStopLossPoints();
        }
        return strategyConfig.getDefaultStopLossPoints();
    }

    private double resolveTarget(StrategyRequest request) {
        if (request.getTargetPoints() != null) {
            return request.getTargetPoints();
        }
        if (strategyConfig.getScalpingTargetPoints() > 0) {
            return strategyConfig.getScalpingTargetPoints();
        }
        return strategyConfig.getDefaultTargetPoints();
    }

    private void enforcePerTradeRiskLimit(double stopLossPoints, int quantity) {
        double theoreticalLoss = stopLossPoints * quantity;
        double maxAllowed = strategyConfig.getScalpingMaxLossPerTrade();

        if (maxAllowed > 0 && theoreticalLoss > maxAllowed) {
            log.error("Theoretical loss {} exceeds configured scalpingMaxLossPerTrade {}. Aborting trade.",
                    theoreticalLoss, maxAllowed);
            throw new IllegalArgumentException("Per-trade risk exceeds configured limit for scalping strategy");
        }
    }

    private void setupMonitoring(String executionId,
                                 Instrument callInstrument,
                                 String callOrderId,
                                 int quantity,
                                 double stopLossPoints,
                                 double targetPoints,
                                 StrategyCompletionCallback completionCallback) {
        try {
            List<Order> callOrderHistory = unifiedTradingService.getOrderHistory(callOrderId);
            if (callOrderHistory.isEmpty()) {
                log.error("Unable to fetch order history for callOrderId: {}", callOrderId);
                return;
            }

            Order latestCallOrder = callOrderHistory.get(callOrderHistory.size() - 1);
            if (!StrategyConstants.ORDER_STATUS_COMPLETE.equals(latestCallOrder.status)) {
                log.warn(StrategyConstants.LOG_ORDER_NOT_COMPLETE, "Call", callOrderId, latestCallOrder.status);
                return;
            }

            double callEntryPrice = getOrderPrice(callOrderId);
            if (callEntryPrice == 0.0) {
                log.error("Unable to fetch valid entry price for Call order {}. Monitoring will not start.", callOrderId);
                return;
            }

            PositionMonitor monitor = new PositionMonitor(executionId, stopLossPoints, targetPoints);
            monitor.addLeg(callOrderId,
                    callInstrument.tradingsymbol,
                    callInstrument.instrument_token,
                    callEntryPrice,
                    quantity,
                    StrategyConstants.OPTION_TYPE_CALL);

            monitor.setExitCallback(reason -> {
                log.warn("Exit triggered for scalping execution {}: {}", executionId, reason);
                exitCallLeg(executionId,
                        callInstrument.tradingsymbol,
                        quantity,
                        reason,
                        completionCallback);
            });

            double totalPremium = callEntryPrice * quantity;
            log.info("Starting scalping monitoring for execution {} with premium: {}",
                    executionId, totalPremium);

            if (!webSocketService.isConnected()) {
                webSocketService.connect();
            }

            webSocketService.startMonitoring(executionId, monitor);
            log.info("Scalping position monitoring started for execution: {}", executionId);

        } catch (Exception | KiteException e) {
            log.error("Error setting up scalping monitoring for execution {}: {}", executionId, e.getMessage(), e);
        }
    }

    private void exitCallLeg(String executionId,
                              String callSymbol,
                              int quantity,
                              String reason,
                              StrategyCompletionCallback completionCallback) {
        try {
            String tradingMode = unifiedTradingService.isPaperTradingEnabled()
                    ? StrategyConstants.TRADING_MODE_PAPER
                    : StrategyConstants.TRADING_MODE_LIVE;

            log.info("[{} MODE] Exiting scalping Call leg for execution {}: Symbol={}, Reason={}",
                    tradingMode, executionId, callSymbol, reason);

            OrderRequest exitOrder = createOrderRequest(callSymbol,
                    StrategyConstants.TRANSACTION_SELL,
                    quantity,
                    StrategyConstants.ORDER_TYPE_MARKET);

            OrderResponse exitResponse = unifiedTradingService.placeOrder(exitOrder);

            if (StrategyConstants.ORDER_STATUS_SUCCESS.equals(exitResponse.getStatus())) {
                log.info("[{} MODE] Scalping Call leg exited successfully: {}", tradingMode, exitResponse.getOrderId());
            } else {
                log.error("Failed to exit scalping Call leg: {}", exitResponse.getMessage());
            }

            webSocketService.stopMonitoring(executionId);

            if (completionCallback != null) {
                StrategyCompletionReason mappedReason = reason != null && reason.toUpperCase().contains("STOP")
                        ? StrategyCompletionReason.STOPLOSS_HIT
                        : StrategyCompletionReason.TARGET_HIT;
                completionCallback.onStrategyCompleted(executionId, mappedReason);
            }

            log.info("[{} MODE] All scalping legs exited for execution {}", tradingMode, executionId);

        } catch (Exception | KiteException e) {
            log.error("Error exiting scalping Call leg for execution {}: {}", executionId, e.getMessage(), e);
        }
    }

    @Override
    public String getStrategyName() {
        return "Intraday ATM Scalping";
    }

    @Override
    public String getStrategyDescription() {
        return "Intraday ATM option scalping using a single ATM Call leg with tight SL/Target and real-time monitoring.";
    }
}

