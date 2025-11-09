package com.tradingbot.service.strategy;

import com.tradingbot.config.StrategyConfig;
import com.tradingbot.dto.OrderRequest;
import com.tradingbot.dto.OrderResponse;
import com.tradingbot.dto.StrategyExecutionResponse;
import com.tradingbot.dto.StrategyRequest;
import com.tradingbot.service.TradingService;
import com.tradingbot.service.UnifiedTradingService;
import com.tradingbot.service.strategy.monitoring.PositionMonitor;
import com.tradingbot.service.strategy.monitoring.WebSocketService;
import com.zerodhatech.kiteconnect.kitehttp.exceptions.KiteException;
import com.zerodhatech.models.Instrument;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * ATM Straddle Strategy
 * Buy 1 ATM Call + Buy 1 ATM Put
 * Non-directional strategy that profits from high volatility
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
public class ATMStraddleStrategy extends BaseStrategy {

    private final WebSocketService webSocketService;
    private final StrategyConfig strategyConfig;

    public ATMStraddleStrategy(TradingService tradingService,
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

        String tradingMode = unifiedTradingService.isPaperTradingEnabled() ? "PAPER" : "LIVE";
        log.info("[{} MODE] Executing ATM Straddle for {} with SL={}pts, Target={}pts",
                 tradingMode, request.getInstrumentType(), stopLossPoints, targetPoints);

        // Get current spot price
        double spotPrice = getCurrentSpotPrice(request.getInstrumentType());
        log.info("Current spot price: {}", spotPrice);

        // Calculate ATM strike
        double atmStrike = getATMStrike(spotPrice, request.getInstrumentType());
        log.info("ATM Strike: {}", atmStrike);

        // Get option instruments
        List<Instrument> instruments = getOptionInstruments(
            request.getInstrumentType(),
            request.getExpiry()
        );
        log.info("Found {} option instruments for {}", instruments.size(), request.getInstrumentType());

        // Find ATM Call and Put
        Instrument atmCall = findOptionInstrument(instruments, atmStrike, "CE");
        Instrument atmPut = findOptionInstrument(instruments, atmStrike, "PE");
        log.info("ATM Call: {}, ATM Put: {}",
            atmCall != null ? atmCall.tradingsymbol : "null",
            atmPut != null ? atmPut.tradingsymbol : "null");

        if (atmCall == null || atmPut == null) {
            throw new RuntimeException("ATM options not found for strike: " + atmStrike);
        }

        List<StrategyExecutionResponse.OrderDetail> orderDetails = new ArrayList<>();

        // Calculate actual quantity using centralized method from BaseStrategy
        int quantity = calculateOrderQuantity(request);

        String orderType = request.getOrderType() != null ? request.getOrderType() : "MARKET";

        // Place Call order using UnifiedTradingService (supports paper trading)
        log.info("[{} MODE] Placing CALL order for {}", tradingMode, atmCall.tradingsymbol);
        OrderRequest callOrder = createOrderRequest(atmCall.tradingsymbol, "BUY", quantity, orderType, null);
        var callOrderResponse = unifiedTradingService.placeOrder(callOrder);

        // Validate Call order response
        if (callOrderResponse == null || callOrderResponse.getOrderId() == null ||
            !"SUCCESS".equals(callOrderResponse.getStatus())) {
            String errorMsg = callOrderResponse != null ? callOrderResponse.getMessage() : "No response received";
            log.error("Call order placement failed: {}", errorMsg);
            throw new RuntimeException("Call order placement failed: " + errorMsg);
        }

        double callPrice = getOrderPrice(callOrderResponse.getOrderId());
        orderDetails.add(new StrategyExecutionResponse.OrderDetail(
            callOrderResponse.getOrderId(),
            atmCall.tradingsymbol,
            "CE",
            atmStrike,
            quantity,
            callPrice,
            "COMPLETED"
        ));

        // Place Put order using UnifiedTradingService (supports paper trading)
        log.info("[{} MODE] Placing PUT order for {}", tradingMode, atmPut.tradingsymbol);
        OrderRequest putOrder = createOrderRequest(atmPut.tradingsymbol, "BUY", quantity, orderType, null);
        var putOrderResponse = unifiedTradingService.placeOrder(putOrder);

        // Validate Put order response
        if (putOrderResponse == null || putOrderResponse.getOrderId() == null ||
            !"SUCCESS".equals(putOrderResponse.getStatus())) {
            String errorMsg = putOrderResponse != null ? putOrderResponse.getMessage() : "No response received";
            log.error("Put order placement failed: {}", errorMsg);
            throw new RuntimeException("Put order placement failed: " + errorMsg);
        }

        double putPrice = getOrderPrice(putOrderResponse.getOrderId());
        orderDetails.add(new StrategyExecutionResponse.OrderDetail(
            putOrderResponse.getOrderId(),
            atmPut.tradingsymbol,
            "PE",
            atmStrike,
            quantity,
            putPrice,
            "COMPLETED"
        ));

        double totalPremium = (callPrice + putPrice) * quantity;

        // Setup position monitoring with SL and Target
        setupMonitoring(executionId, atmCall, atmPut, callPrice, putPrice,
                       callOrderResponse.getOrderId(), putOrderResponse.getOrderId(),
                       quantity, orderDetails, stopLossPoints, targetPoints, completionCallback);

        StrategyExecutionResponse response = new StrategyExecutionResponse();
        response.setExecutionId(executionId);
        response.setStatus("ACTIVE");
        response.setMessage(String.format("[%s MODE] ATM Straddle executed successfully. Monitoring with SL=%.1fpts, Target=%.1fpts",
                           tradingMode, stopLossPoints, targetPoints));
        response.setOrders(orderDetails);
        response.setTotalPremium(totalPremium);
        response.setCurrentValue(totalPremium);
        response.setProfitLoss(0.0);
        response.setProfitLossPercentage(0.0);

        log.info("[{} MODE] ATM Straddle executed successfully. Total Premium: {}. Real-time monitoring started.",
                 tradingMode, totalPremium);
        return response;
    }

