package com.tradingbot.service;

import com.tradingbot.dto.OrderRequest;
import com.tradingbot.dto.OrderResponse;
import com.tradingbot.dto.StrategyExecutionResponse;
import com.tradingbot.dto.StrategyRequest;
import com.tradingbot.model.StrategyCompletionReason;
import com.tradingbot.model.StrategyExecution;
import com.tradingbot.model.StrategyStatus;
import com.tradingbot.model.StrategyExecution.LegLifecycleState;
import com.tradingbot.service.strategy.StrategyFactory;
import com.tradingbot.service.strategy.TradingStrategy;
import com.tradingbot.service.strategy.monitoring.WebSocketService;
import com.tradingbot.util.CurrentUserContext;
import com.tradingbot.util.StrategyConstants;
import com.zerodhatech.kiteconnect.kitehttp.exceptions.KiteException;
import com.zerodhatech.models.Order;
import com.zerodhatech.models.Instrument;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

import static com.tradingbot.service.TradingConstants.*;
import static com.tradingbot.util.StrategyConstants.TRANSACTION_BUY;
import static com.tradingbot.util.StrategyConstants.TRANSACTION_SELL;

@Service
@RequiredArgsConstructor
@Slf4j
public class StrategyService {

    private final TradingService tradingService;
    private final UnifiedTradingService unifiedTradingService;
    private final StrategyFactory strategyFactory;
    private final WebSocketService webSocketService;
    private final ApplicationEventPublisher eventPublisher;

    // Field injection with @Lazy to break circular dependency
    @Autowired
    @Lazy
    private com.tradingbot.service.strategy.StrategyRestartScheduler strategyRestartScheduler;

    // Keyed by executionId but owned by userId; maintain both maps for efficient lookups
    private final Map<String, StrategyExecution> executionsById = new ConcurrentHashMap<>();

    /**
     * Create and register a new StrategyExecution for the given request.
     * If parentExecutionId is non-null, link this execution into an auto-restart chain.
     */
    public StrategyExecution createAndRegisterExecution(StrategyRequest request, String userId, String parentExecutionId) {
        String executionId = UUID.randomUUID().toString();
        StrategyExecution execution = new StrategyExecution();
        execution.setExecutionId(executionId);
        execution.setUserId(userId);
        execution.setStrategyType(request.getStrategyType());
        execution.setInstrumentType(request.getInstrumentType());
        execution.setExpiry(request.getExpiry());
        execution.setStatus(StrategyStatus.EXECUTING);
        execution.setTimestamp(System.currentTimeMillis());
        execution.setStopLossPoints(request.getStopLossPoints());
        execution.setTargetPoints(request.getTargetPoints());
        execution.setLots(request.getLots());
        execution.setTradingMode(unifiedTradingService.isPaperTradingEnabled()
                ? StrategyConstants.TRADING_MODE_PAPER
                : StrategyConstants.TRADING_MODE_LIVE);

        // Chain linkage
        execution.setParentExecutionId(parentExecutionId);
        if (parentExecutionId == null) {
            // First execution in the chain: root is itself, count is 0
            execution.setRootExecutionId(executionId);
            execution.setAutoRestartCount(0);
        } else {
            // Child execution: inherit root and increment restart count
            StrategyExecution parent = executionsById.get(parentExecutionId);
            String rootId = parent != null && parent.getRootExecutionId() != null
                    ? parent.getRootExecutionId()
                    : parentExecutionId;
            execution.setRootExecutionId(rootId);
            int parentCount = parent != null ? parent.getAutoRestartCount() : 0;
            execution.setAutoRestartCount(parentCount + 1);
            log.info("Linking execution {} as child of {} (root={}, restartCount={})",
                     executionId, parentExecutionId, rootId, execution.getAutoRestartCount());
        }

        executionsById.put(executionId, execution);
        return execution;
    }

