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
import org.json.JSONObject;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;

import static com.tradingbot.service.TradingConstants.*;

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
        log.debug("Generating Kite Connect login URL");
        String loginUrl = kiteConnect.getLoginURL();
        log.debug("Login URL generated: {}", loginUrl);
        return loginUrl;
    }

    /**
     * Generate session using request token
     */
    public User generateSession(String requestToken) throws KiteException, IOException {
        log.info("Generating session with request token");
        User user = kiteConnect.generateSession(requestToken, kiteConfig.getApiSecret());
        kiteConnect.setAccessToken(user.accessToken);
        log.info("Session generated successfully for user: {}", user.userId);
        return user;
    }

    /**
     * Get user profile
     */
    public Profile getUserProfile() throws KiteException, IOException {
        log.debug("Fetching user profile");
        Profile profile = kiteConnect.getProfile();
        log.debug("User profile fetched for user: {}", profile.userName);
        return profile;
    }

    /**
     * Get account margins
     */
    public Margin getMargins(String segment) throws KiteException, IOException {
        log.debug("Fetching margins for segment: {}", segment);
        Margin margin = kiteConnect.getMargins(segment);
        log.debug("Margins fetched - Available: {}, Used: {}", margin.available.cash, margin.utilised.debits);
        return margin;
    }

    /**
     * Place a new order
     */
    public OrderResponse placeOrder(OrderRequest orderRequest) {
        log.info("Placing order - Symbol: {}, Type: {}, Qty: {}",
            orderRequest.getTradingSymbol(), orderRequest.getTransactionType(), orderRequest.getQuantity());
        try {
            OrderParams orderParams = buildOrderParams(orderRequest);
            Order order = kiteConnect.placeOrder(orderParams, VARIETY_REGULAR);

            // Check if order was placed successfully
            if (order != null && order.orderId != null && !order.orderId.isEmpty()) {
                log.info("Order placed successfully: {} - {} {} {} @ {}",
                    order.orderId, order.transactionType, order.quantity, order.tradingSymbol, order.orderType);
                return new OrderResponse(order.orderId, STATUS_SUCCESS, MSG_ORDER_PLACED_SUCCESS);
            } else {
                log.error("Order placement failed - no order ID returned");
                return new OrderResponse(null, STATUS_FAILED, ERR_ORDER_PLACEMENT_FAILED_NO_ID);
            }

        } catch (KiteException e) {
            log.error("Kite API error while placing order: {}", e.message, e);
            return new OrderResponse(null, STATUS_FAILED, ERR_ORDER_PLACEMENT_FAILED + e.message);
        } catch (IOException e) {
            log.error("Network error while placing order: {}", e.getMessage(), e);
            return new OrderResponse(null, STATUS_FAILED, ERR_NETWORK + e.getMessage());
        } catch (Exception e) {
            log.error("Unexpected error while placing order: {}", e.getMessage(), e);
            return new OrderResponse(null, STATUS_FAILED, ERR_UNEXPECTED + e.getMessage());
        }
    }

    /**
     * Modify an existing order
     */
    public OrderResponse modifyOrder(String orderId, OrderRequest orderRequest) throws KiteException, IOException {
        log.info("Modifying order: {} - New params: {}", orderId, orderRequest);
        OrderParams orderParams = buildModifyOrderParams(orderRequest);
        Order order = kiteConnect.modifyOrder(orderId, orderParams, VARIETY_REGULAR);
        log.info("Order modified successfully: {}", orderId);

        return new OrderResponse(order.orderId, STATUS_SUCCESS, MSG_ORDER_MODIFIED_SUCCESS);
    }

    /**
     * Cancel an order
     */
    public OrderResponse cancelOrder(String orderId) {
        log.info("Cancelling order: {}", orderId);
        try {
            Order order = kiteConnect.cancelOrder(orderId, VARIETY_REGULAR);

            // Check if order was cancelled successfully
            if (order != null && order.orderId != null && !order.orderId.isEmpty()) {
                log.info("Order cancelled successfully: {}", orderId);
                return new OrderResponse(order.orderId, STATUS_SUCCESS, MSG_ORDER_CANCELLED_SUCCESS);
            } else {
                log.error("Order cancellation failed for orderId: {}", orderId);
                return new OrderResponse(orderId, STATUS_FAILED, ERR_ORDER_CANCELLATION_FAILED);
            }

        } catch (KiteException e) {
            log.error("Kite API error while cancelling order {}: {}", orderId, e.message, e);
            return new OrderResponse(orderId, STATUS_FAILED, ERR_ORDER_CANCELLATION_FAILED + ": " + e.message);
        } catch (IOException e) {
            log.error("Network error while cancelling order {}: {}", orderId, e.getMessage(), e);
            return new OrderResponse(orderId, STATUS_FAILED, ERR_NETWORK + e.getMessage());
        } catch (Exception e) {
            log.error("Unexpected error while cancelling order {}: {}", orderId, e.getMessage(), e);
            return new OrderResponse(orderId, STATUS_FAILED, ERR_UNEXPECTED + e.getMessage());
        }
    }

    /**
     * Get all orders for the day
     */
    public List<Order> getOrders() throws KiteException, IOException {
        log.debug("Fetching all orders for the day");
        List<Order> orders = kiteConnect.getOrders();
        log.debug("Fetched {} orders", orders != null ? orders.size() : 0);
        return orders;
    }

    /**
     * Get order history
     */
    public List<Order> getOrderHistory(String orderId) throws KiteException, IOException {
        log.debug("Fetching order history for order: {}", orderId);
        List<Order> history = kiteConnect.getOrderHistory(orderId);
        log.debug("Fetched {} order history records for order: {}", history != null ? history.size() : 0, orderId);
        return history;
    }

    /**
     * Get all trades for the day
     */
    public List<Trade> getTrades() throws KiteException, IOException {
        log.debug("Fetching all trades for the day");
        List<Trade> trades = kiteConnect.getTrades();
        log.debug("Fetched {} trades", trades != null ? trades.size() : 0);
        return trades;
    }

    /**
     * Get all positions
     */
    public Map<String, List<Position>> getPositions() throws KiteException, IOException {
        log.debug("Fetching all positions");
        Map<String, List<Position>> positions = kiteConnect.getPositions();
        log.debug("Fetched positions - Net: {}, Day: {}",
            positions.get(POSITION_NET) != null ? positions.get(POSITION_NET).size() : 0,
            positions.get(POSITION_DAY) != null ? positions.get(POSITION_DAY).size() : 0);
        return positions;
    }

    /**
     * Get holdings
     */
    public List<Holding> getHoldings() throws KiteException, IOException {
        log.debug("Fetching holdings");
        List<Holding> holdings = kiteConnect.getHoldings();
        log.debug("Fetched {} holdings", holdings != null ? holdings.size() : 0);
        return holdings;
    }

    /**
     * Convert position
     */
    public JSONObject convertPosition(String tradingSymbol, String exchange, String transactionType,
                                      String positionType, String oldProduct, String newProduct,
                                      int quantity) throws KiteException, IOException {
        log.info("Converting position - Symbol: {}, Exchange: {}, Type: {}, Qty: {}, From: {} To: {}",
            tradingSymbol, exchange, transactionType, quantity, oldProduct, newProduct);
        JSONObject result = kiteConnect.convertPosition(tradingSymbol, exchange, transactionType,
                positionType, oldProduct, newProduct, quantity);
        log.info("Position conversion completed for symbol: {}", tradingSymbol);
        return result;
    }

    /**
     * Get quote for instruments
     */
    public Map<String, Quote> getQuote(String[] instruments) throws KiteException, IOException {
        log.debug("Fetching quotes for {} instruments", instruments.length);
        Map<String, Quote> quotes = kiteConnect.getQuote(instruments);
        log.debug("Fetched quotes for {} instruments", quotes != null ? quotes.size() : 0);
        return quotes;
    }

    /**
     * Get OHLC data
     */
    public Map<String, OHLCQuote> getOHLC(String[] instruments) throws KiteException, IOException {
        log.debug("Fetching OHLC data for {} instruments", instruments.length);
        Map<String, OHLCQuote> ohlc = kiteConnect.getOHLC(instruments);
        log.debug("Fetched OHLC data for {} instruments", ohlc != null ? ohlc.size() : 0);
        return ohlc;
    }

    /**
     * Get LTP (Last Traded Price)
     */
    public Map<String, LTPQuote> getLTP(String[] instruments) throws KiteException, IOException {
        log.debug("Fetching LTP for {} instruments", instruments.length);
        Map<String, LTPQuote> ltp = kiteConnect.getLTP(instruments);
        log.debug("Fetched LTP for {} instruments", ltp != null ? ltp.size() : 0);
        return ltp;
    }

    /**
     * Get historical data
     */
    public HistoricalData getHistoricalData(Date fromDate, Date toDate, String instrumentToken,
                                            String interval, boolean continuous, boolean oi)
            throws KiteException, IOException {
        log.debug("Fetching historical data - Token: {}, Interval: {}, From: {}, To: {}",
            instrumentToken, interval, fromDate, toDate);
        HistoricalData data = kiteConnect.getHistoricalData(fromDate, toDate, instrumentToken, interval, continuous, oi);
        log.debug("Fetched {} candles of historical data", data != null && data.dataArrayList != null ? data.dataArrayList.size() : 0);
        return data;
    }

    /**
     * Get all instruments
     */
    public List<Instrument> getInstruments() throws KiteException, IOException {
        log.debug("Fetching all instruments");
        List<Instrument> instruments = kiteConnect.getInstruments();
        log.debug("Fetched {} instruments", instruments != null ? instruments.size() : 0);
        return instruments;
    }

    /**
     * Get instruments for specific exchange
     */
    public List<Instrument> getInstruments(String exchange) throws KiteException, IOException {
        log.debug("Fetching instruments for exchange: {}", exchange);
        List<Instrument> instruments = kiteConnect.getInstruments(exchange);
        log.debug("Fetched {} instruments for exchange: {}", instruments != null ? instruments.size() : 0, exchange);
        return instruments;
    }

    /**
     * Get GTT (Good Till Triggered) orders
     */
    public List<GTT> getGTTs() throws KiteException, IOException {
        log.debug("Fetching GTT orders");
        List<GTT> gtts = kiteConnect.getGTTs();
        log.debug("Fetched {} GTT orders", gtts != null ? gtts.size() : 0);
        return gtts;
    }

    /**
     * Place GTT order
     */
    public GTT placeGTT(GTTParams gttParams) throws KiteException, IOException {
        log.info("Placing GTT order - Type: {}, Symbol: {}", gttParams.triggerType, gttParams.tradingsymbol);
        GTT gtt = kiteConnect.placeGTT(gttParams);
        log.info("GTT order placed successfully - ID: {}", gtt.id);
        return gtt;
    }

    /**
     * Get GTT order by ID
     */
    public GTT getGTT(int triggerId) throws KiteException, IOException {
        log.debug("Fetching GTT order: {}", triggerId);
        GTT gtt = kiteConnect.getGTT(triggerId);
        log.debug("Fetched GTT order: {} - Status: {}", triggerId, gtt.status);
        return gtt;
    }

    /**
     * Modify GTT order
     */
    public GTT modifyGTT(int triggerId, GTTParams gttParams) throws KiteException, IOException {
        log.info("Modifying GTT order: {}", triggerId);
        GTT gtt = kiteConnect.modifyGTT(triggerId, gttParams);
        log.info("GTT order modified successfully: {}", triggerId);
        return gtt;
    }

    /**
     * Cancel GTT order
     */
    public GTT cancelGTT(int triggerId) throws KiteException, IOException {
        log.info("Cancelling GTT order: {}", triggerId);
        GTT gtt = kiteConnect.cancelGTT(triggerId);
        log.info("GTT order cancelled successfully: {}", triggerId);
        return gtt;
    }

    /**
     * Get order charges for orders placed today
     * This uses the Kite Connect SDK's getVirtualContractNote method to get actual charges breakdown for executed orders
     * <a href="https://kite.trade/docs/connect/v3/margins/#virtual-contract-note">API Reference</a>
     *
     * @return List of OrderChargesResponse with detailed charge breakdown for each order
     * @throws KiteException if Kite API returns an error
     */
    public List<OrderChargesResponse> getOrderCharges() throws KiteException {
        try {
            // Get all orders for the day
            List<Order> orders = kiteConnect.getOrders();

            if (orders == null || orders.isEmpty()) {
                log.info("No orders found for today");
                return new ArrayList<>();
            }
            log.info("Total orders fetched for today: {}", orders.size());

            // Filter only executed/completed orders
            List<Order> executedOrders = orders.stream()
                    .filter(order -> STATUS_COMPLETE.equals(order.status))
                    .toList();

            if (executedOrders.isEmpty()) {
                log.info("No executed orders found for today");
                return new ArrayList<>();
            }
            log.info("Executed orders count: {}", executedOrders.size());

            // Build ContractNoteParams list for KiteConnect SDK method
            List<ContractNoteParams> contractNoteParamsList = new ArrayList<>();
            for (Order order : executedOrders) {
                ContractNoteParams params = new ContractNoteParams();
                params.orderID = order.orderId;
                params.exchange = order.exchange;
                params.tradingSymbol = order.tradingSymbol;
                params.transactionType = order.transactionType;
                params.variety = order.orderVariety != null ? order.orderVariety : VARIETY_REGULAR;
                params.product = order.product;
                params.orderType = order.orderType;
                // Parse String to int/double - Order model has these as Strings
                params.quantity = Integer.parseInt(order.filledQuantity);
                params.averagePrice = Double.parseDouble(order.averagePrice);
                contractNoteParamsList.add(params);
            }
            log.info("Built ContractNoteParams for {} executed orders", executedOrders.size());

            // Call KiteConnect SDK's getVirtualContractNote method
            log.info("Calling KiteConnect.getVirtualContractNote() with {} orders", executedOrders.size());
            List<ContractNote> contractNotes = kiteConnect.getVirtualContractNote(contractNoteParamsList);

            if (contractNotes == null || contractNotes.isEmpty()) {
                log.warn("No contract notes returned from Kite API");
                return new ArrayList<>();
            }

            // Convert ContractNote objects to OrderChargesResponse
            List<OrderChargesResponse> chargesResponses = new ArrayList<>();
            for (ContractNote contractNote : contractNotes) {

                // Build GST breakdown - ContractNote.charges.gst fields are primitives
                OrderChargesResponse.GstBreakdown gst = OrderChargesResponse.GstBreakdown.builder()
                        .igst(contractNote.charges.gst.IGST)
                        .cgst(contractNote.charges.gst.CGST)
                        .sgst(contractNote.charges.gst.SGST)
                        .total(contractNote.charges.gst.total)
                        .build();

                // Build charges breakdown - all fields are primitives (double)
                OrderChargesResponse.Charges charges = OrderChargesResponse.Charges.builder()
                        .transactionTax(contractNote.charges.transactionTax)
                        .transactionTaxType(contractNote.charges.transactionTaxType != null ? contractNote.charges.transactionTaxType : TRANSACTION_TAX_TYPE_STT)
                        .exchangeTurnoverCharge(contractNote.charges.exchangeTurnoverCharge)
                        .sebiTurnoverCharge(0.0)  // SEBI charge field not available in ContractNote SDK model
                        .brokerage(contractNote.charges.brokerage)
                        .stampDuty(contractNote.charges.stampDuty)
                        .gst(gst)
                        .total(contractNote.charges.total)
                        .build();

                // Build order charges response
                OrderChargesResponse orderCharges = OrderChargesResponse.builder()
                        .transactionType(contractNote.transactionType != null ? contractNote.transactionType : "")
                        .tradingsymbol(contractNote.tradingSymbol != null ? contractNote.tradingSymbol : "")
                        .exchange(contractNote.exchange != null ? contractNote.exchange : "")
                        .variety(contractNote.variety != null ? contractNote.variety : VARIETY_REGULAR)
                        .product(contractNote.product != null ? contractNote.product : "")
                        .orderType(contractNote.orderType != null ? contractNote.orderType : "")
                        .quantity(contractNote.quantity)
                        .price(contractNote.price)
                        .charges(charges)
                        .build();

                chargesResponses.add(orderCharges);
            }

            double totalCharges = chargesResponses.stream()
                    .mapToDouble(c -> c.getCharges().getTotal())
                    .sum();

            log.info("Successfully fetched charges for {} orders. Total charges: ₹{}",
                    chargesResponses.size(), String.format("%.2f", totalCharges));

            return chargesResponses;

        } catch (KiteException e) {
            log.error("KiteException in getOrderCharges: {}", e.getMessage(), e);
            throw e;
        } catch (Exception e) {
            log.error("Unexpected error fetching order charges: {}", e.getMessage(), e);
            throw new KiteException("Failed to fetch order charges: " + e.getMessage(), 500);
        }
    }

    /**
     * Build OrderParams from OrderRequest for placing new orders
     */
    private OrderParams buildOrderParams(OrderRequest orderRequest) {
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
        return orderParams;
    }

    /**
     * Build OrderParams from OrderRequest for modifying existing orders
     */
    private OrderParams buildModifyOrderParams(OrderRequest orderRequest) {
        OrderParams orderParams = new OrderParams();
        orderParams.quantity = orderRequest.getQuantity();
        orderParams.price = orderRequest.getPrice();
        orderParams.orderType = orderRequest.getOrderType();
        orderParams.triggerPrice = orderRequest.getTriggerPrice();
        orderParams.validity = orderRequest.getValidity();
        orderParams.disclosedQuantity = orderRequest.getDisclosedQuantity();
        return orderParams;
    }
}
