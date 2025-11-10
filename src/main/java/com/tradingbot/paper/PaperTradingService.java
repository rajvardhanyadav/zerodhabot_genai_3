package com.tradingbot.paper;

import com.tradingbot.config.PaperTradingConfig;
import com.tradingbot.dto.OrderRequest;
import com.tradingbot.dto.OrderResponse;
import com.tradingbot.service.TradingService;
import com.zerodhatech.kiteconnect.kitehttp.exceptions.KiteException;
import com.zerodhatech.models.LTPQuote;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Paper Trading Service - Manages simulated trading operations
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class PaperTradingService {

    private final PaperTradingConfig config;
    private final TradingService tradingService;

    // In-memory storage for paper trading
    private final Map<String, PaperOrder> orders = new ConcurrentHashMap<>();
    private final Map<String, List<PaperOrder>> orderHistory = new ConcurrentHashMap<>();
    private final Map<String, PaperPosition> positions = new ConcurrentHashMap<>();
    private final Map<String, PaperAccount> accounts = new ConcurrentHashMap<>();

    private final AtomicLong orderIdGenerator = new AtomicLong(System.currentTimeMillis());

    /**
     * Place a paper order
     */
    public OrderResponse placeOrder(OrderRequest orderRequest, String userId) throws KiteException, IOException {
        log.info("[PAPER TRADING] Placing order for {}: {} {} @ {}",
                 orderRequest.getTradingSymbol(),
                 orderRequest.getTransactionType(),
                 orderRequest.getQuantity(),
                 orderRequest.getOrderType());

        // Get or create paper account
        PaperAccount account = getOrCreateAccount(userId);

        // Generate order ID
        String orderId = String.valueOf(orderIdGenerator.incrementAndGet());

        // Get instrument token and current price
        Long instrumentToken = getInstrumentToken(orderRequest.getTradingSymbol(), orderRequest.getExchange());
        Double currentPrice = getCurrentPrice(orderRequest.getTradingSymbol(), orderRequest.getExchange());

        // Create paper order
        PaperOrder order = PaperOrder.fromOrderRequest(
            orderId, userId,
            orderRequest.getTradingSymbol(),
            orderRequest.getExchange(),
            orderRequest.getTransactionType(),
            orderRequest.getQuantity(),
            orderRequest.getProduct(),
            orderRequest.getOrderType(),
            orderRequest.getPrice(),
            orderRequest.getTriggerPrice(),
            orderRequest.getValidity(),
            orderRequest.getDisclosedQuantity(),
            instrumentToken
        );

        // Validate order
        String validationError = validateOrder(order, account, currentPrice);
        if (validationError != null) {
            order.setStatus("REJECTED");
            order.setStatusMessage(validationError);
            orders.put(orderId, order);
            addToHistory(orderId, order);

            log.error("[PAPER TRADING] Order rejected: {}", validationError);
            return new OrderResponse(orderId, "FAILED", validationError);
        }

        // Calculate required margin
        Double requiredMargin = calculateRequiredMargin(order, currentPrice);

        // Block margin for buy orders
        if ("BUY".equals(order.getTransactionType())) {
            if (!account.hasSufficientBalance(requiredMargin)) {
                order.setStatus("REJECTED");
                order.setStatusMessage("Insufficient funds");
                orders.put(orderId, order);
                addToHistory(orderId, order);

                log.error("[PAPER TRADING] Insufficient funds. Required: {}, Available: {}",
                         requiredMargin, account.getAvailableBalance());
                return new OrderResponse(orderId, "FAILED", "Insufficient funds");
            }
            account.blockMargin(requiredMargin);
        }

        // Update order status
        order.setStatus("PENDING");
        order.setStatusMessage("Order validation pending");
        orders.put(orderId, order);
        addToHistory(orderId, order);

        // Execute order asynchronously (simulate exchange processing)
        executeOrderAsync(order, account, currentPrice);

        log.info("[PAPER TRADING] Order placed successfully: {}", orderId);
        return new OrderResponse(orderId, "SUCCESS", "Order placed successfully");
    }

    /**
     * Execute order with simulated delay
     */
    private void executeOrderAsync(PaperOrder order, PaperAccount account, Double currentPrice) {
        //new Thread(() -> {
            try {
                // Simulate exchange delay
                if (config.isEnableExecutionDelay()) {
                    Thread.sleep(config.getExecutionDelayMs());
                }

                // Simulate order rejection
                if (config.isEnableOrderRejection() &&
                    Math.random() < config.getRejectionProbability()) {
                    rejectOrder(order, "Random rejection for simulation");
                    return;
                }

                // Execute based on order type
                if ("MARKET".equals(order.getOrderType())) {
                    executeMarketOrder(order, account, currentPrice);
                } else if ("LIMIT".equals(order.getOrderType())) {
                    executeLimitOrder(order, account, currentPrice);
                } else if ("SL".equals(order.getOrderType()) || "SL-M".equals(order.getOrderType())) {
                    executeStopLossOrder(order, account, currentPrice);
                }

            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.error("[PAPER TRADING] Thread interrupted: {}", e.getMessage(), e);
                rejectOrder(order, "Execution interrupted: " + e.getMessage());
            } catch (Exception e) {
                log.error("[PAPER TRADING] Unexpected error executing order: {}", e.getMessage(), e);
                rejectOrder(order, "Execution error: " + e.getMessage());
            }
        //}).start();
    }

    /**
     * Execute market order
     */
    private void executeMarketOrder(PaperOrder order, PaperAccount account, Double currentPrice) {
        try {
            // Get fresh price
            Double executionPrice = getCurrentPrice(order.getTradingSymbol(), order.getExchange());

            // Apply slippage
            /*if (config.getSlippagePercentage() > 0) {
                double slippage = executionPrice * (config.getSlippagePercentage() / 100.0);
                executionPrice = "BUY".equals(order.getTransactionType()) ?
                               executionPrice + slippage : executionPrice - slippage;
            }*/

            // Update order
            order.setStatus("COMPLETE");
            order.setAveragePrice(executionPrice);
            order.setExecutionPrice(executionPrice);
            order.setFilledQuantity(order.getQuantity());
            order.setPendingQuantity(0);
            order.setExchangeTimestamp(LocalDateTime.now());
            order.setStatusMessage("Order completed");

            // Calculate charges
            calculateCharges(order, executionPrice);

            // Update account
            if (config.isApplyBrokerageCharges()) {
                account.addCharges(order.getBrokerageCharges(), order.getTaxes());
            }

            // Update position
            updatePosition(order, executionPrice, account);

            // Update order history
            addToHistory(order.getOrderId(), order);

            log.info("[PAPER TRADING] Market order executed: {} @ {}", order.getOrderId(), executionPrice);
        } catch (KiteException | IOException e) {
            log.error("[PAPER TRADING] Failed to execute market order - API error: {}", e.getMessage());
            rejectOrder(order, "Execution failed: " + e.getMessage());
        }
    }

    /**
     * Execute limit order
     */
    private void executeLimitOrder(PaperOrder order, PaperAccount account, Double currentPrice) {
        try {
            Double limitPrice = order.getPrice();

            // Check if limit price matches current price
            boolean canExecute = false;
            if ("BUY".equals(order.getTransactionType()) && currentPrice <= limitPrice) {
                canExecute = true;
            } else if ("SELL".equals(order.getTransactionType()) && currentPrice >= limitPrice) {
                canExecute = true;
            }

            if (canExecute) {
                order.setStatus("COMPLETE");
                order.setAveragePrice(limitPrice);
                order.setExecutionPrice(limitPrice);
                order.setFilledQuantity(order.getQuantity());
                order.setPendingQuantity(0);
                order.setExchangeTimestamp(LocalDateTime.now());
                order.setStatusMessage("Order completed");

                calculateCharges(order, limitPrice);

                if (config.isApplyBrokerageCharges()) {
                    account.addCharges(order.getBrokerageCharges(), order.getTaxes());
                }

                updatePosition(order, limitPrice, account);
                addToHistory(order.getOrderId(), order);

                log.info("[PAPER TRADING] Limit order executed: {} @ {}", order.getOrderId(), limitPrice);
            } else {
                order.setStatus("OPEN");
                order.setStatusMessage("Order open - waiting for limit price");
                addToHistory(order.getOrderId(), order);

                log.info("[PAPER TRADING] Limit order open: {} waiting for price {}",
                         order.getOrderId(), limitPrice);
            }
        } catch (Exception e) {
            log.error("[PAPER TRADING] Failed to execute limit order: {}", e.getMessage());
            rejectOrder(order, "Execution failed: " + e.getMessage());
        }
    }

    /**
     * Execute stop loss order
     */
    private void executeStopLossOrder(PaperOrder order, PaperAccount account, Double currentPrice) {
        try {
            Double triggerPrice = order.getTriggerPrice();

            // Check if trigger price is hit
            boolean triggered = false;
            if ("BUY".equals(order.getTransactionType()) && currentPrice >= triggerPrice) {
                triggered = true;
            } else if ("SELL".equals(order.getTransactionType()) && currentPrice <= triggerPrice) {
                triggered = true;
            }

            if (triggered) {
                if ("SL-M".equals(order.getOrderType())) {
                    // Stop loss market - execute at current price
                    executeMarketOrder(order, account, currentPrice);
                } else {
                    // Stop loss limit - execute at limit price
                    executeLimitOrder(order, account, currentPrice);
                }
            } else {
                order.setStatus("OPEN");
                order.setStatusMessage("Trigger pending");
                addToHistory(order.getOrderId(), order);

                log.info("[PAPER TRADING] SL order open: {} waiting for trigger {}",
                         order.getOrderId(), triggerPrice);
            }
        } catch (Exception e) {
            log.error("[PAPER TRADING] Failed to execute stop loss order: {}", e.getMessage());
            rejectOrder(order, "Execution failed: " + e.getMessage());
        }
    }

    /**
     * Reject order
     */
    private void rejectOrder(PaperOrder order, String reason) {
        order.setStatus("REJECTED");
        order.setStatusMessage(reason);
        order.setExchangeTimestamp(LocalDateTime.now());
        addToHistory(order.getOrderId(), order);

        log.warn("[PAPER TRADING] Order rejected: {} - {}", order.getOrderId(), reason);
    }

    /**
     * Calculate required margin
     */
    private Double calculateRequiredMargin(PaperOrder order, Double price) {
        double orderValue = price * order.getQuantity();

        // Different margin requirements based on product type
        return switch (order.getProduct()) {
            case "CNC" -> orderValue; // Full amount for delivery
            case "MIS" -> orderValue * 0.20; // 20% for intraday
            case "NRML" -> orderValue * 0.40; // 40% for normal
            default -> orderValue;
        };
    }

    /**
     * Calculate brokerage and taxes
     */
    private void calculateCharges(PaperOrder order, Double executionPrice) {
        double orderValue = executionPrice * order.getQuantity();

        // Brokerage
        double brokerage = config.getBrokeragePerOrder();

        // STT
        double stt = 0.0;
        if ("SELL".equals(order.getTransactionType())) {
            stt = orderValue * (config.getSttPercentage() / 100.0);
        }

        // Transaction charges
        double transactionCharges = orderValue * (config.getTransactionCharges() / 100.0);

        // GST on brokerage
        double gst = brokerage * (config.getGstPercentage() / 100.0);

        // SEBI charges
        double sebi = orderValue * (config.getSebiCharges() / 100.0);

        // Stamp duty
        double stampDuty = orderValue * (config.getStampDuty() / 100.0);

        double totalTaxes = stt + transactionCharges + gst + sebi + stampDuty;
        double totalCharges = brokerage + totalTaxes;

        order.setBrokerageCharges(brokerage);
        order.setTaxes(totalTaxes);
        order.setTotalCharges(totalCharges);
    }

    /**
     * Update position after order execution
     */
    private void updatePosition(PaperOrder order, Double executionPrice, PaperAccount account) {
        String positionKey = order.getTradingSymbol() + "_" + order.getProduct();

        PaperPosition position = positions.getOrDefault(positionKey, new PaperPosition());
        position.setTradingSymbol(order.getTradingSymbol());
        position.setExchange(order.getExchange());
        position.setInstrumentToken(order.getInstrumentToken());
        position.setProduct(order.getProduct());
        position.setMultiplier(1);
        position.setLastPrice(executionPrice);

        if ("BUY".equals(order.getTransactionType())) {
            // Update buy details
            int totalBuyQty = (position.getBuyQuantity() != null ? position.getBuyQuantity() : 0) + order.getQuantity();
            double totalBuyValue = (position.getBuyValue() != null ? position.getBuyValue() : 0.0) +
                                  (executionPrice * order.getQuantity());

            position.setBuyQuantity(totalBuyQty);
            position.setBuyValue(totalBuyValue);
            position.setBuyPrice(totalBuyValue / totalBuyQty);

            // Update day buy
            position.setDayBuyQuantity(totalBuyQty);
            position.setDayBuyValue(totalBuyValue);
            position.setDayBuyPrice(position.getBuyPrice());

            // Update net quantity
            int netQty = totalBuyQty - (position.getSellQuantity() != null ? position.getSellQuantity() : 0);
            position.setQuantity(netQty);

            if (netQty > 0) {
                position.setAveragePrice(totalBuyValue / totalBuyQty);
            }

        } else {
            // Update sell details
            int totalSellQty = (position.getSellQuantity() != null ? position.getSellQuantity() : 0) + order.getQuantity();
            double totalSellValue = (position.getSellValue() != null ? position.getSellValue() : 0.0) +
                                   (executionPrice * order.getQuantity());

            position.setSellQuantity(totalSellQty);
            position.setSellValue(totalSellValue);
            position.setSellPrice(totalSellValue / totalSellQty);

            // Update day sell
            position.setDaySellQuantity(totalSellQty);
            position.setDaySellValue(totalSellValue);
            position.setDaySellPrice(position.getSellPrice());

            // Update net quantity
            int netQty = (position.getBuyQuantity() != null ? position.getBuyQuantity() : 0) - totalSellQty;
            position.setQuantity(netQty);

            // Calculate realised P&L
            if (position.getBuyQuantity() != null && position.getBuyQuantity() > 0) {
                double realisedPnL = (executionPrice - position.getBuyPrice()) * order.getQuantity();
                position.setRealised((position.getRealised() != null ? position.getRealised() : 0.0) + realisedPnL);

                // Release margin
                double marginToRelease = calculateRequiredMargin(order, position.getBuyPrice());
                account.releaseMargin(marginToRelease);

                // Update account P&L
                account.updatePnL(realisedPnL, 0.0);

                // Update trade statistics
                account.setTotalTrades(account.getTotalTrades() + 1);
                if (realisedPnL > 0) {
                    account.setWinningTrades(account.getWinningTrades() + 1);
                } else if (realisedPnL < 0) {
                    account.setLosingTrades(account.getLosingTrades() + 1);
                }
            }
        }

        position.setLastUpdated(LocalDateTime.now());
        positions.put(positionKey, position);

        log.debug("[PAPER TRADING] Position updated: {} - Qty: {}, Avg: {}",
                 positionKey, position.getQuantity(), position.getAveragePrice());
    }

    /**
     * Cancel order
     */
    public OrderResponse cancelOrder(String orderId, String userId) {
        PaperOrder order = orders.get(orderId);

        if (order == null) {
            return new OrderResponse(orderId, "FAILED", "Order not found");
        }

        if (!order.getPlacedBy().equals(userId)) {
            return new OrderResponse(orderId, "FAILED", "Unauthorized");
        }

        if ("COMPLETE".equals(order.getStatus()) || "CANCELLED".equals(order.getStatus())) {
            return new OrderResponse(orderId, "FAILED", "Order cannot be cancelled");
        }

        // Release margin if buy order
        if ("BUY".equals(order.getTransactionType()) && order.getPendingQuantity() > 0) {
            PaperAccount account = accounts.get(userId);
            if (account != null) {
                try {
                    Double currentPrice = getCurrentPrice(order.getTradingSymbol(), order.getExchange());
                    Double marginToRelease = calculateRequiredMargin(order, currentPrice);
                    account.releaseMargin(marginToRelease);
                } catch (KiteException | IOException e) {
                    log.error("[PAPER TRADING] Error releasing margin - API error: {}", e.getMessage());
                }
            }
        }

        order.setStatus("CANCELLED");
        order.setCancelledQuantity(order.getPendingQuantity());
        order.setPendingQuantity(0);
        order.setStatusMessage("Order cancelled by user");
        order.setExchangeTimestamp(LocalDateTime.now());

        addToHistory(orderId, order);

        log.info("[PAPER TRADING] Order cancelled: {}", orderId);
        return new OrderResponse(orderId, "SUCCESS", "Order cancelled successfully");
    }

    /**
     * Modify order
     */
    public OrderResponse modifyOrder(String orderId, OrderRequest orderRequest, String userId) {
        PaperOrder order = orders.get(orderId);

        if (order == null) {
            return new OrderResponse(orderId, "FAILED", "Order not found");
        }

        if (!order.getPlacedBy().equals(userId)) {
            return new OrderResponse(orderId, "FAILED", "Unauthorized");
        }

        if (!"OPEN".equals(order.getStatus()) && !"PENDING".equals(order.getStatus())) {
            return new OrderResponse(orderId, "FAILED", "Order cannot be modified");
        }

        // Update order details
        if (orderRequest.getQuantity() != null) {
            order.setQuantity(orderRequest.getQuantity());
            order.setPendingQuantity(orderRequest.getQuantity());
        }
        if (orderRequest.getPrice() != null) {
            order.setPrice(orderRequest.getPrice());
        }
        if (orderRequest.getTriggerPrice() != null) {
            order.setTriggerPrice(orderRequest.getTriggerPrice());
        }
        if (orderRequest.getOrderType() != null) {
            order.setOrderType(orderRequest.getOrderType());
        }

        order.setStatusMessage("Order modified");
        addToHistory(orderId, order);

        log.info("[PAPER TRADING] Order modified: {}", orderId);
        return new OrderResponse(orderId, "SUCCESS", "Order modified successfully");
    }

    /**
     * Get all orders
     */
    public List<PaperOrder> getAllOrders(String userId) {
        return orders.values().stream()
                .filter(o -> o.getPlacedBy().equals(userId))
                .toList();
    }

    /**
     * Get order history
     */
    public List<PaperOrder> getOrderHistory(String orderId) {
        return orderHistory.getOrDefault(orderId, new ArrayList<>());
    }

    /**
     * Get positions
     */
    public List<PaperPosition> getPositions(String userId) {
        return new ArrayList<>(positions.values());
    }

    /**
     * Get account
     */
    public PaperAccount getAccount(String userId) {
        return getOrCreateAccount(userId);
    }

    /**
     * Reset paper trading account
     */
    public void resetAccount(String userId) {
        orders.entrySet().removeIf(e -> e.getValue().getPlacedBy().equals(userId));
        orderHistory.clear();
        positions.clear();
        accounts.remove(userId);

        log.info("[PAPER TRADING] Account reset for user: {}", userId);
    }

    // Helper methods

    private PaperAccount getOrCreateAccount(String userId) {
        return accounts.computeIfAbsent(userId,
            k -> PaperAccount.createNew(userId, config.getInitialBalance()));
    }

    private void addToHistory(String orderId, PaperOrder order) {
        PaperOrder historyCopy = copyOrder(order);
        orderHistory.computeIfAbsent(orderId, k -> new ArrayList<>()).add(historyCopy);
    }

    private PaperOrder copyOrder(PaperOrder order) {
        PaperOrder copy = new PaperOrder();
        copy.setOrderId(order.getOrderId());
        copy.setExchangeOrderId(order.getExchangeOrderId());
        copy.setPlacedBy(order.getPlacedBy());
        copy.setVariety(order.getVariety());
        copy.setStatus(order.getStatus());
        copy.setTradingSymbol(order.getTradingSymbol());
        copy.setExchange(order.getExchange());
        copy.setInstrumentToken(order.getInstrumentToken());
        copy.setTransactionType(order.getTransactionType());
        copy.setOrderType(order.getOrderType());
        copy.setProduct(order.getProduct());
        copy.setQuantity(order.getQuantity());
        copy.setPrice(order.getPrice());
        copy.setTriggerPrice(order.getTriggerPrice());
        copy.setAveragePrice(order.getAveragePrice());
        copy.setFilledQuantity(order.getFilledQuantity());
        copy.setPendingQuantity(order.getPendingQuantity());
        copy.setCancelledQuantity(order.getCancelledQuantity());
        copy.setDisclosedQuantity(order.getDisclosedQuantity());
        copy.setValidity(order.getValidity());
        copy.setOrderTimestamp(order.getOrderTimestamp());
        copy.setExchangeTimestamp(order.getExchangeTimestamp());
        copy.setStatusMessage(order.getStatusMessage());
        copy.setExecutionPrice(order.getExecutionPrice());
        copy.setBrokerageCharges(order.getBrokerageCharges());
        copy.setTaxes(order.getTaxes());
        copy.setTotalCharges(order.getTotalCharges());
        return copy;
    }

    private String validateOrder(PaperOrder order, PaperAccount account, Double currentPrice) {
        // Basic order validation
        if (order.getQuantity() <= 0) {
            return "Invalid quantity";
        }

        if ("LIMIT".equals(order.getOrderType()) && (order.getPrice() == null || order.getPrice() <= 0)) {
            return "Invalid limit price";
        }

        if (("SL".equals(order.getOrderType()) || "SL-M".equals(order.getOrderType())) &&
            (order.getTriggerPrice() == null || order.getTriggerPrice() <= 0)) {
            return "Invalid trigger price";
        }

        // Note: account and currentPrice parameters reserved for future validations
        // such as margin checks and price range validations

        return null;
    }

    private Double getCurrentPrice(String tradingSymbol, String exchange) throws KiteException, IOException {
        String instrument = exchange + ":" + tradingSymbol;
        Map<String, LTPQuote> ltp = tradingService.getLTP(new String[]{instrument});
        return ltp.get(instrument).lastPrice;
    }

    private Long getInstrumentToken(String tradingSymbol, String exchange) {
        // This is a simplified implementation
        // In real scenario, you would fetch from instruments list
        return (long) (tradingSymbol + exchange).hashCode();
    }
}

