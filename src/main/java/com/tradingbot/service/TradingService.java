package com.tradingbot.service;

import com.tradingbot.config.KiteConfig;
import com.tradingbot.dto.OrderChargesResponse;
import com.tradingbot.dto.OrderRequest;
import com.tradingbot.dto.OrderResponse;
import com.zerodhatech.kiteconnect.KiteConnect;
import com.zerodhatech.kiteconnect.kitehttp.exceptions.KiteException;
import com.zerodhatech.models.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.json.JSONArray;
import org.json.JSONObject;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class TradingService {

    private final KiteConnect kiteConnect;
    private final KiteConfig kiteConfig;
    private final RestTemplate restTemplate;

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
        log.error("Session generated successfully for user: {}", user.userId);
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
        try {
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

            // Check if order was placed successfully
            if (order != null && order.orderId != null && !order.orderId.isEmpty()) {
                log.info("Order placed successfully: {}", order.orderId);
                log.info(order.orderId + " " + order.status + " " + order.tradingSymbol + " " + order.transactionType + " " + order.quantity + " " + order.price + " " + order.orderType + " " + order.product + " " + order.exchange + " " + order.validity + " " + order.triggerPrice + " " + order.disclosedQuantity + " " + order.orderTimestamp + " " + order.exchangeTimestamp + " " + order.averagePrice + " " + order.filledQuantity + " " + order.pendingQuantity + " " + order.meta + " " + order.tag);
                return new OrderResponse(order.orderId, "SUCCESS", "Order placed successfully");
            } else {
                log.error("Order placement failed - no order ID returned");
                return new OrderResponse(null, "FAILED", "Order placement failed - no order ID returned");
            }

        } catch (KiteException e) {
            log.error("Kite API error while placing order: {}", e.message, e);
            return new OrderResponse(null, "FAILED", "Order placement failed: " + e.message);
        } catch (IOException e) {
            log.error("Network error while placing order: {}", e.getMessage(), e);
            return new OrderResponse(null, "FAILED", "Network error: " + e.getMessage());
        } catch (Exception e) {
            log.error("Unexpected error while placing order: {}", e.getMessage(), e);
            return new OrderResponse(null, "FAILED", "Unexpected error: " + e.getMessage());
        }
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
        log.error("Order modified successfully: {}", orderId);

        return new OrderResponse(order.orderId, "SUCCESS", "Order modified successfully");
    }

    /**
     * Cancel an order
     */
    public OrderResponse cancelOrder(String orderId) throws KiteException, IOException {
        try {
            Order order = kiteConnect.cancelOrder(orderId, "regular");

            // Check if order was cancelled successfully
            if (order != null && order.orderId != null && !order.orderId.isEmpty()) {
                log.info("Order cancelled successfully: {}", orderId);
                return new OrderResponse(order.orderId, "SUCCESS", "Order cancelled successfully");
            } else {
                log.error("Order cancellation failed for orderId: {}", orderId);
                return new OrderResponse(orderId, "FAILED", "Order cancellation failed");
            }

        } catch (KiteException e) {
            log.error("Kite API error while cancelling order {}: {}", orderId, e.message, e);
            return new OrderResponse(orderId, "FAILED", "Order cancellation failed: " + e.message);
        } catch (IOException e) {
            log.error("Network error while cancelling order {}: {}", orderId, e.getMessage(), e);
            return new OrderResponse(orderId, "FAILED", "Network error: " + e.getMessage());
        } catch (Exception e) {
            log.error("Unexpected error while cancelling order {}: {}", orderId, e.getMessage(), e);
            return new OrderResponse(orderId, "FAILED", "Unexpected error: " + e.getMessage());
        }
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

    /**
     * Get order charges for orders placed today
     * This calls the Kite API to get actual charges breakdown for executed orders
     *
     * @return List of OrderChargesResponse with detailed charge breakdown for each order
     * @throws KiteException if Kite API returns an error
     * @throws IOException if network error occurs
     */
    public List<OrderChargesResponse> getOrderCharges() throws KiteException, IOException {
        try {
            // Get all orders for the day
            List<Order> orders = kiteConnect.getOrders();

            if (orders == null || orders.isEmpty()) {
                log.info("No orders found for today");
                return new ArrayList<>();
            }

            // Filter only executed/completed orders
            List<Order> executedOrders = orders.stream()
                    .filter(order -> "COMPLETE".equals(order.status))
                    .collect(Collectors.toList());

            if (executedOrders.isEmpty()) {
                log.info("No executed orders found for today");
                return new ArrayList<>();
            }

            // Build request payload
            JSONArray requestArray = new JSONArray();
            for (Order order : executedOrders) {
                JSONObject orderJson = new JSONObject();
                orderJson.put("order_id", order.orderId);
                orderJson.put("exchange", order.exchange);
                orderJson.put("tradingsymbol", order.tradingSymbol);
                orderJson.put("transaction_type", order.transactionType);
                orderJson.put("variety", order.orderVariety);
                orderJson.put("product", order.product);
                orderJson.put("order_type", order.orderType);
                orderJson.put("quantity", order.filledQuantity);
                orderJson.put("average_price", order.averagePrice);
                requestArray.put(orderJson);
            }

            // Make HTTP POST request to Kite charges API
            String url = "https://kite.zerodha.com/oms/charges/orders";

            HttpHeaders headers = new HttpHeaders();
            headers.set("Authorization", "token " + kiteConfig.getApiKey() + ":" + kiteConnect.getAccessToken());
            headers.set("Content-Type", "application/json");
            headers.set("X-Kite-Version", "3");

            HttpEntity<String> entity = new HttpEntity<>(requestArray.toString(), headers);

            log.info("Calling Kite charges API with {} orders", executedOrders.size());
            ResponseEntity<String> response = restTemplate.exchange(url, HttpMethod.POST, entity, String.class);

            // Parse response
            JSONObject responseJson = new JSONObject(response.getBody());

            if (!"success".equals(responseJson.getString("status"))) {
                throw new KiteException("Failed to fetch order charges", 500);
            }

            JSONArray dataArray = responseJson.getJSONArray("data");
            List<OrderChargesResponse> chargesResponses = new ArrayList<>();

            for (int i = 0; i < dataArray.length(); i++) {
                JSONObject orderData = dataArray.getJSONObject(i);
                JSONObject chargesJson = orderData.getJSONObject("charges");
                JSONObject gstJson = chargesJson.getJSONObject("gst");

                OrderChargesResponse.GstBreakdown gst = OrderChargesResponse.GstBreakdown.builder()
                        .igst(gstJson.getDouble("igst"))
                        .cgst(gstJson.getDouble("cgst"))
                        .sgst(gstJson.getDouble("sgst"))
                        .total(gstJson.getDouble("total"))
                        .build();

                OrderChargesResponse.Charges charges = OrderChargesResponse.Charges.builder()
                        .transactionTax(chargesJson.getDouble("transaction_tax"))
                        .transactionTaxType(chargesJson.getString("transaction_tax_type"))
                        .exchangeTurnoverCharge(chargesJson.getDouble("exchange_turnover_charge"))
                        .sebiTurnoverCharge(chargesJson.getDouble("sebi_turnover_charge"))
                        .brokerage(chargesJson.getDouble("brokerage"))
                        .stampDuty(chargesJson.getDouble("stamp_duty"))
                        .gst(gst)
                        .total(chargesJson.getDouble("total"))
                        .build();

                OrderChargesResponse orderCharges = OrderChargesResponse.builder()
                        .transactionType(orderData.getString("transaction_type"))
                        .tradingsymbol(orderData.getString("tradingsymbol"))
                        .exchange(orderData.getString("exchange"))
                        .variety(orderData.getString("variety"))
                        .product(orderData.getString("product"))
                        .orderType(orderData.getString("order_type"))
                        .quantity(orderData.getInt("quantity"))
                        .price(orderData.getDouble("price"))
                        .charges(charges)
                        .build();

                chargesResponses.add(orderCharges);
            }

            log.info("Successfully fetched charges for {} orders", chargesResponses.size());
            return chargesResponses;

        } catch (Exception e) {
            log.error("Error fetching order charges: {}", e.getMessage(), e);
            throw new KiteException("Failed to fetch order charges: " + e.getMessage(), 500);
        }
    }
}
