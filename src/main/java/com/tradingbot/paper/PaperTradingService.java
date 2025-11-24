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

import static com.tradingbot.service.TradingConstants.*;

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
    // Per-user positions: userId -> (positionKey -> PaperPosition)
    private final Map<String, Map<String, PaperPosition>> positionsByUser = new ConcurrentHashMap<>();
    private final Map<String, PaperAccount> accounts = new ConcurrentHashMap<>();

    private final AtomicLong orderIdGenerator = new AtomicLong(System.currentTimeMillis());

    /**
     * Place a paper order
     */
    public OrderResponse placeOrder(OrderRequest orderRequest, String userId) {
        log.info("[PAPER TRADING] Placing order for {}: {} {} @ {}",
                 orderRequest.getTradingSymbol(),
                 orderRequest.getTransactionType(),
                 orderRequest.getQuantity(),
                 orderRequest.getOrderType());

        // Get or create paper account
        PaperAccount account = getOrCreateAccount(userId);

        // Generate order ID
        String orderId = String.valueOf(orderIdGenerator.incrementAndGet());

        // Get instrument token first
        Long instrumentToken = getInstrumentToken(orderRequest.getTradingSymbol(), orderRequest.getExchange());

        // Create paper order upfront so we can reject gracefully if anything fails later
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
        String validationError = validateOrder(order);
        if (validationError != null) {
            return rejectAndReturnOrder(order, orderId, validationError);
        }

        // Fetch current price, handle API errors internally
        Double currentPrice;
        try {
            currentPrice = getCurrentPrice(orderRequest.getTradingSymbol(), orderRequest.getExchange());
        } catch (KiteException | IOException e) {
            String msg = "Failed to fetch LTP: " + e.getMessage();
            log.error("[PAPER TRADING] {}", msg, e);
            return rejectAndReturnOrder(order, orderId, msg);
        }

        // Calculate required margin
        Double requiredMargin = calculateRequiredMargin(order, currentPrice);

        // Block margin for buy orders
        if (TRANSACTION_BUY.equals(order.getTransactionType())) {
            if (!account.hasSufficientBalance(requiredMargin)) {
                String errorMsg = String.format(ERR_INSUFFICIENT_FUNDS,
                                                requiredMargin, account.getAvailableBalance());
                log.error("[PAPER TRADING] {}", errorMsg);
                return rejectAndReturnOrder(order, orderId, errorMsg);
            }
            account.blockMargin(requiredMargin);
        }

        // Update order status
        order.setStatus(STATUS_PENDING);
        order.setStatusMessage(MSG_ORDER_VALIDATION_PENDING);
        orders.put(orderId, order);
        addToHistory(orderId, order);

        // Execute order synchronously (immediate execution for paper trading)
        executeOrder(order, account, currentPrice);

        log.info("[PAPER TRADING] Order placed successfully: {}", orderId);
        return new OrderResponse(orderId, STATUS_SUCCESS, MSG_ORDER_PLACED_SUCCESS);
    }

    /**
     * Reject order and return response
     */
    private OrderResponse rejectAndReturnOrder(PaperOrder order, String orderId, String reason) {
        order.setStatus(STATUS_REJECTED);
        order.setStatusMessage(reason);
        orders.put(orderId, order);
        addToHistory(orderId, order);
        log.error("[PAPER TRADING] Order rejected: {}", reason);
        return new OrderResponse(orderId, STATUS_FAILED, reason);
    }

    /**
     * Execute order immediately
     */
    private void executeOrder(PaperOrder order, PaperAccount account, Double currentPrice) {
        try {
            // Simulate execution delay if enabled
            if (config.isEnableExecutionDelay()) {
                Thread.sleep(config.getExecutionDelayMs());
            }

            // Simulate order rejection
            if (config.isEnableOrderRejection() && Math.random() < config.getRejectionProbability()) {
                rejectOrder(order, "Random rejection for simulation");
                return;
            }

            // Execute based on order type
            switch (order.getOrderType()) {
                case ORDER_TYPE_MARKET -> executeMarketOrder(order, account, currentPrice);
                case ORDER_TYPE_LIMIT -> executeLimitOrder(order, account, currentPrice);
                case ORDER_TYPE_SL, ORDER_TYPE_SL_M -> executeStopLossOrder(order, account, currentPrice);
                default -> rejectOrder(order, ERR_UNSUPPORTED_ORDER_TYPE + order.getOrderType());
            }

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("[PAPER TRADING] Thread interrupted: {}", e.getMessage(), e);
            rejectOrder(order, "Execution interrupted: " + e.getMessage());
        } catch (Exception e) {
            log.error("[PAPER TRADING] Unexpected error executing order: {}", e.getMessage(), e);
            rejectOrder(order, "Execution error: " + e.getMessage());
        }
    }

    /**
     * Execute market order
     */
    private void executeMarketOrder(PaperOrder order, PaperAccount account, Double currentPrice) {
        try {
            // Use provided currentPrice when available to avoid extra API call
            Double executionPrice = (currentPrice != null && currentPrice > 0) ? currentPrice : getCurrentPrice(order.getTradingSymbol(), order.getExchange());

            // Complete order
            completeOrder(order, executionPrice, account);

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
        Double limitPrice = order.getPrice();

        // Check if limit price matches current price
        boolean canExecute = (TRANSACTION_BUY.equals(order.getTransactionType()) && currentPrice <= limitPrice) ||
                            (TRANSACTION_SELL.equals(order.getTransactionType()) && currentPrice >= limitPrice);

        if (canExecute) {
            completeOrder(order, limitPrice, account);
            log.info("[PAPER TRADING] Limit order executed: {} @ {}", order.getOrderId(), limitPrice);
        } else {
            order.setStatus(STATUS_OPEN);
            order.setStatusMessage(MSG_ORDER_OPEN_WAITING_FOR_LIMIT);
            addToHistory(order.getOrderId(), order);
            log.info("[PAPER TRADING] Limit order open: {} waiting for price {}", order.getOrderId(), limitPrice);
        }
    }

    /**
     * Execute stop loss order
     */
    private void executeStopLossOrder(PaperOrder order, PaperAccount account, Double currentPrice) {
        Double triggerPrice = order.getTriggerPrice();

        // Check if trigger price is hit
        boolean triggered = (TRANSACTION_BUY.equals(order.getTransactionType()) && currentPrice >= triggerPrice) ||
                           (TRANSACTION_SELL.equals(order.getTransactionType()) && currentPrice <= triggerPrice);

        if (triggered) {
            if (ORDER_TYPE_SL_M.equals(order.getOrderType())) {
                // Stop loss market - execute at current price
                executeMarketOrder(order, account, currentPrice);
            } else {
                // Stop loss limit - execute at limit price
                executeLimitOrder(order, account, currentPrice);
            }
        } else {
            order.setStatus(STATUS_OPEN);
            order.setStatusMessage(MSG_TRIGGER_PENDING);
            addToHistory(order.getOrderId(), order);
            log.info("[PAPER TRADING] SL order open: {} waiting for trigger {}", order.getOrderId(), triggerPrice);
        }
    }

    /**
     * Complete an order with execution price
     */
    private void completeOrder(PaperOrder order, Double executionPrice, PaperAccount account) {
        order.setStatus(STATUS_COMPLETE);
        order.setAveragePrice(executionPrice);
        order.setExecutionPrice(executionPrice);
        order.setFilledQuantity(order.getQuantity());
        order.setPendingQuantity(0);
        order.setExchangeTimestamp(LocalDateTime.now());
        order.setStatusMessage(MSG_ORDER_COMPLETED);

        // Calculate charges
        calculateCharges(order, executionPrice);

        // Apply charges to account
        if (config.isApplyBrokerageCharges()) {
            account.addCharges(order.getBrokerageCharges(), order.getTaxes());
        }

        // Update position
        updatePosition(order, executionPrice, account);

        // Update order history
        addToHistory(order.getOrderId(), order);
    }

    /**
     * Reject order
     */
    private void rejectOrder(PaperOrder order, String reason) {
        order.setStatus(STATUS_REJECTED);
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
            case PRODUCT_CNC -> orderValue; // Full amount for delivery
            case PRODUCT_MIS -> orderValue * 0.20; // 20% for intraday
            case PRODUCT_NRML -> orderValue * 0.40; // 40% for normal
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

        // STT (only on sell)
        double stt = TRANSACTION_SELL.equals(order.getTransactionType()) ?
                     orderValue * (config.getSttPercentage() / 100.0) : 0.0;

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
        String userId = order.getPlacedBy();
        Map<String, PaperPosition> userPositions = positionsByUser.computeIfAbsent(userId, k -> new ConcurrentHashMap<>());

        String positionKey = order.getTradingSymbol() + "_" + order.getProduct();

        PaperPosition position = userPositions.computeIfAbsent(positionKey, k -> PaperPosition.builder()
                .tradingSymbol(order.getTradingSymbol())
                .exchange(order.getExchange())
                .instrumentToken(order.getInstrumentToken())
                .product(order.getProduct())
                .multiplier(1)
                .build());

        position.setLastPrice(executionPrice);
        position.setLastUpdated(LocalDateTime.now());

        if (TRANSACTION_BUY.equals(order.getTransactionType())) {
            // BUY could either open/extend a long or close an existing short
            updateBuyPosition(position, order, executionPrice, account);
        } else {
            // SELL could either open/extend a short or close an existing long
            updateSellPosition(position, order, executionPrice, account);
        }

        userPositions.put(positionKey, position);
        log.debug("[PAPER TRADING] Position updated [user={}]: {} - Qty: {}, Avg: {}",
                 userId, positionKey, position.getQuantity(), position.getAveragePrice());
    }

    /**
     * Update position for buy orders (including closing shorts)
     */
    private void updateBuyPosition(PaperPosition position, PaperOrder order, Double executionPrice, PaperAccount account) {
        int buyQty = order.getQuantity();
        int existingSellQty = position.getSellQuantity() != null ? position.getSellQuantity() : 0;

        // First, realise P&L for any portion of this BUY that closes an existing short position
        int offsetQty = Math.min(existingSellQty, buyQty);
        if (offsetQty > 0 && existingSellQty > 0) {
            double avgSellPrice = position.getSellPrice() != null ? position.getSellPrice() : 0.0;
            double realisedPnL = (avgSellPrice - executionPrice) * offsetQty;

            position.setRealised(position.getRealised() + realisedPnL);
            position.setPnl(position.getPnl() + realisedPnL);

            if (account != null) {
                // Update account P&L and trade statistics
                account.updatePnL(realisedPnL, 0.0);
                account.recordTrade(realisedPnL);

                // Release margin for the closed short portion (use avg sell price as reference)
                double marginToRelease = calculateRequiredMargin(order, avgSellPrice);
                account.releaseMargin(marginToRelease);
            }
        }

        // Then update aggregate BUY-side quantities and values as before
        int totalBuyQty = (position.getBuyQuantity() != null ? position.getBuyQuantity() : 0) + buyQty;
        double totalBuyValue = (position.getBuyValue() != null ? position.getBuyValue() : 0.0)
                + (executionPrice * buyQty);

        log.info("[PAPER TRADING] Updating BUY position: PrevQty={}, NewQty={}, PrevValue={}, NewValue={}",
                 position.getBuyQuantity(), totalBuyQty, position.getBuyValue(), totalBuyValue);

        position.setBuyQuantity(totalBuyQty);
        position.setBuyValue(totalBuyValue);
        position.setBuyPrice(totalBuyValue / totalBuyQty);
        position.setDayBuyQuantity(totalBuyQty);
        position.setDayBuyValue(totalBuyValue);
        position.setDayBuyPrice(position.getBuyPrice());

        // Update net quantity: positive means net long, negative means net short
        int netQty = totalBuyQty - existingSellQty;
        position.setQuantity(netQty);

        if (netQty > 0) {
            // Net long - average price based on buys
            position.setAveragePrice(totalBuyValue / totalBuyQty);
        }
    }

    /**
     * Update position for sell orders (including closing longs)
     */
    private void updateSellPosition(PaperPosition position, PaperOrder order, Double executionPrice, PaperAccount account) {
        int totalSellQty = position.getSellQuantity() + order.getQuantity();
        double totalSellValue = position.getSellValue() + (executionPrice * order.getQuantity());

        log.info("[PAPER TRADING] Updating SELL position: PrevQty={}, NewQty={}, PrevValue={}, NewValue={}",
                 position.getSellQuantity(), totalSellQty, position.getSellValue(), totalSellValue);

        position.setSellQuantity(totalSellQty);
        position.setSellValue(totalSellValue);
        position.setSellPrice(totalSellValue / totalSellQty);
        position.setDaySellQuantity(totalSellQty);
        position.setDaySellValue(totalSellValue);
        position.setDaySellPrice(position.getSellPrice());

        // Update net quantity
        int netQty = position.getBuyQuantity() - totalSellQty;
        position.setQuantity(netQty);

        // Calculate realised P&L when selling against existing longs
        if (position.getBuyQuantity() > 0) {
            double realisedPnL = (executionPrice - position.getBuyPrice()) * order.getQuantity();
            position.setRealised(position.getRealised() + realisedPnL);
            position.setPnl(position.getPnl() + realisedPnL);

            // Release margin for the closed long portion
            double marginToRelease = calculateRequiredMargin(order, position.getBuyPrice());
            account.releaseMargin(marginToRelease);

            // Update account P&L and trade statistics
            account.updatePnL(realisedPnL, 0.0);
            account.recordTrade(realisedPnL);
        }
    }

    /**
     * Cancel order
     */
    public OrderResponse cancelOrder(String orderId, String userId) {
        PaperOrder order = orders.get(orderId);

        if (order == null) {
            return new OrderResponse(orderId, STATUS_FAILED, ERR_ORDER_NOT_FOUND);
        }

        if (!order.getPlacedBy().equals(userId)) {
            return new OrderResponse(orderId, STATUS_FAILED, ERR_UNAUTHORIZED);
        }

        if (STATUS_COMPLETE.equals(order.getStatus()) || STATUS_CANCELLED.equals(order.getStatus())) {
            return new OrderResponse(orderId, STATUS_FAILED, ERR_ORDER_CANNOT_BE_CANCELLED);
        }

        // Release margin if buy order
        releasePendingMargin(order, userId);

        order.setStatus(STATUS_CANCELLED);
        order.setCancelledQuantity(order.getPendingQuantity());
        order.setPendingQuantity(0);
        order.setStatusMessage(MSG_ORDER_CANCELLED_BY_USER);
        order.setExchangeTimestamp(LocalDateTime.now());

        addToHistory(orderId, order);

        log.info("[PAPER TRADING] Order cancelled: {}", orderId);
        return new OrderResponse(orderId, STATUS_SUCCESS, MSG_ORDER_CANCELLED_SUCCESS);
    }

    /**
     * Release margin for pending orders
     */
    private void releasePendingMargin(PaperOrder order, String userId) {
        if (TRANSACTION_BUY.equals(order.getTransactionType()) && order.getPendingQuantity() > 0) {
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
    }

    /**
     * Modify order
     */
    public OrderResponse modifyOrder(String orderId, OrderRequest orderRequest, String userId) {
        PaperOrder order = orders.get(orderId);

        if (order == null) {
            return new OrderResponse(orderId, STATUS_FAILED, ERR_ORDER_NOT_FOUND);
        }

        if (!order.getPlacedBy().equals(userId)) {
            return new OrderResponse(orderId, STATUS_FAILED, ERR_UNAUTHORIZED);
        }

        if (!STATUS_OPEN.equals(order.getStatus()) && !STATUS_PENDING.equals(order.getStatus())) {
            return new OrderResponse(orderId, STATUS_FAILED, ERR_ORDER_CANNOT_BE_MODIFIED);
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

        order.setStatusMessage(MSG_ORDER_MODIFIED_SUCCESS);
        addToHistory(orderId, order);

        log.info("[PAPER TRADING] Order modified: {}", orderId);
        return new OrderResponse(orderId, STATUS_SUCCESS, MSG_ORDER_MODIFIED_SUCCESS);
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
    @SuppressWarnings("unused")
    public List<PaperPosition> getPositions(String userId) {
        return new ArrayList<>(positionsByUser.getOrDefault(userId, Collections.emptyMap()).values());
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
        // Remove user's orders and related history only for that user
        orders.entrySet().removeIf(e -> e.getValue().getPlacedBy().equals(userId));
        orderHistory.entrySet().removeIf(e -> {
            List<PaperOrder> hist = e.getValue();
            return !hist.isEmpty() && userId.equals(hist.get(0).getPlacedBy());
        });

        // Clear only this user's positions and account
        positionsByUser.remove(userId);
        accounts.remove(userId);

        log.info("[PAPER TRADING] Account reset for user: {}", userId);
    }

    // Helper methods

    private PaperAccount getOrCreateAccount(String userId) {
        return accounts.computeIfAbsent(userId,
            k -> PaperAccount.createNew(userId, config.getInitialBalance()));
    }

    public Optional<PaperOrder> getOrderById(String orderId, String userId) {
        PaperOrder order = orders.get(orderId);
        if (order == null) {
            return Optional.empty();
        }
        if (userId != null && !userId.equals(order.getPlacedBy())) {
            return Optional.empty();
        }
        return Optional.of(order.copy());
    }

    private void addToHistory(String orderId, PaperOrder order) {
        orderHistory.computeIfAbsent(orderId, k -> new ArrayList<>()).add(order.copy());
    }

    private String validateOrder(PaperOrder order) {
        // Basic order validation
        if (order.getQuantity() <= 0) {
            return VALIDATION_INVALID_QUANTITY;
        }

        if (ORDER_TYPE_LIMIT.equals(order.getOrderType()) && (order.getPrice() == null || order.getPrice() <= 0)) {
            return VALIDATION_INVALID_LIMIT_PRICE;
        }

        if ((ORDER_TYPE_SL.equals(order.getOrderType()) || ORDER_TYPE_SL_M.equals(order.getOrderType())) &&
            (order.getTriggerPrice() == null || order.getTriggerPrice() <= 0)) {
            return VALIDATION_INVALID_TRIGGER_PRICE;
        }

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
