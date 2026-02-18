package com.tradingbot.service.strategy;

import com.tradingbot.dto.OrderRequest;
import com.tradingbot.dto.OrderResponse;
import com.tradingbot.model.StrategyCompletionReason;
import com.tradingbot.model.StrategyExecution;
import com.tradingbot.model.StrategyStatus;
import com.tradingbot.service.StrategyService;
import com.tradingbot.service.UnifiedTradingService;
import com.tradingbot.service.strategy.monitoring.WebSocketService;
import com.tradingbot.util.CurrentUserContext;
import com.tradingbot.util.StrategyConstants;
import com.zerodhatech.kiteconnect.kitehttp.exceptions.KiteException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;

import static com.tradingbot.service.TradingConstants.*;

/**
 * Handler for straddle exit operations.
 * <p>
 * Extracts exit logic from SellATMStraddleStrategy for better maintainability.
 * Thread-safe and HFT-optimized with parallel exit order processing.
 *
 * @since 4.1
 */
@Slf4j
@Component
public class StraddleExitHandler {

    private final UnifiedTradingService unifiedTradingService;
    private final StrategyService strategyService;
    private final WebSocketService webSocketService;

    public StraddleExitHandler(UnifiedTradingService unifiedTradingService,
                               @Lazy StrategyService strategyService,
                               WebSocketService webSocketService) {
        this.unifiedTradingService = unifiedTradingService;
        this.strategyService = strategyService;
        this.webSocketService = webSocketService;
    }

