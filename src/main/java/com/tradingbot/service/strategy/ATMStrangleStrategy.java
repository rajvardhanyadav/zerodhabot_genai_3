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
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * ATM Strangle Strategy
 * Buy 1 OTM Call + Buy 1 OTM Put
 * Lower cost than straddle with wider breakeven points
 *
 * Features:
 * - Stop Loss: Configurable (default from config)
 * - Target: Configurable (default from config)
 * - Real-time monitoring via WebSocket
 * - Auto-exit both legs when either SL or Target is hit
 * - Supports both Paper Trading and Live Trading modes
 */
@Slf4j
@Component
public class ATMStrangleStrategy extends BaseStrategy {

    private final WebSocketService webSocketService;
    private final StrategyConfig strategyConfig;

    public ATMStrangleStrategy(TradingService tradingService,
                               UnifiedTradingService unifiedTradingService,
                               Map<String, Integer> lotSizeCache,
                               WebSocketService webSocketService,
                               StrategyConfig strategyConfig) {
        super(tradingService, unifiedTradingService, lotSizeCache);
        this.webSocketService = webSocketService;
        this.strategyConfig = strategyConfig;
    }

    @Override
    public StrategyExecutionResponse execute(StrategyRequest request, String executionId,
                                            StrategyCompletionCallback completionCallback)
            throws KiteException, IOException {

        // Get SL and Target from request, or use defaults from config
        double stopLossPoints = request.getStopLossPoints() != null
            ? request.getStopLossPoints()
            : strategyConfig.getDefaultStopLossPoints();

        double targetPoints = request.getTargetPoints() != null
            ? request.getTargetPoints()
            : strategyConfig.getDefaultTargetPoints();

        String tradingMode = unifiedTradingService.isPaperTradingEnabled() ? StrategyConstants.TRADING_MODE_PAPER : StrategyConstants.TRADING_MODE_LIVE;
        log.info("[{} MODE] Executing ATM Strangle for {} with SL={}pts, Target={}pts",
                 tradingMode, request.getInstrumentType(), stopLossPoints, targetPoints);

        // Get current spot price
        double spotPrice = getCurrentSpotPrice(request.getInstrumentType());
        log.info("Current spot price: {}", spotPrice);

        // Calculate ATM strike
        double atmStrike = getATMStrike(spotPrice, request.getInstrumentType());

        // Get strike gap (default: 100 for NIFTY, 200 for BANKNIFTY)
        double strikeGap = request.getStrikeGap() != null ? request.getStrikeGap() :
            getDefaultStrikeGap(request.getInstrumentType());

        double callStrike = atmStrike + strikeGap;
        double putStrike = atmStrike - strikeGap;

        log.info("Strangle Strikes - Call: {}, Put: {}", callStrike, putStrike);

        // Get option instruments
        List<Instrument> instruments = getOptionInstruments(
            request.getInstrumentType(),
            request.getExpiry()
        );

        // Find OTM Call and Put
        Instrument otmCall = findOptionInstrument(instruments, callStrike, StrategyConstants.OPTION_TYPE_CALL);
        Instrument otmPut = findOptionInstrument(instruments, putStrike, StrategyConstants.OPTION_TYPE_PUT);

        if (otmCall == null || otmPut == null) {
            throw new RuntimeException("OTM options not found for strikes: " + callStrike + ", " + putStrike);
        }

        List<StrategyExecutionResponse.OrderDetail> orderDetails = new ArrayList<>();

        // Calculate actual quantity using centralized method from BaseStrategy
        int quantity = calculateOrderQuantity(request);

        String orderType = request.getOrderType() != null ? request.getOrderType() : StrategyConstants.ORDER_TYPE_MARKET;

        // Place Call order using UnifiedTradingService (supports paper trading)
        log.info("[{} MODE] Placing OTM CALL order for {}", tradingMode, otmCall.tradingsymbol);
        OrderRequest callOrder = createOrderRequest(otmCall.tradingsymbol, StrategyConstants.TRANSACTION_BUY, quantity, orderType);
        var callOrderResponse = unifiedTradingService.placeOrder(callOrder);

        // Validate Call order response
        if (callOrderResponse == null || callOrderResponse.getOrderId() == null ||
            !StrategyConstants.ORDER_STATUS_SUCCESS.equals(callOrderResponse.getStatus())) {
            String errorMsg = callOrderResponse != null ? callOrderResponse.getMessage() : StrategyConstants.ERROR_NO_RESPONSE;
            log.error("Call order placement failed: {}", errorMsg);
            throw new RuntimeException("Call order placement failed: " + errorMsg);
        }

        double callPrice = getOrderPrice(callOrderResponse.getOrderId());
        orderDetails.add(new StrategyExecutionResponse.OrderDetail(
            callOrderResponse.getOrderId(),
            otmCall.tradingsymbol,
            StrategyConstants.OPTION_TYPE_CALL,
            callStrike,
            quantity,
            callPrice,
            StrategyConstants.ORDER_STATUS_COMPLETE
        ));

        // Place Put order using UnifiedTradingService (supports paper trading)
        log.info("[{} MODE] Placing OTM PUT order for {}", tradingMode, otmPut.tradingsymbol);
        OrderRequest putOrder = createOrderRequest(otmPut.tradingsymbol, StrategyConstants.TRANSACTION_BUY, quantity, orderType);
        var putOrderResponse = unifiedTradingService.placeOrder(putOrder);

        // Validate Put order response
        if (putOrderResponse == null || putOrderResponse.getOrderId() == null ||
            !StrategyConstants.ORDER_STATUS_SUCCESS.equals(putOrderResponse.getStatus())) {
            String errorMsg = putOrderResponse != null ? putOrderResponse.getMessage() : StrategyConstants.ERROR_NO_RESPONSE;
            log.error("Put order placement failed: {}", errorMsg);
            throw new RuntimeException("Put order placement failed: " + errorMsg);
        }

        double putPrice = getOrderPrice(putOrderResponse.getOrderId());
        orderDetails.add(new StrategyExecutionResponse.OrderDetail(
            putOrderResponse.getOrderId(),
            otmPut.tradingsymbol,
            StrategyConstants.OPTION_TYPE_PUT,
            putStrike,
            quantity,
            putPrice,
            StrategyConstants.ORDER_STATUS_COMPLETE
        ));

        double totalPremium = (callPrice + putPrice) * quantity;

        // Setup position monitoring with SL and Target
        setupMonitoring(executionId, otmCall, otmPut, callPrice, putPrice,
                       callOrderResponse.getOrderId(), putOrderResponse.getOrderId(),
                       quantity, stopLossPoints, targetPoints, completionCallback);

        StrategyExecutionResponse response = new StrategyExecutionResponse();
        response.setExecutionId(executionId);
        response.setStatus(StrategyConstants.STRATEGY_STATUS_ACTIVE);
        response.setMessage(String.format("[%s MODE] ATM Strangle executed successfully. Monitoring with SL=%.1fpts, Target=%.1fpts",
                           tradingMode, stopLossPoints, targetPoints));
        response.setOrders(orderDetails);
        response.setTotalPremium(totalPremium);
        response.setCurrentValue(totalPremium);
        response.setProfitLoss(0.0);
        response.setProfitLossPercentage(0.0);

        log.info("[{} MODE] ATM Strangle executed successfully. Total Premium: {}. Real-time monitoring started.",
                 tradingMode, totalPremium);
        return response;
    }

