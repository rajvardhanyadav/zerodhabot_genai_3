package com.tradingbot.paper;

import com.tradingbot.config.PaperTradingConfig;
import com.tradingbot.config.PersistenceConfig;
import com.tradingbot.dto.BasketOrderRequest;
import com.tradingbot.dto.BasketOrderResponse;
import com.tradingbot.dto.OrderRequest;
import com.tradingbot.dto.OrderResponse;
import com.tradingbot.dto.OrderChargesResponse;
import com.tradingbot.entity.TradeEntity;
import com.tradingbot.entity.OrderTimingEntity;
import com.tradingbot.paper.entity.OrderCharges;
import com.tradingbot.service.TradingService;
import com.tradingbot.service.persistence.TradePersistenceService;
import com.zerodhatech.kiteconnect.kitehttp.exceptions.KiteException;
import com.zerodhatech.models.LTPQuote;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import static com.tradingbot.paper.ZerodhaChargeCalculator.OrderType.OPTIONS;
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
    private final ZerodhaChargeCalculator chargeCalculator;
    private final PersistenceConfig persistenceConfig;

    // Lazy injection to break circular dependency
    @Autowired
    @Lazy
    private TradePersistenceService persistenceService;

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
     * Place basket order - multiple orders at once for paper trading
     * Places all orders in the basket and returns consolidated results
     */
    public BasketOrderResponse placeBasketOrder(BasketOrderRequest basketRequest, String userId) {
        log.info("[PAPER TRADING] Placing basket order with {} orders for user: {}",
                basketRequest.getOrders() != null ? basketRequest.getOrders().size() : 0, userId);

        List<BasketOrderResponse.BasketOrderResult> results = new ArrayList<>();
        int successCount = 0;
        int failureCount = 0;

        if (basketRequest.getOrders() == null || basketRequest.getOrders().isEmpty()) {
            log.error("[PAPER TRADING] Basket order request has no orders");
            return BasketOrderResponse.builder()
                    .status(STATUS_FAILED)
                    .message("No orders in basket request")
                    .totalOrders(0)
                    .successCount(0)
                    .failureCount(0)
                    .orderResults(results)
                    .build();
        }

        // Get or create paper account
        PaperAccount account = getOrCreateAccount(userId);

        // Fetch prices for all instruments first to minimize API calls
        Map<String, Double> priceCache = new HashMap<>();
        for (BasketOrderRequest.BasketOrderItem item : basketRequest.getOrders()) {
            try {
                String key = item.getTradingSymbol() + ":" + item.getExchange();
                if (!priceCache.containsKey(key)) {
                    Double price = getCurrentPrice(item.getTradingSymbol(), item.getExchange());
                    priceCache.put(key, price);
                }
            } catch (KiteException | IOException e) {
                log.warn("[PAPER TRADING] Failed to fetch price for {}: {}", item.getTradingSymbol(), e.getMessage());
            }
        }

        // Place each order in the basket
        for (BasketOrderRequest.BasketOrderItem item : basketRequest.getOrders()) {
            try {
                OrderRequest orderRequest = item.toOrderRequest();
                String key = item.getTradingSymbol() + ":" + item.getExchange();
                Double cachedPrice = priceCache.get(key);

                // Place the individual order
                OrderResponse response = placeOrderWithPrice(orderRequest, userId, cachedPrice);

                if (STATUS_SUCCESS.equals(response.getStatus())) {
                    // Get execution price from order
                    Double executionPrice = getOrderExecutionPrice(response.getOrderId());

                    log.info("[PAPER TRADING] Basket order item placed successfully: {} - {} {}",
                            response.getOrderId(), item.getTransactionType(), item.getTradingSymbol());

                    results.add(BasketOrderResponse.BasketOrderResult.builder()
                            .orderId(response.getOrderId())
                            .tradingSymbol(item.getTradingSymbol())
                            .legType(item.getLegType())
                            .status(STATUS_SUCCESS)
                            .message(MSG_ORDER_PLACED_SUCCESS)
                            .executionPrice(executionPrice)
                            .instrumentToken(item.getInstrumentToken())
                            .build());
                    successCount++;
                } else {
                    log.error("[PAPER TRADING] Basket order item failed for {}: {}",
                            item.getTradingSymbol(), response.getMessage());
                    results.add(BasketOrderResponse.BasketOrderResult.builder()
                            .tradingSymbol(item.getTradingSymbol())
                            .legType(item.getLegType())
                            .status(STATUS_FAILED)
                            .message(response.getMessage())
                            .instrumentToken(item.getInstrumentToken())
                            .build());
                    failureCount++;
                }

            } catch (Exception e) {
                log.error("[PAPER TRADING] Error placing basket order item for {}: {}",
                        item.getTradingSymbol(), e.getMessage());
                results.add(BasketOrderResponse.BasketOrderResult.builder()
                        .tradingSymbol(item.getTradingSymbol())
                        .legType(item.getLegType())
                        .status(STATUS_FAILED)
                        .message("Error: " + e.getMessage())
                        .instrumentToken(item.getInstrumentToken())
                        .build());
                failureCount++;
            }
        }

        String overallStatus = determineOverallStatus(successCount, failureCount, basketRequest.getOrders().size());
        String message = String.format("[PAPER] Basket order completed: %d/%d orders successful",
                successCount, basketRequest.getOrders().size());

        log.info("[PAPER TRADING] Basket order completed - Status: {}, Success: {}, Failed: {}",
                overallStatus, successCount, failureCount);

        return BasketOrderResponse.builder()
                .status(overallStatus)
                .message(message)
                .orderResults(results)
                .successCount(successCount)
                .failureCount(failureCount)
                .totalOrders(basketRequest.getOrders().size())
                .build();
    }

    /**
     * Place order with pre-fetched price to avoid additional API calls
     */
    private OrderResponse placeOrderWithPrice(OrderRequest orderRequest, String userId, Double cachedPrice) {
        log.info("[PAPER TRADING] Placing order for {}: {} {} @ {}",
                 orderRequest.getTradingSymbol(),
                 orderRequest.getTransactionType(),
                 orderRequest.getQuantity(),
                 orderRequest.getOrderType());

        PaperAccount account = getOrCreateAccount(userId);
        String orderId = String.valueOf(orderIdGenerator.incrementAndGet());
        Long instrumentToken = getInstrumentToken(orderRequest.getTradingSymbol(), orderRequest.getExchange());

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

        String validationError = validateOrder(order);
        if (validationError != null) {
            return rejectAndReturnOrder(order, orderId, validationError);
        }

        Double currentPrice;
        try {
            currentPrice = (cachedPrice != null && cachedPrice > 0)
                    ? cachedPrice
                    : getCurrentPrice(orderRequest.getTradingSymbol(), orderRequest.getExchange());
        } catch (KiteException | IOException e) {
            String msg = "Failed to fetch LTP: " + e.getMessage();
            log.error("[PAPER TRADING] {}", msg, e);
            return rejectAndReturnOrder(order, orderId, msg);
        }

        Double requiredMargin = calculateRequiredMargin(order, currentPrice);

        if (TRANSACTION_BUY.equals(order.getTransactionType())) {
            if (!account.hasSufficientBalance(requiredMargin)) {
                String errorMsg = String.format(ERR_INSUFFICIENT_FUNDS,
                                                requiredMargin, account.getAvailableBalance());
                log.error("[PAPER TRADING] {}", errorMsg);
                return rejectAndReturnOrder(order, orderId, errorMsg);
            }
            account.blockMargin(requiredMargin);
        }

        order.setStatus(STATUS_PENDING);
        order.setStatusMessage(MSG_ORDER_VALIDATION_PENDING);
        orders.put(orderId, order);
        addToHistory(orderId, order);

        executeOrder(order, account, currentPrice);

        log.info("[PAPER TRADING] Order placed successfully: {}", orderId);
        return new OrderResponse(orderId, STATUS_SUCCESS, MSG_ORDER_PLACED_SUCCESS);
    }

    /**
     * Get execution price for an order
     */
    private Double getOrderExecutionPrice(String orderId) {
        PaperOrder order = orders.get(orderId);
        if (order != null) {
            return order.getAveragePrice();
        }
        return null;
    }

    /**
     * Determine overall status based on success/failure counts
     */
    private String determineOverallStatus(int successCount, int failureCount, int total) {
        if (successCount == total) {
            return STATUS_SUCCESS;
        } else if (successCount > 0) {
            return STATUS_PARTIAL;
        } else {
            return STATUS_FAILED;
        }
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
        LocalDateTime orderInitiatedAt = order.getOrderTimestamp();

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

        // Persist trade asynchronously (non-blocking for HFT)
        persistTradeAsync(order, orderInitiatedAt);
    }

    /**
     * Persist trade data asynchronously
     */
    private void persistTradeAsync(PaperOrder order, LocalDateTime orderInitiatedAt) {
        if (!persistenceConfig.isEnabled() || persistenceService == null) {
            return;
        }

        try {
            // Create trade entity
            TradeEntity trade = persistenceService.createTradeFromPaperOrder(order, "PAPER");

            // Calculate entry latency
            if (orderInitiatedAt != null && order.getExchangeTimestamp() != null) {
                long latencyMs = java.time.Duration.between(orderInitiatedAt, order.getExchangeTimestamp()).toMillis();
                trade.setEntryLatencyMs(latencyMs);
            }

            // Persist asynchronously
            persistenceService.persistTradeAsync(trade);

            // Also persist timing metrics
            OrderTimingEntity timing = OrderTimingEntity.builder()
                    .orderId(order.getOrderId())
                    .exchangeOrderId(order.getExchangeOrderId())
                    .userId(order.getPlacedBy())
                    .tradingSymbol(order.getTradingSymbol())
                    .transactionType(order.getTransactionType())
                    .orderType(order.getOrderType())
                    .orderInitiatedAt(orderInitiatedAt)
                    .orderExecutedAt(order.getExchangeTimestamp())
                    .actualPrice(order.getAveragePrice() != null ? BigDecimal.valueOf(order.getAveragePrice()) : null)
                    .orderStatus(order.getStatus())
                    .tradingMode("PAPER")
                    .orderContext("TRADE")
                    .orderTimestamp(order.getOrderTimestamp())
                    .build();

            persistenceService.persistOrderTimingAsync(timing);

        } catch (Exception e) {
            log.warn("[PAPER TRADING] Failed to persist trade: {}", e.getMessage());
            // Don't fail the trade if persistence fails
        }
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
        OrderCharges charges = chargeCalculator.calculateCharges(
                OPTIONS,
            TRANSACTION_BUY.equals(order.getTransactionType()) ? ZerodhaChargeCalculator.TransactionType.BUY : ZerodhaChargeCalculator.TransactionType.SELL,
            BigDecimal.valueOf(orderValue),
            BigDecimal.valueOf(order.getQuantity())
        );
        order.setChargesBreakdown(charges);
        order.setBrokerageCharges(charges.getBrokerage().doubleValue());
        order.setTaxes(charges.getStt()
                .add(charges.getExchangeTxnCharge())
                .add(charges.getGst())
                .add(charges.getSebiCharge())
                .add(charges.getStampDuty())
                .doubleValue());
         order.setTotalCharges(Double.valueOf(String.valueOf(charges.getTotalCharges())));
         log.info("[PAPER TRADING] Charges for order {}: Brokerage={}, Taxes={}, Total={}",
                  order.getOrderId(),
                  charges.getBrokerage(),
                  charges.getStt().add(charges.getExchangeTxnCharge())
                          .add(charges.getGst())
                          .add(charges.getSebiCharge())
                          .add(charges.getStampDuty()),
                  charges.getTotalCharges());
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

    /**
     * Get executed orders for a user
     */
    public List<PaperOrder> getExecutedOrdersForUser(String userId) {
        return orders.values().stream()
                .filter(o -> o.getPlacedBy().equals(userId))
                .filter(o -> STATUS_COMPLETE.equals(o.getStatus()))
                .toList();
    }

    /**
     * Get per-order charges in paper mode
     */
    public List<OrderChargesResponse> getOrderCharges(String userId) {
        return getExecutedOrdersForUser(userId).stream()
                .map(this::mapToChargeResponse)
                .toList();
    }

    private OrderChargesResponse mapToChargeResponse(PaperOrder order) {
        OrderCharges charges = order.getChargesBreakdown();
        OrderChargesResponse.Charges dtoCharges;
        if (charges != null) {
            double gstTotal = charges.getGst() != null ? charges.getGst().doubleValue() : 0.0;
            OrderChargesResponse.GstBreakdown gst = OrderChargesResponse.GstBreakdown.builder()
                    .igst(0.0)
                    .cgst(gstTotal / 2)
                    .sgst(gstTotal / 2)
                    .total(gstTotal)
                    .build();

            dtoCharges = OrderChargesResponse.Charges.builder()
                    .transactionTax(charges.getStt() != null ? charges.getStt().doubleValue() : 0.0)
                    .transactionTaxType(TRANSACTION_TAX_TYPE_STT)
                    .exchangeTurnoverCharge(charges.getExchangeTxnCharge() != null ? charges.getExchangeTxnCharge().doubleValue() : 0.0)
                    .sebiTurnoverCharge(charges.getSebiCharge() != null ? charges.getSebiCharge().doubleValue() : 0.0)
                    .brokerage(charges.getBrokerage() != null ? charges.getBrokerage().doubleValue() : 0.0)
                    .stampDuty(charges.getStampDuty() != null ? charges.getStampDuty().doubleValue() : 0.0)
                    .gst(gst)
                    .total(charges.getTotalCharges() != null ? charges.getTotalCharges().doubleValue() : 0.0)
                    .build();
        } else {
            dtoCharges = OrderChargesResponse.Charges.builder()
                    .transactionTax(0.0)
                    .transactionTaxType(TRANSACTION_TAX_TYPE_STT)
                    .exchangeTurnoverCharge(0.0)
                    .sebiTurnoverCharge(0.0)
                    .brokerage(0.0)
                    .stampDuty(0.0)
                    .gst(OrderChargesResponse.GstBreakdown.builder().igst(0.0).cgst(0.0).sgst(0.0).total(0.0).build())
                    .total(0.0)
                    .build();
        }

        return OrderChargesResponse.builder()
                .transactionType(order.getTransactionType())
                .tradingsymbol(order.getTradingSymbol())
                .exchange(order.getExchange())
                .variety(order.getVariety())
                .product(order.getProduct())
                .orderType(order.getOrderType())
                .quantity(order.getFilledQuantity())
                .price(order.getExecutionPrice())
                .charges(dtoCharges)
                .build();
    }
}