    /**
     * Execute a trading strategy (user-initiated path)
     */
    public StrategyExecutionResponse executeStrategy(StrategyRequest request) throws KiteException, IOException {
        String userId = CurrentUserContext.getRequiredUserId();
        log.info("Executing strategy: {} for instrument: {} by user {}", request.getStrategyType(), request.getInstrumentType(), userId);

        StrategyExecution execution = createAndRegisterExecution(request, userId, null);
        String executionId = execution.getExecutionId();

        try {
            // Get the appropriate strategy implementation from factory
            TradingStrategy strategy = strategyFactory.getStrategy(request.getStrategyType());

            // Execute the strategy with completion callback
            StrategyExecutionResponse response = strategy.execute(request, executionId,
                this::handleStrategyCompletion);

            // Set execution status based on response status
            if (StrategyStatus.ACTIVE.name().equalsIgnoreCase(response.getStatus())) {
                execution.setStatus(StrategyStatus.ACTIVE);
                execution.setMessage(StrategyConstants.MSG_STRATEGY_ACTIVE);

                // Store order legs for later stopping
                if (response.getOrders() != null && !response.getOrders().isEmpty()) {
                    updateOrderLegs(executionId, response.getOrders());
                }

                log.info("Strategy {} is ACTIVE - positions being monitored (user={})", executionId, userId);
            } else if (StrategyStatus.COMPLETED.name().equalsIgnoreCase(response.getStatus())) {
                execution.setStatus(StrategyStatus.COMPLETED);
                execution.setMessage(StrategyConstants.MSG_STRATEGY_COMPLETED);
                log.info("Strategy {} COMPLETED successfully (user={})", executionId, userId);
            } else {
                execution.setStatus(StrategyStatus.FAILED);
                execution.setMessage(response.getMessage());
                log.info("Strategy {} status: {} (user={})", executionId, response.getStatus(), userId);
            }

            return response;

        } catch (Exception e) {
            execution.setStatus(StrategyStatus.FAILED);
            execution.setMessage("Strategy execution failed: " + e.getMessage());
            log.error("Strategy execution failed for user={} executionId={}", userId, executionId, e);
            throw e;
        }
    }

    /**
     * Get all active strategies for current user
     */
    public List<StrategyExecution> getActiveStrategies() {
        String userId = CurrentUserContext.getRequiredUserId();
        List<StrategyExecution> list = new ArrayList<>();
        for (StrategyExecution se : executionsById.values()) {
            if (se.getUserId() != null && se.getUserId().equals(userId)) {
                list.add(se);
            }
        }
        return list;
    }

    /**
     * Get strategy by execution ID for current user
     */
    public StrategyExecution getStrategy(String executionId) {
        StrategyExecution se = executionsById.get(executionId);
        String userId = CurrentUserContext.getRequiredUserId();
        if (se != null && userId.equals(se.getUserId())) {
            return se;
        }
        return null;
    }

    /**
     * Handle completion of a strategy execution with structured reason.
     * This method no longer triggers auto-restart directly to avoid circular dependency.
     * Instead, StrategyRestartScheduler will call this as a listener.
     */
    public void handleStrategyCompletion(String executionId, StrategyCompletionReason reason) {
        StrategyExecution execution = executionsById.get(executionId);
        if (execution != null) {
            execution.setStatus(StrategyStatus.COMPLETED);
            execution.setCompletionReason(reason);
            execution.setMessage("Strategy completed - " + reason);
            log.info("Strategy {} marked as COMPLETED: {} (user={})", executionId, reason, execution.getUserId());
            eventPublisher.publishEvent(new StrategyCompletionEvent(this, execution));
        } else {
            log.warn("Attempted to mark non-existent strategy as completed: {}", executionId);
        }
    }

    /**
     * Update strategy status to COMPLETED when both legs are closed
     */
    @Deprecated
    public void markStrategyAsCompleted(String executionId, String reason) {
        log.debug("markStrategyAsCompleted invoked for {} with reason={} (deprecated path)", executionId, reason);
        handleStrategyCompletion(executionId, StrategyCompletionReason.OTHER);
    }

    /**
     * Get available expiry dates for an instrument
     */
    public List<String> getAvailableExpiries(String instrumentType) throws KiteException, IOException {
        log.info("Fetching available expiries for instrument: {}", instrumentType);

        List<Instrument> allInstruments = tradingService.getInstruments(EXCHANGE_NFO);

        String instrumentName = getInstrumentName(instrumentType);
        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd");

        List<String> expiries = allInstruments.stream()
            .filter(i -> i.name != null && i.name.equals(instrumentName))
            .filter(i -> i.expiry != null)
            .filter(i -> i.expiry.after(new Date()))
            .map(i -> sdf.format(i.expiry))
            .distinct()
            .sorted()
            .toList();

        log.info("Found {} expiry dates for {}: {}", expiries.size(), instrumentType, expiries);

        if (expiries.isEmpty()) {
            log.warn("No expiries found for instrument: {}", instrumentType);
        }

        return expiries;
    }

