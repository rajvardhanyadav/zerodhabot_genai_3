package com.tradingbot.service.strategy;

import com.tradingbot.config.StrategyConfig;
import com.tradingbot.model.SlTargetMode;
import com.tradingbot.service.UnifiedTradingService;
import com.tradingbot.service.strategy.monitoring.PositionMonitorV2;
import com.tradingbot.service.strategy.monitoring.WebSocketService;
import com.tradingbot.util.CurrentUserContext;
import com.tradingbot.util.StrategyConstants;
import com.zerodhatech.kiteconnect.kitehttp.exceptions.KiteException;
import com.zerodhatech.models.Instrument;
import com.zerodhatech.models.Order;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * Helper for setting up position monitoring for straddle strategies.
 * <p>
 * Extracts monitoring setup logic from SellATMStraddleStrategy for better maintainability.
 * HFT-optimized with parallel order history fetching.
 *
 * @since 4.1
 */
@Slf4j
@Component
public class MonitoringSetupHelper {

    private final UnifiedTradingService unifiedTradingService;
    private final WebSocketService webSocketService;
    private final StrategyConfig strategyConfig;

    private static final LocalTime DEFAULT_FORCED_EXIT_TIME = LocalTime.of(15, 10);
    private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.ofPattern("HH:mm");

    public MonitoringSetupHelper(UnifiedTradingService unifiedTradingService,
                                 WebSocketService webSocketService,
                                 StrategyConfig strategyConfig) {
        this.unifiedTradingService = unifiedTradingService;
        this.webSocketService = webSocketService;
        this.strategyConfig = strategyConfig;
    }

    /**
     * Configuration parameters for monitoring setup.
     */
    public record MonitoringParams(
            String executionId,
            Instrument callInstrument,
            Instrument putInstrument,
            String callOrderId,
            String putOrderId,
            int quantity,
            double stopLossPoints,
            double targetPoints,
            double targetDecayPct,
            double stopLossExpansionPct,
            SlTargetMode slTargetMode,
            StrategyCompletionCallback completionCallback
    ) {}

    /**
     * Callbacks for position monitor events.
     */
    public record MonitorCallbacks(
            Consumer<String> exitCallback,
            BiConsumer<String, String> individualLegExitCallback,
            LegReplacementCallback legReplacementCallback
    ) {}

    /**
     * Functional interface for leg replacement callback.
     */
    @FunctionalInterface
    public interface LegReplacementCallback {
        void onLegReplacement(String exitedLegSymbol, String legTypeToAdd, double targetPremium,
                              String lossMakingLegSymbol, double exitedLegLtp);
    }

    /**
     * Setup monitoring asynchronously.
     * <p>
     * HFT: Spawns async task to avoid blocking the main thread.
     *
     * @param params     monitoring parameters
     * @param callbacks  event callbacks
     * @param executor   executor for async operations
     */
    public void setupMonitoringAsync(MonitoringParams params, MonitorCallbacks callbacks, ExecutorService executor) {
        final String ownerUserId = CurrentUserContext.getUserId();
        if (ownerUserId == null || ownerUserId.isBlank()) {
            log.error("No user context for monitoring setup, executionId: {}", params.executionId());
            return;
        }

        CompletableFuture.runAsync(
                CurrentUserContext.wrapWithContext(() ->
                        setupMonitoringInternal(params, callbacks, executor, ownerUserId)),
                executor
        ).exceptionally(ex -> {
            log.error("Error setting up monitoring for {}: {}", params.executionId(), ex.getMessage(), ex);
            return null;
        });
    }

    /**
     * Internal monitoring setup.
     */
    private void setupMonitoringInternal(MonitoringParams params, MonitorCallbacks callbacks,
                                         ExecutorService executor, String ownerUserId) {
        try {
            // HFT: Parallel fetch of order histories
            OrderValidationResult validation = validateOrdersParallel(
                    params.callOrderId(), params.putOrderId(), executor);

            if (validation == null) {
                return;
            }

            log.info("Orders validated - Call: {} (Price: {}), Put: {} (Price: {})",
                    validation.callOrder().status, validation.callEntryPrice(),
                    validation.putOrder().status, validation.putEntryPrice());

            PositionMonitorV2 monitor = createPositionMonitor(params, validation, ownerUserId);
            setupCallbacks(monitor, params, callbacks);

            startWebSocketMonitoring(params.executionId(), monitor, validation);

        } catch (Exception e) {
            log.error("Error in monitoring setup for {}: {}", params.executionId(), e.getMessage(), e);
        }
    }

