package com.tradingbot.service;

import com.tradingbot.config.PaperTradingConfig;
import com.tradingbot.dto.BasketOrderRequest;
import com.tradingbot.dto.BasketOrderResponse;
import com.tradingbot.dto.DayPnLResponse;
import com.tradingbot.dto.OrderRequest;
import com.tradingbot.dto.OrderResponse;
import com.tradingbot.dto.OrderChargesResponse;
import com.tradingbot.paper.PaperAccount;
import com.tradingbot.paper.PaperOrder;
import com.tradingbot.paper.PaperPosition;
import com.tradingbot.paper.PaperTradingService;
import com.tradingbot.util.CurrentUserContext;
import com.zerodhatech.kiteconnect.kitehttp.exceptions.KiteException;
import com.zerodhatech.models.Holding;
import com.zerodhatech.models.Order;
import com.zerodhatech.models.Position;
import com.zerodhatech.models.Trade;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.json.JSONObject;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.time.ZoneId;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Unified Trading Service - Routes between Paper Trading and Live Trading
 * Based on configuration flag
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class UnifiedTradingService {

    private static final String PAPER_MODE_EMOJI = "\uD83C\uDFAF";
    private static final String LIVE_MODE_EMOJI = "\uD83D\uDCB0";
    private static final String PAPER_MODE = "PAPER";
    private static final String LIVE_MODE = "LIVE";

    private final PaperTradingConfig config;
    private final PaperTradingService paperTradingService;
    private final TradingService liveTradingService;

    /**
     * Place order - routes to paper or live trading based on config
     */
    public OrderResponse placeOrder(OrderRequest orderRequest) throws KiteException, IOException {
        String userId = getUserId();
        if (isPaperTradingEnabled()) {
            logPaperMode("Placing paper order for user=" + userId);
            return paperTradingService.placeOrder(orderRequest, userId);
        } else {
            logLiveMode("Placing live order for user=" + userId);
            return liveTradingService.placeOrder(orderRequest);
        }
    }

    /**
     * Place basket order - routes to paper or live trading based on config
     * Places multiple orders as a basket for atomic execution
     */
    public BasketOrderResponse placeBasketOrder(BasketOrderRequest basketRequest) {
        String userId = getUserId();
        if (isPaperTradingEnabled()) {
            logPaperMode("Placing paper basket order for user=" + userId);
            return paperTradingService.placeBasketOrder(basketRequest, userId);
        } else {
            logLiveMode("Placing live basket order for user=" + userId);
            return liveTradingService.placeBasketOrder(basketRequest);
        }
    }

    /**
     * Modify order
     */
    public OrderResponse modifyOrder(String orderId, OrderRequest orderRequest) throws KiteException, IOException {
        String userId = getUserId();
        if (isPaperTradingEnabled()) {
            logPaperMode("Modifying paper order: " + orderId + " for user=" + userId);
            return paperTradingService.modifyOrder(orderId, orderRequest, userId);
        } else {
            logLiveMode("Modifying live order: " + orderId + " for user=" + userId);
            return liveTradingService.modifyOrder(orderId, orderRequest);
        }
    }

    /**
     * Cancel order
     */
    public OrderResponse cancelOrder(String orderId) {
        String userId = getUserId();
        if (isPaperTradingEnabled()) {
            logPaperMode("Cancelling paper order: " + orderId + " for user=" + userId);
            return paperTradingService.cancelOrder(orderId, userId);
        } else {
            logLiveMode("Cancelling live order: " + orderId + " for user=" + userId);
            return liveTradingService.cancelOrder(orderId);
        }
    }

    /**
     * Get all orders
     */
    public List<Order> getOrders() throws KiteException, IOException {
        String userId = getUserId();
        if (isPaperTradingEnabled()) {
            log.debug("{} [{}] Fetching paper orders for user={}", PAPER_MODE_EMOJI, PAPER_MODE, userId);
            List<PaperOrder> paperOrders = paperTradingService.getAllOrders(userId);
            return convertPaperOrdersToKiteOrders(paperOrders);
        } else {
            log.debug("{} [{}] Fetching live orders for user={}", LIVE_MODE_EMOJI, LIVE_MODE, userId);
            return liveTradingService.getOrders();
        }
    }

    /**
     * Get order history
     */
    public List<Order> getOrderHistory(String orderId) throws KiteException, IOException {
        String userId = getUserId();
        if (isPaperTradingEnabled()) {
            log.debug("{} [{}] Fetching paper order history: {} for user={}", PAPER_MODE_EMOJI, PAPER_MODE, orderId, userId);
            List<PaperOrder> paperOrders = paperTradingService.getOrderHistory(orderId);
            return convertPaperOrdersToKiteOrders(paperOrders);
        } else {
            log.debug("{} [{}] Fetching live order history: {} for user={}", LIVE_MODE_EMOJI, LIVE_MODE, orderId, userId);
            return liveTradingService.getOrderHistory(orderId);
        }
    }

    /**
     * Get positions
     */
    public Map<String, List<Position>> getPositions() throws KiteException, IOException {
        String userId = getUserId();
        if (isPaperTradingEnabled()) {
            log.debug("{} [{}] Fetching paper positions for user={}", PAPER_MODE_EMOJI, PAPER_MODE, userId);
            List<PaperPosition> paperPositions = paperTradingService.getPositions(userId);
            return convertPaperPositionsToKitePositions(paperPositions);
        } else {
            log.debug("{} [{}] Fetching live positions for user={}", LIVE_MODE_EMOJI, LIVE_MODE, userId);
            return liveTradingService.getPositions();
        }
    }

    /**
     * Get paper trading account (only available in paper mode)
     */
    public PaperAccount getPaperAccount() {
        if (isPaperTradingEnabled()) {
            return paperTradingService.getAccount(getUserId());
        }
        return null;
    }

    /**
     * Reset paper trading account
     */
    public void resetPaperAccount() {
        if (isPaperTradingEnabled()) {
            paperTradingService.resetAccount(getUserId());
            logPaperMode("Account reset completed for user=" + getUserId());
        }
    }

    /**
     * Check if paper trading is enabled
     */
    public boolean isPaperTradingEnabled() {
        return config.isPaperTradingEnabled();
    }

    /**
     * Get holdings (only available in live mode)
     */
    public List<Holding> getHoldings() throws KiteException, IOException {
        if (isPaperTradingEnabled()) {
            log.warn("{} [{}] Holdings are not supported in paper trading mode", PAPER_MODE_EMOJI, PAPER_MODE);
            throw new UnsupportedOperationException("Holdings are only available in live trading mode");
        } else {
            log.debug("{} [{}] Fetching live holdings", LIVE_MODE_EMOJI, LIVE_MODE);
            return liveTradingService.getHoldings();
        }
    }

    /**
     * Get trades
     */
    public List<Trade> getTrades() throws KiteException, IOException {
        if (isPaperTradingEnabled()) {
            log.warn("{} [{}] Trades endpoint not yet implemented in paper mode", PAPER_MODE_EMOJI, PAPER_MODE);
            throw new UnsupportedOperationException("Trades tracking not yet implemented in paper trading mode");
        } else {
            log.debug("{} [{}] Fetching live trades", LIVE_MODE_EMOJI, LIVE_MODE);
            return liveTradingService.getTrades();
        }
    }

    /**
     * Convert position (only available in live mode)
     */
    public JSONObject convertPosition(String tradingSymbol, String exchange, String transactionType,
                                     String positionType, String oldProduct, String newProduct,
                                     int quantity) throws KiteException, IOException {
        if (isPaperTradingEnabled()) {
            log.warn("{} [{}] Position conversion not supported in paper trading mode", PAPER_MODE_EMOJI, PAPER_MODE);
            throw new UnsupportedOperationException("Position conversion is only available in live trading mode");
        } else {
            log.info("{} [{}] Converting position: {} from {} to {}", LIVE_MODE_EMOJI, LIVE_MODE, tradingSymbol, oldProduct, newProduct);
            return liveTradingService.convertPosition(tradingSymbol, exchange, transactionType,
                    positionType, oldProduct, newProduct, quantity);
        }
    }

    /**
     * Get total day P&L from all positions
     */
    public DayPnLResponse getDayPnL() throws KiteException, IOException {
        Map<String, List<Position>> positions = getPositions();

        double totalRealised = 0.0;
        double totalUnrealised = 0.0;
        double totalM2M = 0.0;
        int positionCount = 0;

        // Calculate P&L from net positions
        if (positions.containsKey("net") && positions.get("net") != null) {
            List<Position> netPositions = positions.get("net");
            positionCount = netPositions.size();

            for (Position position : netPositions) {
                totalRealised += safeToDouble(position.realised);
                totalUnrealised += safeToDouble(position.unrealised);
                totalM2M += safeToDouble(position.m2m);
            }
        }

        double totalDayPnL = totalRealised + totalUnrealised;
        String tradingMode = isPaperTradingEnabled() ? PAPER_MODE : LIVE_MODE;
        String emoji = isPaperTradingEnabled() ? PAPER_MODE_EMOJI : LIVE_MODE_EMOJI;

        log.info("{} [{}] Day P&L - Realised: {}, Unrealised: {}, Total: {}",
                 emoji, tradingMode, totalRealised, totalUnrealised, totalDayPnL);

        return new DayPnLResponse(
            totalRealised,
            totalUnrealised,
            totalM2M,
            totalDayPnL,
            positionCount,
            tradingMode
        );
    }

    /**
     * Get order charges
     */
    public List<OrderChargesResponse> getOrderCharges() throws KiteException, IOException {
        String userId = getUserId();
        if (isPaperTradingEnabled()) {
            logPaperMode("Fetching paper order charges for user=" + userId);
            return paperTradingService.getOrderCharges(userId);
        }
        logLiveMode("Fetching live order charges for user=" + userId);
        return liveTradingService.getOrderCharges();
    }

    // Helper methods for conversion

    private List<Order> convertPaperOrdersToKiteOrders(List<PaperOrder> paperOrders) {
        return paperOrders.stream()
                .map(this::convertPaperOrderToKiteOrder)
                .collect(Collectors.toList());
    }

    private Order convertPaperOrderToKiteOrder(PaperOrder paperOrder) {
        Order order = new Order();
        order.orderId = paperOrder.getOrderId();
        order.exchangeOrderId = paperOrder.getExchangeOrderId();
        order.status = paperOrder.getStatus();
        order.tradingSymbol = paperOrder.getTradingSymbol();
        order.exchange = paperOrder.getExchange();
        order.transactionType = paperOrder.getTransactionType();
        order.orderType = paperOrder.getOrderType();
        order.product = paperOrder.getProduct();
        order.quantity = safeToString(paperOrder.getQuantity());
        order.price = safeToString(paperOrder.getPrice());
        order.triggerPrice = safeToString(paperOrder.getTriggerPrice());
        order.averagePrice = safeToString(paperOrder.getAveragePrice());
        order.filledQuantity = safeToString(paperOrder.getFilledQuantity());
        order.pendingQuantity = safeToString(paperOrder.getPendingQuantity());
        order.disclosedQuantity = safeToString(paperOrder.getDisclosedQuantity());
        order.validity = paperOrder.getValidity();
        order.orderTimestamp = paperOrder.getOrderTimestamp() != null ?
                              Date.from(paperOrder.getOrderTimestamp().atZone(ZoneId.systemDefault()).toInstant()) : null;
        order.exchangeTimestamp = paperOrder.getExchangeTimestamp() != null ?
                                 Date.from(paperOrder.getExchangeTimestamp().atZone(ZoneId.systemDefault()).toInstant()) : null;
        order.statusMessage = paperOrder.getStatusMessage();
        order.parentOrderId = paperOrder.getParentOrderId();
        order.tag = paperOrder.getTag();
        return order;
    }

    private Map<String, List<Position>> convertPaperPositionsToKitePositions(List<PaperPosition> paperPositions) {
        List<Position> netPositions = paperPositions.stream()
                .map(this::convertPaperPositionToKitePosition)
                .collect(Collectors.toList());

        Map<String, List<Position>> positionsMap = new HashMap<>();
        positionsMap.put("net", netPositions);
        positionsMap.put("day", new ArrayList<>());
        return positionsMap;
    }

    private Position convertPaperPositionToKitePosition(PaperPosition pp) {
        Position p = new Position();
        p.tradingSymbol = pp.getTradingSymbol();
        p.exchange = pp.getExchange();
        p.product = pp.getProduct();
        p.averagePrice = safeToDouble(pp.getAveragePrice());
        p.closePrice = safeToDouble(pp.getClosePrice());
        p.lastPrice = safeToDouble(pp.getLastPrice());
        p.value = safeToDouble(pp.getValue());
        p.pnl = safeToDouble(pp.getPnl());
        p.m2m = safeToDouble(pp.getM2m());
        p.unrealised = safeToDouble(pp.getUnrealised());
        p.realised = safeToDouble(pp.getRealised());
        p.buyQuantity = safeToInt(pp.getBuyQuantity());
        p.buyPrice = safeToDouble(pp.getBuyPrice());
        p.buyValue = safeToDouble(pp.getBuyValue());
        p.sellQuantity = safeToInt(pp.getSellQuantity());
        p.sellPrice = safeToDouble(pp.getSellPrice());
        p.sellValue = safeToDouble(pp.getSellValue());
        p.dayBuyQuantity = safeToInt(pp.getDayBuyQuantity());
        p.dayBuyPrice = safeToDouble(pp.getDayBuyPrice());
        p.dayBuyValue = safeToDouble(pp.getDayBuyValue());
        p.daySellQuantity = safeToInt(pp.getDaySellQuantity());
        p.daySellPrice = safeToDouble(pp.getDaySellPrice());
        p.daySellValue = safeToDouble(pp.getDaySellValue());
        p.overnightQuantity = safeToInt(pp.getOvernightQuantity());
        p.multiplier = pp.getMultiplier() != null ? pp.getMultiplier().doubleValue() : 1.0;
        return p;
    }

    // Utility methods for safe conversions and logging

    private String safeToString(Object value) {
        return value != null ? value.toString() : "0";
    }

    private double safeToDouble(Double value) {
        return value != null ? value : 0.0;
    }

    private int safeToInt(Integer value) {
        return value != null ? value : 0;
    }

    private String getUserId() {
        String id = CurrentUserContext.getUserId();
        if (id == null || id.isBlank()) {
            // Fallback for background threads like WebSocket tick handlers where ThreadLocal may be empty.
            // In paper trading mode, we can safely default to a fixed paper user id to avoid failures.
            if (isPaperTradingEnabled()) {
                String fallbackUserId = "PAPER_DEFAULT_USER";
                log.warn("User context missing on current thread; falling back to {} in paper mode", fallbackUserId);
                return fallbackUserId;
            }
            // For live mode, keep the strict behavior to avoid mis-attributing orders.
            throw new IllegalStateException("User context is missing. Provide X-User-Id header.");
        }
        return id;
    }

    private void logPaperMode(String message) {
        log.info("{} [{}] {}", PAPER_MODE_EMOJI, PAPER_MODE, message);
    }

    private void logLiveMode(String message) {
        log.info("{} [{}] {}", LIVE_MODE_EMOJI, LIVE_MODE, message);
    }
}
