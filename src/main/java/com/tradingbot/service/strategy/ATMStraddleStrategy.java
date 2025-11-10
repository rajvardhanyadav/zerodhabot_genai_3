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
import com.tradingbot.util.StrategyConstants;
import com.zerodhatech.kiteconnect.kitehttp.exceptions.KiteException;
import com.zerodhatech.models.Instrument;
import com.zerodhatech.models.Order;
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
 * <p>
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

        double stopLossPoints = getStopLossPoints(request);
        double targetPoints = getTargetPoints(request);
        String tradingMode = getTradingMode();

        log.info(StrategyConstants.LOG_EXECUTING_STRATEGY,
                tradingMode, request.getInstrumentType(), stopLossPoints, targetPoints);

        double spotPrice = getCurrentSpotPrice(request.getInstrumentType());
        log.info("Current spot price: {}", spotPrice);

        double atmStrike = getATMStrike(spotPrice, request.getInstrumentType());
        log.info("ATM Strike: {}", atmStrike);

        List<Instrument> instruments = getOptionInstruments(request.getInstrumentType(), request.getExpiry());
        log.info("Found {} option instruments for {}", instruments.size(), request.getInstrumentType());

        Instrument atmCall = findOptionInstrument(instruments, atmStrike, StrategyConstants.OPTION_TYPE_CALL);
        Instrument atmPut = findOptionInstrument(instruments, atmStrike, StrategyConstants.OPTION_TYPE_PUT);

        validateATMOptions(atmCall, atmPut, atmStrike);

        List<StrategyExecutionResponse.OrderDetail> orderDetails = new ArrayList<>();
        int quantity = calculateOrderQuantity(request);
        String orderType = getOrderType(request);

        // Place both legs
        placeCallLeg(atmCall, quantity, orderType, orderDetails, tradingMode);
        placePutLeg(atmPut, quantity, orderType, orderDetails, tradingMode);

        double totalPremium = calculateTotalPremium(orderDetails);

        setupMonitoring(executionId, atmCall, atmPut,
                orderDetails.get(0).getOrderId(), orderDetails.get(1).getOrderId(),
                quantity, orderDetails, stopLossPoints, targetPoints, completionCallback);

        return buildSuccessResponse(executionId, orderDetails, totalPremium, stopLossPoints, targetPoints, tradingMode);
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
                atmCall != null ? atmCall.tradingsymbol : "null",
                atmPut != null ? atmPut.tradingsymbol : "null");

        if (atmCall == null || atmPut == null) {
            throw new RuntimeException(StrategyConstants.ERROR_ATM_OPTIONS_NOT_FOUND + atmStrike);
        }
    }

    private void placeCallLeg(Instrument atmCall, int quantity, String orderType,
                              List<StrategyExecutionResponse.OrderDetail> orderDetails,
                              String tradingMode) throws KiteException, IOException {
        log.info(StrategyConstants.LOG_PLACING_ORDER, tradingMode, StrategyConstants.OPTION_TYPE_CALL, atmCall.tradingsymbol);

        OrderRequest callOrder = createOrderRequest(atmCall.tradingsymbol, StrategyConstants.TRANSACTION_BUY,
                quantity, orderType, null);
        OrderResponse callOrderResponse = unifiedTradingService.placeOrder(callOrder);

        validateOrderResponse(callOrderResponse, "Call");

        double callPrice = getOrderPrice(callOrderResponse.getOrderId());
        orderDetails.add(createOrderDetail(callOrderResponse.getOrderId(), atmCall, quantity, callPrice));
    }

    private void placePutLeg(Instrument atmPut, int quantity, String orderType,
                             List<StrategyExecutionResponse.OrderDetail> orderDetails,
                             String tradingMode) throws KiteException, IOException {
        log.info(StrategyConstants.LOG_PLACING_ORDER, tradingMode, StrategyConstants.OPTION_TYPE_PUT, atmPut.tradingsymbol);

        OrderRequest putOrder = createOrderRequest(atmPut.tradingsymbol, StrategyConstants.TRANSACTION_BUY,
                quantity, orderType, null);
        OrderResponse putOrderResponse = unifiedTradingService.placeOrder(putOrder);

        validateOrderResponse(putOrderResponse, "Put");

        double putPrice = getOrderPrice(putOrderResponse.getOrderId());
        orderDetails.add(createOrderDetail(putOrderResponse.getOrderId(), atmPut, quantity, putPrice));
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
        return new StrategyExecutionResponse.OrderDetail(
                orderId,
                instrument.tradingsymbol,
                instrument.instrument_type.equals("CE") ? StrategyConstants.OPTION_TYPE_CALL : StrategyConstants.OPTION_TYPE_PUT,
                Double.valueOf(instrument.strike),
                quantity,
                price,
                StrategyConstants.ORDER_STATUS_COMPLETED
        );
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
     * Setup real-time monitoring with stop loss and target
     * Fetches entry prices from orders and validates order completion before starting monitoring
     */
    private void setupMonitoring(String executionId, Instrument callInstrument, Instrument putInstrument,
                                 String callOrderId, String putOrderId,
                                 int quantity, List<StrategyExecutionResponse.OrderDetail> orderDetails,
                                 double stopLossPoints, double targetPoints,
                                 StrategyCompletionCallback completionCallback) {

        try {
            List<Order> callOrderHistory = unifiedTradingService.getOrderHistory(callOrderId);
            List<Order> putOrderHistory = unifiedTradingService.getOrderHistory(putOrderId);

            if (!validateOrderHistories(callOrderHistory, putOrderHistory, callOrderId, putOrderId)) {
                return;
            }

            Order latestCallOrder = getLatestOrder(callOrderHistory);
            Order latestPutOrder = getLatestOrder(putOrderHistory);

            if (!validateOrderCompletion(latestCallOrder, latestPutOrder, callOrderId, putOrderId)) {
                return;
            }

            double callEntryPrice = getOrderPrice(callOrderId);
            double putEntryPrice = getOrderPrice(putOrderId);

            if (!validateEntryPrices(callEntryPrice, putEntryPrice)) {
                return;
            }

            log.info("Orders validated - Call: {} (Price: {}), Put: {} (Price: {})",
                    latestCallOrder.status, callEntryPrice, latestPutOrder.status, putEntryPrice);

            PositionMonitor monitor = createPositionMonitor(executionId, stopLossPoints, targetPoints,
                    callOrderId, putOrderId, callInstrument, putInstrument,
                    callEntryPrice, putEntryPrice, quantity, completionCallback);

            startWebSocketMonitoring(executionId, monitor, callEntryPrice, putEntryPrice);

        } catch (Exception | KiteException e) {
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
                                                  int quantity, StrategyCompletionCallback completionCallback) {
        PositionMonitor monitor = new PositionMonitor(executionId, stopLossPoints, targetPoints);

        monitor.addLeg(callOrderId, callInstrument.tradingsymbol, callInstrument.instrument_token,
                callEntryPrice, quantity, StrategyConstants.OPTION_TYPE_CALL);

        monitor.addLeg(putOrderId, putInstrument.tradingsymbol, putInstrument.instrument_token,
                putEntryPrice, quantity, StrategyConstants.OPTION_TYPE_PUT);

        monitor.setExitCallback(reason -> {
            log.warn("Exit triggered for execution {}: {}", executionId, reason);
            exitAllLegs(executionId, callOrderId, putOrderId, callInstrument.tradingsymbol,
                    putInstrument.tradingsymbol, quantity, reason, completionCallback);
        });

        return monitor;
    }

    private void startWebSocketMonitoring(String executionId, PositionMonitor monitor,
                                          double callEntryPrice, double putEntryPrice) {
        if (!webSocketService.isConnected()) {
            webSocketService.connect();
        }

        webSocketService.startMonitoring(executionId, monitor);

        log.info("Position monitoring started for execution: {} with Call price: {}, Put price: {}",
                executionId, callEntryPrice, putEntryPrice);
    }

    /**
     * Exit all legs when SL or Target is hit
     */
    private void exitAllLegs(String executionId, String callOrderId, String putOrderId,
                             String callSymbol, String putSymbol, int quantity, String reason,
                             StrategyCompletionCallback completionCallback) {
        try {
            String tradingMode = getTradingMode();
            log.info(StrategyConstants.LOG_EXITING_LEGS, tradingMode, executionId, reason);

            exitLeg(callSymbol, quantity, "Call", tradingMode);
            exitLeg(putSymbol, quantity, "Put", tradingMode);

            webSocketService.stopMonitoring(executionId);

            if (completionCallback != null) {
                completionCallback.onStrategyCompleted(executionId, reason);
            }

            log.info(StrategyConstants.LOG_ALL_LEGS_EXITED, tradingMode, executionId);

        } catch (Exception | KiteException e) {
            log.error("Error exiting legs for execution {}: {}", executionId, e.getMessage(), e);
        }
    }

    private void exitLeg(String symbol, int quantity, String legName, String tradingMode)
            throws KiteException, IOException {
        OrderRequest exitOrder = createOrderRequest(symbol, StrategyConstants.TRANSACTION_SELL,
                quantity, StrategyConstants.ORDER_TYPE_MARKET, null);
        OrderResponse exitResponse = unifiedTradingService.placeOrder(exitOrder);

        if (StrategyConstants.ORDER_STATUS_SUCCESS.equals(exitResponse.getStatus())) {
            log.info(StrategyConstants.LOG_LEG_EXITED, tradingMode, legName, exitResponse.getOrderId());
        } else {
            log.error("Failed to exit {} leg: {}", legName, exitResponse.getMessage());
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