    /**
     * Result of order validation containing prices and orders.
     */
    private record OrderValidationResult(
            Order callOrder,
            Order putOrder,
            double callEntryPrice,
            double putEntryPrice
    ) {}

    /**
     * Validate orders and fetch prices in parallel.
     */
    private OrderValidationResult validateOrdersParallel(String callOrderId, String putOrderId,
                                                         ExecutorService executor) {
        // Parallel fetch of order histories
        CompletableFuture<List<Order>> callHistoryFuture = CompletableFuture.supplyAsync(
                CurrentUserContext.wrapSupplier(() -> fetchOrderHistorySafe(callOrderId)), executor);

        CompletableFuture<List<Order>> putHistoryFuture = CompletableFuture.supplyAsync(
                CurrentUserContext.wrapSupplier(() -> fetchOrderHistorySafe(putOrderId)), executor);

        List<Order> callOrderHistory = callHistoryFuture.join();
        List<Order> putOrderHistory = putHistoryFuture.join();

        if (callOrderHistory.isEmpty() || putOrderHistory.isEmpty()) {
            log.error("Order history fetch failed - Call: {}, Put: {}", callOrderId, putOrderId);
            return null;
        }

        Order latestCallOrder = callOrderHistory.get(callOrderHistory.size() - 1);
        Order latestPutOrder = putOrderHistory.get(putOrderHistory.size() - 1);

        if (!StrategyConstants.ORDER_STATUS_COMPLETE.equals(latestCallOrder.status)) {
            log.warn("Call order {} not complete: {}", callOrderId, latestCallOrder.status);
            return null;
        }

        if (!StrategyConstants.ORDER_STATUS_COMPLETE.equals(latestPutOrder.status)) {
            log.warn("Put order {} not complete: {}", putOrderId, latestPutOrder.status);
            return null;
        }

        // Parallel fetch of prices
        CompletableFuture<Double> callPriceFuture = CompletableFuture.supplyAsync(
                CurrentUserContext.wrapSupplier(() -> fetchOrderPriceSafe(callOrderId)), executor);

        CompletableFuture<Double> putPriceFuture = CompletableFuture.supplyAsync(
                CurrentUserContext.wrapSupplier(() -> fetchOrderPriceSafe(putOrderId)), executor);

        double callEntryPrice = callPriceFuture.join();
        double putEntryPrice = putPriceFuture.join();

        if (callEntryPrice == 0.0 || putEntryPrice == 0.0) {
            log.error("Invalid entry prices - Call: {}, Put: {}", callEntryPrice, putEntryPrice);
            return null;
        }

        return new OrderValidationResult(latestCallOrder, latestPutOrder, callEntryPrice, putEntryPrice);
    }

    private List<Order> fetchOrderHistorySafe(String orderId) {
        try {
            return unifiedTradingService.getOrderHistory(orderId);
        } catch (Exception | KiteException e) {
            log.error("Failed to fetch order history for {}: {}", orderId, e.getMessage());
            return Collections.emptyList();
        }
    }

    private double fetchOrderPriceSafe(String orderId) {
        try {
            List<Order> history = unifiedTradingService.getOrderHistory(orderId);
            if (!history.isEmpty()) {
                Order order = history.get(history.size() - 1);
                // averagePrice can be a String in Kite API, parse safely
                if (order.averagePrice != null && !order.averagePrice.isEmpty()) {
                    double avgPrice = Double.parseDouble(order.averagePrice);
                    if (avgPrice > 0) {
                        return avgPrice;
                    }
                }
            }
        } catch (Exception | KiteException e) {
            log.error("Failed to fetch order price for {}: {}", orderId, e.getMessage());
        }
        return 0.0;
    }