    /**
     * Get available instruments with their details
     */
    public List<InstrumentDetail> getAvailableInstruments() throws KiteException {
        log.info("Fetching available instruments from Kite API");

        List<InstrumentDetail> instrumentDetails = new ArrayList<>();
        String[] supportedInstruments = {"NIFTY", "BANKNIFTY"};
        log.info("Supported instruments: {}", Arrays.toString(supportedInstruments));
        for (String instrumentCode : supportedInstruments) {
            try {
                int lotSize = fetchLotSizeFromKite(instrumentCode);
                double strikeInterval = getStrikeInterval(instrumentCode);
                String displayName = getInstrumentDisplayName(instrumentCode);

                instrumentDetails.add(new InstrumentDetail(
                    instrumentCode,
                    displayName,
                    lotSize,
                    strikeInterval
                ));

                log.debug("Added instrument: {} with lot size: {}", instrumentCode, lotSize);

            } catch (Exception e) {
                log.error("Error fetching details for instrument {}: {}", instrumentCode, e.getMessage());
            }
        }

        log.info("Successfully fetched {} instruments", instrumentDetails.size());
        return instrumentDetails;
    }

    /**
     * Fetch lot size from Kite API
     */
    private int fetchLotSizeFromKite(String instrumentType) throws KiteException, IOException {
        log.info("Fetching lot size for instrument: {} from Kite API", instrumentType);
        List<Instrument> allInstruments = tradingService.getInstruments(EXCHANGE_NFO);
        String instrumentName = getInstrumentName(instrumentType);

        Optional<Instrument> instrument = allInstruments.stream()
                .filter(i -> i.name != null && i.name.equals(instrumentName))
                .filter(i -> i.lot_size > 0)
                .findFirst();

        if (instrument.isPresent()) {
            int lotSize = instrument.get().lot_size;
            log.info("Found lot size for {}: {}", instrumentType, lotSize);
            return lotSize;
        } else {
            log.warn("Lot size not found in Kite API for {}, using fallback value", instrumentType);
            return getFallbackLotSize(instrumentType);
        }
    }

    /**
     * Get fallback lot size when Kite API is unavailable
     */
    private int getFallbackLotSize(String instrumentType) {
        return switch (instrumentType.toUpperCase()) {
            case "NIFTY" -> 75;
            case "BANKNIFTY" -> 35;
            default -> throw new IllegalArgumentException("Unsupported instrument type: " + instrumentType);
        };
    }

    /**
     * Get strike interval based on instrument
     */
    private double getStrikeInterval(String instrumentType) {
        return switch (instrumentType.toUpperCase()) {
            case "NIFTY" -> 50.0;
            case "BANKNIFTY" -> 100.0;
            default -> 50.0;
        };
    }

    /**
     * Get display name for instrument
     */
    private String getInstrumentDisplayName(String instrumentCode) {
        return switch (instrumentCode.toUpperCase()) {
            case "NIFTY" -> "NIFTY 50";
            case "BANKNIFTY" -> "NIFTY BANK";
            default -> instrumentCode;
        };
    }

    /**
     * Get instrument name for Kite API
     */
    private String getInstrumentName(String instrumentType) {
        return switch (instrumentType.toUpperCase()) {
            case "NIFTY" -> "NIFTY";
            case "BANKNIFTY" -> "BANKNIFTY";
            default -> instrumentType.toUpperCase();
        };
    }

