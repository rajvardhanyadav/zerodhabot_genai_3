package com.tradingbot.service;

import com.tradingbot.config.KiteConfig;
import com.tradingbot.dto.OrderRequest;
import com.tradingbot.dto.OrderResponse;
import com.zerodhatech.kiteconnect.KiteConnect;
import com.zerodhatech.kiteconnect.kitehttp.exceptions.KiteException;
import com.zerodhatech.models.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.json.JSONObject;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.Date;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
@Slf4j
public class TradingService {

    private final KiteConnect kiteConnect;
    private final KiteConfig kiteConfig;

    /**
     * Generate login URL for Kite Connect authentication
     */
    public String getLoginUrl() {
        return kiteConnect.getLoginURL();
    }

    /**
     * Generate session using request token
     */
    public User generateSession(String requestToken) throws KiteException, IOException {
        User user = kiteConnect.generateSession(requestToken, kiteConfig.getApiSecret());
        kiteConnect.setAccessToken(user.accessToken);
        log.info("Session generated successfully for user: {}", user.userId);
        return user;
    }

    /**
     * Get user profile
     */
    public Profile getUserProfile() throws KiteException, IOException {
        return kiteConnect.getProfile();
    }

    /**
     * Get account margins
     */
    public Margin getMargins(String segment) throws KiteException, IOException {
        return kiteConnect.getMargins(segment);
    }

    /**
     * Place a new order
     */
    public OrderResponse placeOrder(OrderRequest orderRequest) throws KiteException, IOException {
        OrderParams orderParams = new OrderParams();
        orderParams.tradingsymbol = orderRequest.getTradingSymbol();
        orderParams.exchange = orderRequest.getExchange();
        orderParams.transactionType = orderRequest.getTransactionType();
        orderParams.quantity = orderRequest.getQuantity();
        orderParams.product = orderRequest.getProduct();
        orderParams.orderType = orderRequest.getOrderType();
        orderParams.price = orderRequest.getPrice();
        orderParams.triggerPrice = orderRequest.getTriggerPrice();
        orderParams.validity = orderRequest.getValidity();
        orderParams.disclosedQuantity = orderRequest.getDisclosedQuantity();

        Order order = kiteConnect.placeOrder(orderParams, "regular");
        log.info("Order placed successfully: {}", order.orderId);

        return new OrderResponse(order.orderId, "SUCCESS", "Order placed successfully");
    }

    /**
     * Modify an existing order
     */
    public OrderResponse modifyOrder(String orderId, OrderRequest orderRequest) throws KiteException, IOException {
        OrderParams orderParams = new OrderParams();
        orderParams.quantity = orderRequest.getQuantity();
        orderParams.price = orderRequest.getPrice();
        orderParams.orderType = orderRequest.getOrderType();
        orderParams.triggerPrice = orderRequest.getTriggerPrice();
        orderParams.validity = orderRequest.getValidity();
        orderParams.disclosedQuantity = orderRequest.getDisclosedQuantity();

        Order order = kiteConnect.modifyOrder(orderId, orderParams, "regular");
        log.info("Order modified successfully: {}", orderId);

        return new OrderResponse(order.orderId, "SUCCESS", "Order modified successfully");
    }

    /**
     * Cancel an order
     */
    public OrderResponse cancelOrder(String orderId) throws KiteException, IOException {
        Order order = kiteConnect.cancelOrder(orderId, "regular");
        log.info("Order cancelled successfully: {}", orderId);

        return new OrderResponse(order.orderId, "SUCCESS", "Order cancelled successfully");
    }

    /**
     * Get all orders for the day
     */
    public List<Order> getOrders() throws KiteException, IOException {
        return kiteConnect.getOrders();
    }

    /**
     * Get order history
     */
    public List<Order> getOrderHistory(String orderId) throws KiteException, IOException {
        return kiteConnect.getOrderHistory(orderId);
    }

    /**
     * Get all trades for the day
     */
    public List<Trade> getTrades() throws KiteException, IOException {
        return kiteConnect.getTrades();
    }

    /**
     * Get all positions
     */
    public Map<String, List<Position>> getPositions() throws KiteException, IOException {
        return kiteConnect.getPositions();
    }

    /**
     * Get holdings
     */
    public List<Holding> getHoldings() throws KiteException, IOException {
        return kiteConnect.getHoldings();
    }

    /**
     * Convert position
     */
    public JSONObject convertPosition(String tradingSymbol, String exchange, String transactionType,
                                     String positionType, String oldProduct, String newProduct,
                                     int quantity) throws KiteException, IOException {
        return kiteConnect.convertPosition(tradingSymbol, exchange, transactionType,
                positionType, oldProduct, newProduct, quantity);
    }

    /**
     * Get quote for instruments
     */
    public Map<String, Quote> getQuote(String[] instruments) throws KiteException, IOException {
        return kiteConnect.getQuote(instruments);
    }

    /**
     * Get OHLC data
     */
    public Map<String, OHLCQuote> getOHLC(String[] instruments) throws KiteException, IOException {
        return kiteConnect.getOHLC(instruments);
    }

    /**
     * Get LTP (Last Traded Price)
     */
    public Map<String, LTPQuote> getLTP(String[] instruments) throws KiteException, IOException {
        return kiteConnect.getLTP(instruments);
    }

    /**
     * Get historical data
     */
    public HistoricalData getHistoricalData(Date fromDate, Date toDate, String instrumentToken,
                                                    String interval, boolean continuous, boolean oi)
            throws KiteException, IOException {
        return kiteConnect.getHistoricalData(fromDate, toDate, instrumentToken, interval, continuous, oi);
    }

    /**
     * Get all instruments
     */
    public List<Instrument> getInstruments() throws KiteException, IOException {
        return kiteConnect.getInstruments();
    }

    /**
     * Get instruments for specific exchange
     */
    public List<Instrument> getInstruments(String exchange) throws KiteException, IOException {
        return kiteConnect.getInstruments(exchange);
    }

    /**
     * Get GTT (Good Till Triggered) orders
     */
    public List<GTT> getGTTs() throws KiteException, IOException {
        return kiteConnect.getGTTs();
    }

    /**
     * Place GTT order
     */
    public GTT placeGTT(GTTParams gttParams) throws KiteException, IOException {
        return kiteConnect.placeGTT(gttParams);
    }

    /**
     * Get GTT order by ID
     */
    public GTT getGTT(int triggerId) throws KiteException, IOException {
        return kiteConnect.getGTT(triggerId);
    }

    /**
     * Modify GTT order
     */
    public GTT modifyGTT(int triggerId, GTTParams gttParams) throws KiteException, IOException {
        return kiteConnect.modifyGTT(triggerId, gttParams);
    }

    /**
     * Cancel GTT order
     */
    public GTT cancelGTT(int triggerId) throws KiteException, IOException {
        return kiteConnect.cancelGTT(triggerId);
    }
}
