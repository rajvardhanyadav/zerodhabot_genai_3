package com.tradingbot.service;

import com.tradingbot.config.PaperTradingConfig;
import com.tradingbot.dto.OrderRequest;
import com.tradingbot.dto.OrderResponse;
import com.tradingbot.paper.PaperAccount;
import com.tradingbot.paper.PaperOrder;
import com.tradingbot.paper.PaperPosition;
import com.tradingbot.paper.PaperTradingService;
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

    private final PaperTradingConfig config;
    private final TradingService liveTradingService;
    private final PaperTradingService paperTradingService;

    /**
     * Place order - routes to paper or live trading based on config
     */
    public OrderResponse placeOrder(OrderRequest orderRequest) throws KiteException, IOException {
        if (config.isPaperTradingEnabled()) {
            log.info("[PAPER MODE] Placing paper order");
            String userId = getUserId();
            return paperTradingService.placeOrder(orderRequest, userId);
        } else {
            log.info("💰 [LIVE MODE] Placing live order");
            return liveTradingService.placeOrder(orderRequest);
        }
    }

    /**
     * Modify order
     */
    public OrderResponse modifyOrder(String orderId, OrderRequest orderRequest) throws KiteException, IOException {
        if (config.isPaperTradingEnabled()) {
            log.info("🎯 [PAPER MODE] Modifying paper order: {}", orderId);
            String userId = getUserId();
            return paperTradingService.modifyOrder(orderId, orderRequest, userId);
        } else {
            log.info("💰 [LIVE MODE] Modifying live order: {}", orderId);
            return liveTradingService.modifyOrder(orderId, orderRequest);
        }
    }

    /**
     * Cancel order
     */
    public OrderResponse cancelOrder(String orderId) throws KiteException, IOException {
        if (config.isPaperTradingEnabled()) {
            log.info("🎯 [PAPER MODE] Cancelling paper order: {}", orderId);
            String userId = getUserId();
            return paperTradingService.cancelOrder(orderId, userId);
        } else {
            log.info("💰 [LIVE MODE] Cancelling live order: {}", orderId);
            return liveTradingService.cancelOrder(orderId);
        }
    }

    /**
     * Get all orders
     */
    public List<Order> getOrders() throws KiteException, IOException {
        if (config.isPaperTradingEnabled()) {
            log.debug("🎯 [PAPER MODE] Fetching paper orders");
            String userId = getUserId();
            List<PaperOrder> paperOrders = paperTradingService.getAllOrders(userId);
            return convertPaperOrdersToKiteOrders(paperOrders);
        } else {
            log.debug("💰 [LIVE MODE] Fetching live orders");
            return liveTradingService.getOrders();
        }
    }

    /**
     * Get order history
     */
    public List<Order> getOrderHistory(String orderId) throws KiteException, IOException {
        if (config.isPaperTradingEnabled()) {
            log.debug("🎯 [PAPER MODE] Fetching paper order history: {}", orderId);
            List<PaperOrder> paperOrders = paperTradingService.getOrderHistory(orderId);
            return convertPaperOrdersToKiteOrders(paperOrders);
        } else {
            log.debug("💰 [LIVE MODE] Fetching live order history: {}", orderId);
            return liveTradingService.getOrderHistory(orderId);
        }
    }

    /**
     * Get positions
     */
    public Map<String, List<Position>> getPositions() throws KiteException, IOException {
        if (config.isPaperTradingEnabled()) {
            log.debug("🎯 [PAPER MODE] Fetching paper positions");
            String userId = getUserId();
            List<PaperPosition> paperPositions = paperTradingService.getPositions(userId);
            return convertPaperPositionsToKitePositions(paperPositions);
        } else {
            log.debug("💰 [LIVE MODE] Fetching live positions");
            return liveTradingService.getPositions();
        }
    }

    /**
     * Get paper trading account (only available in paper mode)
     */
    public PaperAccount getPaperAccount() {
        if (config.isPaperTradingEnabled()) {
            String userId = getUserId();
            return paperTradingService.getAccount(userId);
        }
        return null;
    }

    /**
     * Reset paper trading account
     */
    public void resetPaperAccount() {
        if (config.isPaperTradingEnabled()) {
            String userId = getUserId();
            paperTradingService.resetAccount(userId);
            log.info("🎯 [PAPER MODE] Account reset completed");
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
        if (config.isPaperTradingEnabled()) {
            log.warn("🎯 [PAPER MODE] Holdings are not supported in paper trading mode");
            throw new UnsupportedOperationException("Holdings are only available in live trading mode");
        } else {
            log.debug("💰 [LIVE MODE] Fetching live holdings");
            return liveTradingService.getHoldings();
        }
    }

    /**
     * Get trades
     */
    public List<Trade> getTrades() throws KiteException, IOException {
        if (config.isPaperTradingEnabled()) {
            log.warn("🎯 [PAPER MODE] Trades endpoint not yet implemented in paper mode");
            // TODO: Implement paper trading trades tracking
            throw new UnsupportedOperationException("Trades tracking not yet implemented in paper trading mode");
        } else {
            log.debug("💰 [LIVE MODE] Fetching live trades");
            return liveTradingService.getTrades();
        }
    }

    /**
     * Convert position (only available in live mode)
     */
    public JSONObject convertPosition(String tradingSymbol, String exchange, String transactionType,
                                     String positionType, String oldProduct, String newProduct,
                                     int quantity) throws KiteException, IOException {
        if (config.isPaperTradingEnabled()) {
            log.warn("🎯 [PAPER MODE] Position conversion not supported in paper trading mode");
            throw new UnsupportedOperationException("Position conversion is only available in live trading mode");
        } else {
            log.info("💰 [LIVE MODE] Converting position: {} from {} to {}", tradingSymbol, oldProduct, newProduct);
            return liveTradingService.convertPosition(tradingSymbol, exchange, transactionType,
                    positionType, oldProduct, newProduct, quantity);
        }
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
        order.quantity = paperOrder.getQuantity() != null ? paperOrder.getQuantity().toString() : "0";
        order.price = paperOrder.getPrice() != null ? paperOrder.getPrice().toString() : "0";
        order.triggerPrice = paperOrder.getTriggerPrice() != null ? paperOrder.getTriggerPrice().toString() : "0";
        order.averagePrice = paperOrder.getAveragePrice() != null ? paperOrder.getAveragePrice().toString() : "0";
        order.filledQuantity = paperOrder.getFilledQuantity() != null ? paperOrder.getFilledQuantity().toString() : "0";
        order.pendingQuantity = paperOrder.getPendingQuantity() != null ? paperOrder.getPendingQuantity().toString() : "0";
        order.disclosedQuantity = paperOrder.getDisclosedQuantity() != null ? paperOrder.getDisclosedQuantity().toString() : "0";
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
        Map<String, List<Position>> positionsMap = new HashMap<>();

        // Convert paper positions to Kite position format
        List<Position> netPositions = new ArrayList<>();

        for (PaperPosition pp : paperPositions) {
            Position p = new Position();
            p.tradingSymbol = pp.getTradingSymbol();
            p.exchange = pp.getExchange();
            p.product = pp.getProduct();
            p.averagePrice = pp.getAveragePrice() != null ? pp.getAveragePrice() : 0.0;
            p.closePrice = pp.getClosePrice() != null ? pp.getClosePrice() : 0.0;
            p.lastPrice = pp.getLastPrice() != null ? pp.getLastPrice() : 0.0;
            p.value = pp.getValue() != null ? pp.getValue() : 0.0;
            p.pnl = pp.getPnl() != null ? pp.getPnl() : 0.0;
            p.m2m = pp.getM2m() != null ? pp.getM2m() : 0.0;
            p.unrealised = pp.getUnrealised() != null ? pp.getUnrealised() : 0.0;
            p.realised = pp.getRealised() != null ? pp.getRealised() : 0.0;
            p.buyQuantity = pp.getBuyQuantity() != null ? pp.getBuyQuantity() : 0;
            p.buyPrice = pp.getBuyPrice() != null ? pp.getBuyPrice() : 0.0;
            p.buyValue = pp.getBuyValue() != null ? pp.getBuyValue() : 0.0;
            p.sellQuantity = pp.getSellQuantity() != null ? pp.getSellQuantity() : 0;
            p.sellPrice = pp.getSellPrice() != null ? pp.getSellPrice() : 0.0;
            p.sellValue = pp.getSellValue() != null ? pp.getSellValue() : 0.0;
            p.dayBuyQuantity = pp.getDayBuyQuantity() != null ? pp.getDayBuyQuantity() : 0;
            p.dayBuyPrice = pp.getDayBuyPrice() != null ? pp.getDayBuyPrice() : 0.0;
            p.dayBuyValue = pp.getDayBuyValue() != null ? pp.getDayBuyValue() : 0.0;
            p.daySellQuantity = pp.getDaySellQuantity() != null ? pp.getDaySellQuantity() : 0;
            p.daySellPrice = pp.getDaySellPrice() != null ? pp.getDaySellPrice() : 0.0;
            p.daySellValue = pp.getDaySellValue() != null ? pp.getDaySellValue() : 0.0;
            p.overnightQuantity = pp.getOvernightQuantity() != null ? pp.getOvernightQuantity() : 0;
            p.multiplier = pp.getMultiplier() != null ? pp.getMultiplier().doubleValue() : 1.0;

            netPositions.add(p);
        }

        positionsMap.put("net", netPositions);
        positionsMap.put("day", new ArrayList<>()); // Empty for now

        return positionsMap;
    }

    private String getUserId() {
        // Get user ID from session or authentication context
        // For now, using a default user ID
        return "PAPER_USER";
    }
}