    /**
     * Update order legs for a strategy execution
     */
    public void updateOrderLegs(String executionId, List<StrategyExecutionResponse.OrderDetail> orderDetails) {
        StrategyExecution execution = executionsById.get(executionId);
        if (execution != null) {
            List<StrategyExecution.OrderLeg> orderLegs = orderDetails.stream()
                .map(od -> StrategyExecution.OrderLeg.builder()
                    .orderId(od.getOrderId())
                    .tradingSymbol(od.getTradingSymbol())
                    .optionType(od.getOptionType())
                    .quantity(od.getQuantity())
                    .entryPrice(od.getPrice())
                    .entryTransactionType(resolveEntryTransactionType(od))
                    .entryTimestamp(System.currentTimeMillis())
                    .build())
                .collect(Collectors.toList());
            execution.setOrderLegs(orderLegs);
            log.info("Updated order legs for execution {}: {} legs (user={})", executionId, orderLegs.size(), execution.getUserId());
        } else {
            log.warn("Cannot update order legs - execution not found: {}", executionId);
        }
    }

    /**
     * Common method to exit all legs for any strategy
     */
    private Map<String, Object> exitAllLegs(String executionId, List<StrategyExecution.OrderLeg> orderLegs) throws KiteException {
        String tradingMode = unifiedTradingService.isPaperTradingEnabled() ? StrategyConstants.TRADING_MODE_PAPER : StrategyConstants.TRADING_MODE_LIVE;
        log.info("[{} MODE] Exiting all legs for execution {}: {} legs", tradingMode, executionId, orderLegs.size());

        List<Map<String, String>> exitOrders = new ArrayList<>();
        int successCount = 0;
        int failureCount = 0;

        // Close all legs at market price
        for (StrategyExecution.OrderLeg leg : orderLegs) {
            StrategyExecution.OrderLeg workingLeg = leg;
            try {
                workingLeg.setLifecycleState(LegLifecycleState.EXIT_PENDING);
                workingLeg.setExitRequestedAt(System.currentTimeMillis());

                String exitTransactionType = determineExitTransactionType(workingLeg);

                OrderRequest exitOrder = new OrderRequest();
                exitOrder.setTradingSymbol(workingLeg.getTradingSymbol());
                exitOrder.setExchange(EXCHANGE_NFO);
                exitOrder.setTransactionType(exitTransactionType);
                exitOrder.setQuantity(workingLeg.getQuantity());
                exitOrder.setProduct(PRODUCT_MIS);
                exitOrder.setOrderType(ORDER_TYPE_MARKET);
                exitOrder.setValidity(VALIDITY_DAY);

                OrderResponse response = unifiedTradingService.placeOrder(exitOrder);

                workingLeg.setExitOrderId(response.getOrderId());
                workingLeg.setExitTransactionType(exitTransactionType);
                workingLeg.setExitQuantity(workingLeg.getQuantity());
                workingLeg.setExitStatus(response.getStatus());
                workingLeg.setExitMessage(response.getMessage());
                workingLeg.setExitTimestamp(System.currentTimeMillis());

                Double exitPrice = resolveOrderFillPrice(response.getOrderId());
                if (exitPrice != null) {
                    workingLeg.setExitPrice(exitPrice);
                    workingLeg.setRealizedPnl(calculateRealizedPnl(workingLeg, exitPrice));
                }

                Map<String, String> orderResult = new HashMap<>();
                orderResult.put("tradingSymbol", workingLeg.getTradingSymbol());
                orderResult.put("optionType", workingLeg.getOptionType());
                orderResult.put("exitOrderId", response.getOrderId());
                orderResult.put("status", response.getStatus());
                orderResult.put("message", response.getMessage());
                exitOrders.add(orderResult);

                if (STATUS_SUCCESS.equals(response.getStatus())) {
                    successCount++;
                    workingLeg.setLifecycleState(LegLifecycleState.EXITED);
                } else {
                    failureCount++;
                    workingLeg.setLifecycleState(LegLifecycleState.EXIT_FAILED);
                    log.error("Failed to close {} leg: {} - {}", workingLeg.getOptionType(), workingLeg.getTradingSymbol(), response.getMessage());
                }

            } catch (Exception e) {
                failureCount++;
                workingLeg.setLifecycleState(LegLifecycleState.EXIT_FAILED);
                log.error("Error closing {} leg: {}", workingLeg.getOptionType(), workingLeg.getTradingSymbol(), e);
                Map<String, String> orderResult = new HashMap<>();
                orderResult.put("tradingSymbol", workingLeg.getTradingSymbol());
                orderResult.put("optionType", workingLeg.getOptionType());
                orderResult.put("status", STATUS_FAILED);
                orderResult.put("message", "Exception: " + e.getMessage());
                exitOrders.add(orderResult);
            }
        }

        // Stop monitoring for this execution
        try {
            webSocketService.stopMonitoring(executionId);
            log.info("[{} MODE] Stopped monitoring for execution {}", tradingMode, executionId);
        } catch (Exception e) {
            log.error("Error stopping monitoring for execution {}: {}", executionId, e.getMessage());
        }

        Map<String, Object> result = new HashMap<>();
        result.put("executionId", executionId);
        result.put("totalLegs", orderLegs.size());
        result.put("successCount", successCount);
        result.put("failureCount", failureCount);
        result.put("exitOrders", exitOrders);

        // Persist updated leg state on execution for downstream consumers
        StrategyExecution execution = executionsById.get(executionId);
        if (execution != null) {
            execution.setOrderLegs(orderLegs);
        }

        log.info("[{} MODE] Exited all legs for execution {} - {} closed successfully, {} failed",
                 tradingMode, executionId, successCount, failureCount);

        return result;
    }

