package com.tradingbot.service;

import com.tradingbot.dto.OrderRequest;
import com.tradingbot.dto.OrderResponse;
import com.tradingbot.dto.StrategyExecutionResponse;
import com.tradingbot.dto.StrategyRequest;
import com.tradingbot.model.StrategyExecution;
import com.tradingbot.model.StrategyStatus;
import com.tradingbot.service.strategy.StrategyFactory;
import com.tradingbot.service.strategy.TradingStrategy;
import com.tradingbot.service.strategy.monitoring.WebSocketService;
import com.zerodhatech.kiteconnect.kitehttp.exceptions.KiteException;
import com.zerodhatech.models.Instrument;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import com.tradingbot.util.StrategyConstants;


import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

import static com.tradingbot.service.TradingConstants.*;

@Service
@RequiredArgsConstructor
@Slf4j
public class StrategyService {

    private final TradingService tradingService;
    private final UnifiedTradingService unifiedTradingService;
    private final StrategyFactory strategyFactory;
    private final WebSocketService webSocketService;
    private final Map<String, StrategyExecution> activeStrategies = new ConcurrentHashMap<>();

    /**
     * Execute a trading strategy
     */
    public StrategyExecutionResponse executeStrategy(StrategyRequest request) throws KiteException, IOException {
        log.info("Executing strategy: {} for instrument: {}", request.getStrategyType(), request.getInstrumentType());

        String executionId = UUID.randomUUID().toString();
        StrategyExecution execution = new StrategyExecution();
        execution.setExecutionId(executionId);
        execution.setStrategyType(request.getStrategyType());
        execution.setInstrumentType(request.getInstrumentType());
        execution.setExpiry(request.getExpiry());
        execution.setStatus(StrategyStatus.EXECUTING);
        execution.setTimestamp(System.currentTimeMillis());

        activeStrategies.put(executionId, execution);

        try {
            // Get the appropriate strategy implementation from factory
            TradingStrategy strategy = strategyFactory.getStrategy(request.getStrategyType());

            // Execute the strategy with completion callback
            StrategyExecutionResponse response = strategy.execute(request, executionId, this::markStrategyAsCompleted);

            // Set execution status based on response status
            if (StrategyStatus.ACTIVE.name().equalsIgnoreCase(response.getStatus())) {
                execution.setStatus(StrategyStatus.ACTIVE);
                execution.setMessage(StrategyConstants.MSG_STRATEGY_ACTIVE);

                // Store order legs for later stopping
                if (response.getOrders() != null && !response.getOrders().isEmpty()) {
                    updateOrderLegs(executionId, response.getOrders());
                }

                log.info("Strategy {} is ACTIVE - positions being monitored", executionId);
            } else if (StrategyStatus.COMPLETED.name().equalsIgnoreCase(response.getStatus())) {
                execution.setStatus(StrategyStatus.COMPLETED);
                execution.setMessage(StrategyConstants.MSG_STRATEGY_COMPLETED);
                log.info("Strategy {} COMPLETED successfully", executionId);
            } else {
                execution.setStatus(StrategyStatus.FAILED);
                execution.setMessage(response.getMessage());
                log.info("Strategy {} status: {}", executionId, response.getStatus());
            }

            return response;

        } catch (Exception e) {
            execution.setStatus(StrategyStatus.FAILED);
            execution.setMessage("Strategy execution failed: " + e.getMessage());
            log.error("Strategy execution failed", e);
            throw e;
        }
    }

    /**
     * Get all active strategies
     */
    public List<StrategyExecution> getActiveStrategies() {
        return new ArrayList<>(activeStrategies.values());
    }

    /**
     * Get strategy by execution ID
     */
    public StrategyExecution getStrategy(String executionId) {
        return activeStrategies.get(executionId);
    }