    /**
     * Setup real-time monitoring with stop loss and target
     */
    private void setupMonitoring(String executionId, Instrument callInstrument, Instrument putInstrument,
                                 double callEntryPrice, double putEntryPrice,
                                 String callOrderId, String putOrderId,
                                 int quantity,
                                 double stopLossPoints, double targetPoints,
                                 StrategyCompletionCallback completionCallback) {

        // Create position monitor with configurable SL and target
        PositionMonitor monitor = new PositionMonitor(executionId, stopLossPoints, targetPoints);

        // Add Call leg
        monitor.addLeg(callOrderId, callInstrument.tradingsymbol, callInstrument.instrument_token,
                      callEntryPrice, quantity, StrategyConstants.OPTION_TYPE_CALL);

        // Add Put leg
        monitor.addLeg(putOrderId, putInstrument.tradingsymbol, putInstrument.instrument_token,
                      putEntryPrice, quantity, StrategyConstants.OPTION_TYPE_PUT);

        // Set exit callback to square off both legs
        monitor.setExitCallback(reason -> {
            log.warn("Exit triggered for execution {}: {}", executionId, reason);
            exitAllLegs(executionId, callInstrument.tradingsymbol,
                       putInstrument.tradingsymbol, quantity, reason, completionCallback);
        });

        // Ensure WebSocket is connected
        if (!webSocketService.isConnected()) {
            webSocketService.connect();
        }

        // Start monitoring
        webSocketService.startMonitoring(executionId, monitor);

        log.info("Position monitoring started for execution: {}", executionId);
    }