    /**
     * Exit all legs for a strategy execution.
     * <p>
     * HFT Optimization: Processes exit orders in parallel using the provided executor.
     *
     * @param executionId         execution ID
     * @param reason              exit reason
     * @param completionCallback  callback on completion
     * @param executor            executor for parallel processing
     */
    public void exitAllLegs(String executionId, String reason,
                            StrategyCompletionCallback completionCallback,
                            ExecutorService executor) {
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

        // HFT: Process all exit orders in parallel
        List<CompletableFuture<Map<String, String>>> exitFutures = new ArrayList<>(orderLegs.size());

        for (StrategyExecution.OrderLeg leg : orderLegs) {
            exitFutures.add(CompletableFuture.supplyAsync(
                    CurrentUserContext.wrapSupplier(() -> processLegExit(leg, tradingMode)),
                    executor
            ));
        }

        // Wait for all exits and collect results
        int successCount = 0;
        int failureCount = 0;

        for (CompletableFuture<Map<String, String>> future : exitFutures) {
            try {
                Map<String, String> result = future.join();
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

        // Stop monitoring
        stopMonitoringSafe(executionId, tradingMode);

        // Invoke callback
        if (completionCallback != null) {
            StrategyCompletionReason mappedReason = mapExitReasonToCompletionReason(reason);
            completionCallback.onStrategyCompleted(executionId, mappedReason);
        }

        log.info("[{} MODE] Exited all legs for execution {} - {} closed successfully, {} failed",
                tradingMode, executionId, successCount, failureCount);
    }

    /**
     * Process a single leg exit.
     * <p>
     * Thread-safe: validates leg lifecycle state before processing.
     *
     * @param leg         the order leg to exit
     * @param tradingMode current trading mode (PAPER or LIVE)
     * @return map containing exit result
     */
    public Map<String, String> processLegExit(StrategyExecution.OrderLeg leg, String tradingMode) {
        Map<String, String> result = new HashMap<>();
        result.put("tradingSymbol", leg.getTradingSymbol());
        result.put("optionType", leg.getOptionType());

        // Validate lifecycle state
        StrategyExecution.LegLifecycleState currentState = leg.getLifecycleState();

        if (currentState == StrategyExecution.LegLifecycleState.EXITED) {
            log.info("Leg {} already EXITED, skipping", leg.getTradingSymbol());
            return successResult(result, "Leg already exited", leg.getExitOrderId());
        }

        if (currentState == StrategyExecution.LegLifecycleState.EXIT_PENDING) {
            log.warn("Leg {} exit already pending, skipping duplicate", leg.getTradingSymbol());
            return successResult(result, "Exit already pending", leg.getExitOrderId());
        }

        if (currentState == StrategyExecution.LegLifecycleState.EXIT_FAILED) {
            log.warn("Leg {} previous exit failed, retrying", leg.getTradingSymbol());
        }

        try {
            leg.setLifecycleState(StrategyExecution.LegLifecycleState.EXIT_PENDING);
            leg.setExitRequestedAt(System.currentTimeMillis());

            String exitTransactionType = strategyService.determineExitTransactionType(leg);

            OrderRequest exitOrder = buildExitOrderRequest(leg, exitTransactionType);
            OrderResponse response = unifiedTradingService.placeOrder(exitOrder);

            updateLegWithExitResponse(leg, response, exitTransactionType);

            result.put("exitOrderId", response.getOrderId());
            result.put("status", response.getStatus());
            result.put("message", response.getMessage());

            if (STATUS_SUCCESS.equals(response.getStatus())) {
                leg.setLifecycleState(StrategyExecution.LegLifecycleState.EXITED);
            } else {
                leg.setLifecycleState(StrategyExecution.LegLifecycleState.EXIT_FAILED);
                log.error("Failed to close {} leg: {} - {}",
                        leg.getOptionType(), leg.getTradingSymbol(), response.getMessage());
            }

        } catch (KiteException | IOException e) {
            handleExitError(leg, result, e);
        } catch (Exception e) {
            handleExitError(leg, result, e);
        }

        return result;
    }

    private OrderRequest buildExitOrderRequest(StrategyExecution.OrderLeg leg, String exitTransactionType) {
        OrderRequest exitOrder = new OrderRequest();
        exitOrder.setTradingSymbol(leg.getTradingSymbol());
        exitOrder.setExchange(EXCHANGE_NFO);
        exitOrder.setTransactionType(exitTransactionType);
        exitOrder.setQuantity(leg.getQuantity());
        exitOrder.setProduct(PRODUCT_MIS);
        exitOrder.setOrderType(ORDER_TYPE_MARKET);
        exitOrder.setValidity(VALIDITY_DAY);
        return exitOrder;
    }

    private void updateLegWithExitResponse(StrategyExecution.OrderLeg leg,
                                           OrderResponse response,
                                           String exitTransactionType) {
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
    }

    private Map<String, String> successResult(Map<String, String> result, String message, String orderId) {
        result.put("status", STATUS_SUCCESS);
        result.put("message", message);
        result.put("exitOrderId", orderId);
        return result;
    }

    private void handleExitError(StrategyExecution.OrderLeg leg, Map<String, String> result, Throwable e) {
        leg.setLifecycleState(StrategyExecution.LegLifecycleState.EXIT_FAILED);
        log.error("Error closing {} leg: {}", leg.getOptionType(), leg.getTradingSymbol(), e);
        result.put("status", STATUS_FAILED);
        result.put("message", "Exception: " + e.getMessage());
    }

    private void stopMonitoringSafe(String executionId, String tradingMode) {
        try {
            webSocketService.stopMonitoring(executionId);
            log.info("[{} MODE] Stopped monitoring for execution {}", tradingMode, executionId);
        } catch (Exception e) {
            log.error("Error stopping monitoring for execution {}: {}", executionId, e.getMessage());
        }
    }

    private String getTradingMode() {
        return unifiedTradingService.isPaperTradingEnabled()
                ? StrategyConstants.TRADING_MODE_PAPER
                : StrategyConstants.TRADING_MODE_LIVE;
    }

    /**
     * Map exit reason string to StrategyCompletionReason enum.
     */
    public StrategyCompletionReason mapExitReasonToCompletionReason(String reason) {
        if (reason == null) {
            return StrategyCompletionReason.TARGET_HIT;
        }

        String upperReason = reason.toUpperCase();

        if (upperReason.contains("TIME_BASED_FORCED_EXIT")) {
            return StrategyCompletionReason.TIME_BASED_EXIT;
        }
        if (upperReason.contains("PREMIUM_DECAY_TARGET_HIT")) {
            return StrategyCompletionReason.TARGET_HIT;
        }
        if (upperReason.contains("PREMIUM_EXPANSION_SL_HIT")) {
            return StrategyCompletionReason.STOPLOSS_HIT;
        }
        if (upperReason.contains("STOP")) {
            return StrategyCompletionReason.STOPLOSS_HIT;
        }

        return StrategyCompletionReason.TARGET_HIT;
    }
}