    /**
     * Setup real-time monitoring with stop loss and target
     */
    private void setupMonitoring(String executionId, Instrument callInstrument, Instrument putInstrument,
                                 double callEntryPrice, double putEntryPrice,
                                 String callOrderId, String putOrderId,
                                 int quantity, List<StrategyExecutionResponse.OrderDetail> orderDetails,
                                 double stopLossPoints, double targetPoints,
                                 StrategyCompletionCallback completionCallback) {

        // Create position monitor with configurable SL and target
        PositionMonitor monitor = new PositionMonitor(executionId, stopLossPoints, targetPoints);

        // Add Call leg
        monitor.addLeg(callOrderId, callInstrument.tradingsymbol, callInstrument.instrument_token,
                      callEntryPrice, quantity, "CE");

        // Add Put leg
        monitor.addLeg(putOrderId, putInstrument.tradingsymbol, putInstrument.instrument_token,
                      putEntryPrice, quantity, "PE");

        // Set exit callback to square off both legs
        monitor.setExitCallback(reason -> {
            log.warn("Exit triggered for execution {}: {}", executionId, reason);
            exitAllLegs(executionId, callOrderId, putOrderId, callInstrument.tradingsymbol,
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
    private void exitAllLegs(String executionId, String callOrderId, String putOrderId,
                            String callSymbol, String putSymbol, int quantity, String reason,
                            StrategyCompletionCallback completionCallback) {
        try {
            String tradingMode = unifiedTradingService.isPaperTradingEnabled() ? "PAPER" : "LIVE";
            log.info("[{} MODE] Exiting all legs for execution {}: {}", tradingMode, executionId, reason);

            // Place sell orders for both legs using UnifiedTradingService
            OrderRequest callExitOrder = createOrderRequest(callSymbol, "SELL", quantity, "MARKET", null);
            OrderResponse callExitResponse = unifiedTradingService.placeOrder(callExitOrder);

            if ("SUCCESS".equals(callExitResponse.getStatus())) {
                log.info("[{} MODE] Call leg exited successfully: {}", tradingMode, callExitResponse.getOrderId());
            } else {
                log.error("Failed to exit Call leg: {}", callExitResponse.getMessage());
            }

            OrderRequest putExitOrder = createOrderRequest(putSymbol, "SELL", quantity, "MARKET", null);
            OrderResponse putExitResponse = unifiedTradingService.placeOrder(putExitOrder);

            if ("SUCCESS".equals(putExitResponse.getStatus())) {
                log.info("[{} MODE] Put leg exited successfully: {}", tradingMode, putExitResponse.getOrderId());
            } else {
                log.error("Failed to exit Put leg: {}", putExitResponse.getMessage());
            }

            // Stop monitoring
            webSocketService.stopMonitoring(executionId);

            // Notify completion via callback
            if (completionCallback != null) {
                completionCallback.onStrategyCompleted(executionId, reason);
            }

            log.info("[{} MODE] Successfully exited all legs for execution {}", tradingMode, executionId);

        } catch (Exception | KiteException e) {
            log.error("Error exiting legs for execution {}: {}", executionId, e.getMessage(), e);
        }
    }

    @Override
    public String getStrategyName() {
        return "ATM Straddle";
    }

    @Override
    public String getStrategyDescription() {
        return String.format("Buy ATM Call + Buy ATM Put (Non-directional strategy with SL=%.1fpts, Target=%.1fpts) - Supports Paper & Live Trading",
                           strategyConfig.getDefaultStopLossPoints(),
                           strategyConfig.getDefaultTargetPoints());
    }
}
