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

    public SellATMStraddleStrategy(TradingService tradingService,
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

        // Get option instruments first (needed for delta calculation)
        List<Instrument> instruments = getOptionInstruments(request.getInstrumentType(), request.getExpiry());
        log.info("Found {} option instruments for {}", instruments.size(), request.getInstrumentType());

        // Get expiry date from instruments for delta calculation
        Date expiryDate = instruments.isEmpty() ? null : instruments.get(0).expiry;
        log.info("Using expiry date: {}", expiryDate);

        // Calculate ATM strike using delta-based selection (nearest to ±0.5)
        double atmStrike = expiryDate != null
                ? getATMStrikeByDelta(spotPrice, request.getInstrumentType(), expiryDate)
                : getATMStrike(spotPrice, request.getInstrumentType());

        log.info("ATM Strike (Delta-based): {}", atmStrike);

        Instrument atmCall = findOptionInstrument(instruments, atmStrike, StrategyConstants.OPTION_TYPE_CALL);
        Instrument atmPut = findOptionInstrument(instruments, atmStrike, StrategyConstants.OPTION_TYPE_PUT);

        validateATMOptions(atmCall, atmPut, atmStrike);

        List<StrategyExecutionResponse.OrderDetail> orderDetails = new ArrayList<>();
        int quantity = calculateOrderQuantity(request);
        String orderType = getOrderType(request);

        // Place both legs as SELL
        placeCallLeg(atmCall, quantity, orderType, orderDetails, tradingMode);
        placePutLeg(atmPut, quantity, orderType, orderDetails, tradingMode);

        double totalPremium = calculateTotalPremium(orderDetails);

        setupMonitoring(executionId, atmCall, atmPut,
                orderDetails.get(0).getOrderId(), orderDetails.get(1).getOrderId(),
                quantity, stopLossPoints, targetPoints, completionCallback);

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
        // Mark optionType with SHORT suffix so generic stop-all logic knows to BUY to close
        String baseType = instrument.instrument_type.equals(StrategyConstants.OPTION_TYPE_CALL)
                ? StrategyConstants.OPTION_TYPE_CALL
                : StrategyConstants.OPTION_TYPE_PUT;
        String annotatedType = baseType + "_SHORT";

        return new StrategyExecutionResponse.OrderDetail(
                orderId,
                instrument.tradingsymbol,
                annotatedType,
                Double.valueOf(instrument.strike),
                quantity,
                price,
                StrategyConstants.ORDER_STATUS_COMPLETE
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

    private void setupMonitoring(String executionId, Instrument callInstrument, Instrument putInstrument,
                                 String callOrderId, String putOrderId,
                                 int quantity,
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
                                                  int quantity,
                                                  StrategyCompletionCallback completionCallback) {

        PositionMonitor monitor = new PositionMonitor(executionId, stopLossPoints, targetPoints);

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
        try {
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
        }
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
}