    /**
     * Update strategy status to COMPLETED when both legs are closed
     */
    public void markStrategyAsCompleted(String executionId, String reason) {
        StrategyExecution execution = activeStrategies.get(executionId);
        if (execution != null) {
            execution.setStatus(StrategyStatus.COMPLETED);
            execution.setMessage("Strategy completed - " + reason);
            log.info("Strategy {} marked as COMPLETED: {}", executionId, reason);
        } else {
            log.warn("Attempted to mark non-existent strategy as completed: {}", executionId);
        }
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
        String[] supportedInstruments = {"NIFTY", "BANKNIFTY", "FINNIFTY"};

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
            case "FINNIFTY" -> 40;
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
            case "FINNIFTY" -> 50.0;
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
            case "FINNIFTY" -> "NIFTY FINSEREXBNK";
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
            case "FINNIFTY" -> "FINNIFTY";
            default -> instrumentType.toUpperCase();
        };
    }

    /**
     * Update order legs for a strategy execution
     */
    public void updateOrderLegs(String executionId, List<StrategyExecutionResponse.OrderDetail> orderDetails) {
        StrategyExecution execution = activeStrategies.get(executionId);
        if (execution != null) {
            List<StrategyExecution.OrderLeg> orderLegs = orderDetails.stream()
                .map(od -> new StrategyExecution.OrderLeg(
                    od.getOrderId(),
                    od.getTradingSymbol(),
                    od.getOptionType(),
                    od.getQuantity(),
                    od.getPrice()
                ))
                .toList();
            execution.setOrderLegs(orderLegs);
            log.info("Updated order legs for execution {}: {} legs", executionId, orderLegs.size());
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
            try {
                OrderRequest exitOrder = new OrderRequest();
                exitOrder.setTradingSymbol(leg.getTradingSymbol());
                exitOrder.setExchange(EXCHANGE_NFO);
                exitOrder.setTransactionType(TRANSACTION_SELL);
                exitOrder.setQuantity(leg.getQuantity());
                exitOrder.setProduct(PRODUCT_MIS);
                exitOrder.setOrderType(ORDER_TYPE_MARKET);
                exitOrder.setValidity(VALIDITY_DAY);

                OrderResponse response = unifiedTradingService.placeOrder(exitOrder);

                Map<String, String> orderResult = new HashMap<>();
                orderResult.put("tradingSymbol", leg.getTradingSymbol());
                orderResult.put("optionType", leg.getOptionType());
                orderResult.put("quantity", String.valueOf(leg.getQuantity()));
                orderResult.put("exitOrderId", response.getOrderId());
                orderResult.put("status", response.getStatus());
                orderResult.put("message", response.getMessage());

                exitOrders.add(orderResult);

                if (STATUS_SUCCESS.equals(response.getStatus())) {
                    successCount++;
                    log.info("[{} MODE] Successfully closed {} leg: {}", tradingMode, leg.getOptionType(), leg.getTradingSymbol());
                } else {
                    failureCount++;
                    log.error("Failed to close {} leg: {} - {}", leg.getOptionType(), leg.getTradingSymbol(), response.getMessage());
                }

            } catch (Exception e) {
                failureCount++;
                log.error("Error closing {} leg: {}", leg.getOptionType(), leg.getTradingSymbol(), e);
                Map<String, String> orderResult = new HashMap<>();
                orderResult.put("tradingSymbol", leg.getTradingSymbol());
                orderResult.put("optionType", leg.getOptionType());
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
        result.put("status", failureCount == 0 ? STATUS_SUCCESS : STATUS_PARTIAL);

        log.info("[{} MODE] Exited all legs for execution {} - {} closed successfully, {} failed",
                 tradingMode, executionId, successCount, failureCount);

        return result;
    }

    /**
     * Stop a specific strategy by closing all legs
     */
    public Map<String, Object> stopStrategy(String executionId) throws KiteException {
        log.info("Stopping strategy: {}", executionId);

        StrategyExecution execution = activeStrategies.get(executionId);
        if (execution == null) {
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

        String tradingMode = unifiedTradingService.isPaperTradingEnabled() ? StrategyConstants.TRADING_MODE_PAPER : StrategyConstants.TRADING_MODE_LIVE;
        log.info("[{} MODE] Strategy {} stopped - {} legs closed successfully, {} failed",
                 tradingMode, executionId, successCount, failureCount);

        return result;
    }

    /**
     * Stop all active strategies
     */
    public Map<String, Object> stopAllActiveStrategies() throws KiteException {
        log.info("Stopping all active strategies");

        List<StrategyExecution> activeList = activeStrategies.values().stream()
            .filter(s -> s.getStatus() == StrategyStatus.ACTIVE)
            .toList();

        if (activeList.isEmpty()) {
            log.info("No active strategies found");
            Map<String, Object> result = new HashMap<>();
            result.put("message", "No active strategies to stop");
            result.put("totalStrategies", 0);
            result.put("results", new ArrayList<>());
            return result;
        }

        log.info("Found {} active strategies to stop", activeList.size());

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

        Map<String, Object> summary = new HashMap<>();
        summary.put("message", String.format("Stopped %d strategies", activeList.size()));
        summary.put("totalStrategies", activeList.size());
        summary.put("totalLegsClosedSuccessfully", totalSuccess);
        summary.put("totalLegsFailed", totalFailures);
        summary.put("results", results);

        log.info("Stopped all active strategies - {} legs closed successfully, {} failed", totalSuccess, totalFailures);

        return summary;
    }

    /**
     * DTO for instrument details
     */
    public record InstrumentDetail(String code, String name, int lotSize, double strikeInterval) {}
}