    public String determineExitTransactionType(StrategyExecution.OrderLeg leg) {
        String optionType = leg.getOptionType();
        if (optionType != null && optionType.toUpperCase().contains("SHORT")) {
            // Leg was opened with a SELL, so we need a BUY to close
            return TRANSACTION_BUY;
        }
        // Default: leg was opened with a BUY, close with SELL
        return TRANSACTION_SELL;
    }

    private String resolveEntryTransactionType(StrategyExecutionResponse.OrderDetail orderDetail) {
        if (orderDetail.getStatus() != null) {
            String normalized = orderDetail.getStatus().toUpperCase(Locale.ROOT);
            if (normalized.contains("SELL")) {
                return TRANSACTION_SELL;
            }
            if (normalized.contains("BUY")) {
                return TRANSACTION_BUY;
            }
        }
        String optionType = orderDetail.getOptionType();
        if (optionType != null && optionType.toUpperCase(Locale.ROOT).contains("SHORT")) {
            return TRANSACTION_SELL;
        }
        return TRANSACTION_BUY;
    }

    public Double resolveOrderFillPrice(String orderId) {
        if (orderId == null) {
            return null;
        }
        try {
            List<Order> history = unifiedTradingService.getOrderHistory(orderId);
            if (history == null || history.isEmpty()) {
                return null;
            }
            Order latest = history.get(history.size() - 1);
            if (latest.averagePrice != null && !latest.averagePrice.isEmpty()) {
                try {
                    return Double.parseDouble(latest.averagePrice);
                } catch (NumberFormatException ignored) {
                    log.warn("Invalid averagePrice '{}' for order {}", latest.averagePrice, orderId);
                }
            }
            if (latest.price != null && !latest.price.isEmpty()) {
                try {
                    return Double.parseDouble(latest.price);
                } catch (NumberFormatException ignored) {
                    log.warn("Invalid price '{}' for order {}", latest.price, orderId);
                }
            }
        } catch (KiteException | IOException e) {
            log.warn("Unable to resolve fill price for order {}: {}", orderId, e.getMessage());
        }
        return null;
    }

    public Double calculateRealizedPnl(StrategyExecution.OrderLeg leg, Double exitPrice) {
        if (leg.getEntryPrice() == null || exitPrice == null || leg.getQuantity() == null) {
            return null;
        }
        double quantity = leg.getQuantity();
        double entryPrice = leg.getEntryPrice();
        boolean wasBuy = TRANSACTION_BUY.equalsIgnoreCase(leg.getEntryTransactionType());
        double pnl = wasBuy
                ? (exitPrice - entryPrice) * quantity
                : (entryPrice - exitPrice) * quantity;
        return Math.round(pnl * 100.0) / 100.0;
    }