    /**
     * Create position monitor with all configuration.
     */
    private PositionMonitorV2 createPositionMonitor(MonitoringParams params,
                                                    OrderValidationResult validation,
                                                    String ownerUserId) {
        LocalTime forcedExitTime = parseForcedExitTime(strategyConfig.getAutoSquareOffTime());
        double combinedEntryPremium = validation.callEntryPrice() + validation.putEntryPrice();

        boolean premiumBasedExitEnabled = (params.slTargetMode() == SlTargetMode.PREMIUM)
                || (params.slTargetMode() == null && strategyConfig.isPremiumBasedExitEnabled());

        PositionMonitorV2 monitor = new PositionMonitorV2(
                params.executionId(),
                params.stopLossPoints(),
                params.targetPoints(),
                PositionMonitorV2.PositionDirection.SHORT,
                strategyConfig.isTrailingStopEnabled(),
                strategyConfig.getTrailingActivationPoints(),
                strategyConfig.getTrailingDistancePoints(),
                strategyConfig.isAutoSquareOffEnabled(),
                forcedExitTime,
                premiumBasedExitEnabled,
                combinedEntryPremium,
                params.targetDecayPct(),
                params.stopLossExpansionPct(),
                params.slTargetMode()
        );

        monitor.addLeg(params.callOrderId(), params.callInstrument().tradingsymbol,
                params.callInstrument().instrument_token,
                validation.callEntryPrice(), params.quantity(), StrategyConstants.OPTION_TYPE_CALL);

        monitor.addLeg(params.putOrderId(), params.putInstrument().tradingsymbol,
                params.putInstrument().instrument_token,
                validation.putEntryPrice(), params.quantity(), StrategyConstants.OPTION_TYPE_PUT);

        monitor.setOwnerUserId(ownerUserId);

        return monitor;
    }

    /**
     * Setup callbacks with user context propagation.
     */
    private void setupCallbacks(PositionMonitorV2 monitor, MonitoringParams params, MonitorCallbacks callbacks) {
        // Exit callback
        monitor.setExitCallback(reason -> {
            executeWithUserContext(monitor, () -> {
                log.warn("Exit triggered for {}: {}", params.executionId(), reason);
                callbacks.exitCallback().accept(reason);
            });
        });

        // Individual leg exit callback
        monitor.setIndividualLegExitCallback((legSymbol, reason) -> {
            executeWithUserContext(monitor, () -> {
                log.warn("Individual leg exit for {}: leg={}, reason={}",
                        params.executionId(), legSymbol, reason);
                callbacks.individualLegExitCallback().accept(legSymbol, reason);
            });
        });

        // Leg replacement callback
        monitor.setLegReplacementCallback((exitedLegSymbol, legTypeToAdd, targetPremium,
                                           lossMakingLegSymbol, exitedLegLtp) -> {
            executeWithUserContext(monitor, () -> {
                log.info("Leg replacement for {}: exitedLeg={}, newLegType={}, targetPremium={}",
                        params.executionId(), exitedLegSymbol, legTypeToAdd, targetPremium);
                callbacks.legReplacementCallback().onLegReplacement(
                        exitedLegSymbol, legTypeToAdd, targetPremium, lossMakingLegSymbol, exitedLegLtp);
            });
        });
    }

    /**
     * Execute action with proper user context handling.
     */
    private void executeWithUserContext(PositionMonitorV2 monitor, Runnable action) {
        String previousUser = CurrentUserContext.getUserId();
        try {
            String monitorOwner = monitor.getOwnerUserId();
            if (monitorOwner != null && !monitorOwner.isBlank()) {
                CurrentUserContext.setUserId(monitorOwner);
            }
            action.run();
        } finally {
            if (previousUser != null && !previousUser.isBlank()) {
                CurrentUserContext.setUserId(previousUser);
            } else {
                CurrentUserContext.clear();
            }
        }
    }

    /**
     * Start WebSocket monitoring.
     */
    private void startWebSocketMonitoring(String executionId, PositionMonitorV2 monitor,
                                          OrderValidationResult validation) {
        double totalPremium = (validation.callEntryPrice() + validation.putEntryPrice())
                * monitor.getLegs().get(0).getQuantity();

        log.info("Starting monitoring for {} with total premium: {}", executionId, totalPremium);

        if (!webSocketService.isConnected()) {
            webSocketService.connect();
        }

        webSocketService.startMonitoring(executionId, monitor);
        log.info("Position monitoring started for: {}", executionId);
    }

    /**
     * Parse forced exit time from configuration.
     *
     * @param timeString time in HH:mm format
     * @return LocalTime, defaults to 15:10 if parsing fails
     */
    public LocalTime parseForcedExitTime(String timeString) {
        if (timeString == null || timeString.isBlank()) {
            log.warn("Forced exit time not configured, using default 15:10 IST");
            return DEFAULT_FORCED_EXIT_TIME;
        }
        try {
            return LocalTime.parse(timeString, TIME_FORMATTER);
        } catch (Exception e) {
            log.warn("Failed to parse forced exit time '{}', using default: {}", timeString, e.getMessage());
            return DEFAULT_FORCED_EXIT_TIME;
        }
    }
}