    /**
     * Exit all legs when SL or Target is hit
     */
    private void exitAllLegs(String executionId, String callSymbol, String putSymbol,
                            int quantity, String reason, StrategyCompletionCallback completionCallback) {
        try {
            String tradingMode = unifiedTradingService.isPaperTradingEnabled() ? StrategyConstants.TRADING_MODE_PAPER : StrategyConstants.TRADING_MODE_LIVE;
            log.info("[{} MODE] Exiting all legs for execution {}: {}", tradingMode, executionId, reason);

            // Place sell orders for both legs using UnifiedTradingService
            OrderRequest callExitOrder = createOrderRequest(callSymbol, StrategyConstants.TRANSACTION_SELL, quantity, StrategyConstants.ORDER_TYPE_MARKET);
            OrderResponse callExitResponse = unifiedTradingService.placeOrder(callExitOrder);

            if (StrategyConstants.ORDER_STATUS_SUCCESS.equals(callExitResponse.getStatus())) {
                log.info("[{} MODE] Call leg exited successfully: {}", tradingMode, callExitResponse.getOrderId());
            } else {
                log.error("Failed to exit Call leg: {}", callExitResponse.getMessage());
            }

            OrderRequest putExitOrder = createOrderRequest(putSymbol, StrategyConstants.TRANSACTION_SELL, quantity, StrategyConstants.ORDER_TYPE_MARKET);
            OrderResponse putExitResponse = unifiedTradingService.placeOrder(putExitOrder);

            if (StrategyConstants.ORDER_STATUS_SUCCESS.equals(putExitResponse.getStatus())) {
                log.info("[{} MODE] Put leg exited successfully: {}", tradingMode, putExitResponse.getOrderId());
            } else {
                log.error("Failed to exit Put leg: {}", putExitResponse.getMessage());
            }

            // Stop monitoring
            webSocketService.stopMonitoring(executionId);

            // Notify completion via callback with structured reason
            if (completionCallback != null) {
                StrategyCompletionReason mappedReason = reason != null && reason.toUpperCase().contains("STOP")
                        ? StrategyCompletionReason.STOPLOSS_HIT
                        : StrategyCompletionReason.TARGET_HIT;
                completionCallback.onStrategyCompleted(executionId, mappedReason);
            }

            log.info("[{} MODE] Successfully exited all legs for execution {}", tradingMode, executionId);

        } catch (Exception | KiteException e) {
            log.error("Error exiting legs for execution {}: {}", executionId, e.getMessage(), e);
        }
    }

    /**
     * Get default strike gap for strangle
     */
    private double getDefaultStrikeGap(String instrumentType) {
        return switch (instrumentType.toUpperCase()) {
            case "NIFTY" -> 100.0;
            case "BANKNIFTY" -> 200.0;
            case "FINNIFTY" -> 100.0;
            default -> 100.0;
        };
    }

    @Override
    public String getStrategyName() {
        return "ATM Strangle";
    }

    @Override
    public String getStrategyDescription() {
        return String.format("Buy OTM Call + Buy OTM Put (Lower cost than straddle with SL=%.1fpts, Target=%.1fpts) - Supports Paper & Live Trading",
                           strategyConfig.getDefaultStopLossPoints(),
                           strategyConfig.getDefaultTargetPoints());
    }
}