    /**
     * Stop a specific strategy by closing all legs
     */
    public Map<String, Object> stopStrategy(String executionId) throws KiteException {
        String userId = CurrentUserContext.getRequiredUserId();
        log.info("Stopping strategy: {} by user {}", executionId, userId);

        StrategyExecution execution = executionsById.get(executionId);
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

        Map<String, Object> result = exitAllLegs(executionId, orderLegs);

        // Update strategy status
        int successCount = (Integer) result.get("successCount");
        int failureCount = (Integer) result.get("failureCount");
        execution.setStatus(StrategyStatus.COMPLETED);
        execution.setMessage(String.format("Strategy stopped manually - %d legs closed successfully, %d failed", successCount, failureCount));

        // Cancel any scheduled auto-restart for this execution
        boolean restartCancelled = strategyRestartScheduler.cancelScheduledRestart(executionId);
        result.put("scheduledRestartCancelled", restartCancelled);

        String tradingMode = unifiedTradingService.isPaperTradingEnabled() ? StrategyConstants.TRADING_MODE_PAPER : StrategyConstants.TRADING_MODE_LIVE;
        log.info("[{} MODE] Strategy {} stopped - {} legs closed successfully, {} failed, scheduled restart cancelled: {} (user={})",
                 tradingMode, executionId, successCount, failureCount, restartCancelled, userId);


        return result;
    }

    /**
     * Stop all active strategies for current user
     */
    public Map<String, Object> stopAllActiveStrategies() throws KiteException {
        String userId = CurrentUserContext.getRequiredUserId();
        log.info("Stopping all active strategies for user {}", userId);

        List<StrategyExecution> activeList = executionsById.values().stream()
            .filter(s -> userId.equals(s.getUserId()))
            .filter(s -> s.getStatus() == StrategyStatus.ACTIVE)
            .toList();

        if (activeList.isEmpty()) {
            log.info("No active strategies found for user {}", userId);

            // Still cancel any scheduled restarts even if no active strategies
            int cancelledRestarts = strategyRestartScheduler.cancelScheduledRestartsForUser(userId);

            Map<String, Object> result = new HashMap<>();
            result.put("message", "No active strategies to stop");
            result.put("totalStrategies", 0);
            result.put("cancelledScheduledRestarts", cancelledRestarts);
            result.put("results", new ArrayList<>());
            return result;
        }

        log.info("Found {} active strategies to stop for user {}", activeList.size(), userId);

        List<Map<String, Object>> results = new ArrayList<>();
        int totalSuccess = 0;
        int totalFailures = 0;

        for (StrategyExecution execution : activeList) {
            try {
                Map<String, Object> stopResult = exitAllLegs(execution.getExecutionId(), execution.getOrderLegs());
                results.add(stopResult);

                int successCount = (Integer) stopResult.get("successCount");
                int failureCount = (Integer) stopResult.get("failureCount");
                totalSuccess += successCount;
                totalFailures += failureCount;
                execution.setStatus(StrategyStatus.COMPLETED);
                execution.setMessage(String.format("Strategy stopped manually - %d legs closed successfully, %d failed", successCount, failureCount));
            } catch (Exception e) {
                log.error("Error stopping strategy {}: {}", execution.getExecutionId(), e.getMessage());
                Map<String, Object> errorResult = new HashMap<>();
                errorResult.put("executionId", execution.getExecutionId());
                errorResult.put("status", STATUS_FAILED);
                errorResult.put("message", e.getMessage());
                results.add(errorResult);
            }
        }

        // Cancel all scheduled auto-restarts for this user
        int cancelledRestarts = strategyRestartScheduler.cancelScheduledRestartsForUser(userId);

        Map<String, Object> summary = new HashMap<>();
        summary.put("message", String.format("Stopped %d strategies", activeList.size()));
        summary.put("totalStrategies", activeList.size());
        summary.put("totalLegsClosedSuccessfully", totalSuccess);
        summary.put("totalLegsFailed", totalFailures);
        summary.put("cancelledScheduledRestarts", cancelledRestarts);
        summary.put("results", results);

        log.info("Stopped all active strategies for user {} - {} legs closed successfully, {} failed, {} scheduled restarts cancelled",
                userId, totalSuccess, totalFailures, cancelledRestarts);

        return summary;
    }

    /**
     * DTO for instrument details
     */
    public record InstrumentDetail(String code, String name, int lotSize, double strikeInterval) {}

    /**
     * Event class for strategy completion
     */
    public record StrategyCompletionEvent(Object source, StrategyExecution execution) {}
}
