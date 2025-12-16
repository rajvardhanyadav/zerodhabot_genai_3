package com.tradingbot.service;

import com.tradingbot.config.KiteConfig;
import com.tradingbot.dto.BasketOrderRequest;
import com.tradingbot.dto.BasketOrderResponse;
import com.tradingbot.dto.OrderChargesResponse;
import com.tradingbot.dto.OrderRequest;
import com.tradingbot.dto.OrderResponse;
import com.tradingbot.service.session.UserSessionManager;
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
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicLong;

import static com.tradingbot.service.TradingConstants.*;

@Service
@RequiredArgsConstructor
@Slf4j
public class TradingService {

    private final KiteConfig kiteConfig;
    private final UserSessionManager sessionManager;
    private final RateLimiterService rateLimiterService;

    // ============ INSTRUMENTS CACHE ============
    // Kite instruments API has strict rate limits (1 req/sec).
    // Instrument data is static for the trading day, so we cache it.
    // Cache TTL: 5 minutes (instruments don't change during the day)
    private static final long INSTRUMENTS_CACHE_TTL_MS = 5 * 60 * 1000; // 5 minutes
    private final Map<String, List<Instrument>> instrumentsCache = new ConcurrentHashMap<>();
    private final Map<String, AtomicLong> instrumentsCacheTimestamp = new ConcurrentHashMap<>();
    private final Object instrumentsCacheLock = new Object();

    /**
     * Generate login URL for Kite Connect authentication
     */
    public String getLoginUrl() {
        log.debug("Generating Kite Connect login URL");
        // Use a fresh KiteConnect with API key; no access token needed for login URL
        KiteConnect temp = new KiteConnect(kiteConfig.getApiKey());
        String loginUrl = temp.getLoginURL();
        log.debug("Login URL generated: {}", loginUrl);
        return loginUrl;
    }

    /**
     * Generate session using request token for the current user
     */
    public User generateSession(String requestToken) throws KiteException, IOException {
        log.info("Generating session with request token (header optional)");
        User user = sessionManager.createSessionFromRequestToken(requestToken);
        log.info("Session generated successfully for user: {}", user.userId);
        return user;
    }

    private KiteConnect kc() {
        return sessionManager.getRequiredKiteForCurrentUser();
    }

    /**
     * Get user profile
     */
    public Profile getUserProfile() throws KiteException, IOException {
        log.debug("Fetching user profile");
        if (!rateLimiterService.acquire(RateLimiterService.ApiType.PROFILE)) {
            throw new RateLimiterService.RateLimitExceededException("Rate limit exceeded for getUserProfile. Please retry.");
        }
        Profile profile = kc().getProfile();
        log.debug("User profile fetched for user: {}", profile.userName);
        return profile;
    }

    /**
     * Get account margins
     */
    public Margin getMargins(String segment) throws KiteException, IOException {
        log.debug("Fetching margins for segment: {}", segment);
        if (!rateLimiterService.acquire(RateLimiterService.ApiType.MARGINS)) {
            throw new RateLimiterService.RateLimitExceededException("Rate limit exceeded for getMargins. Please retry.");
        }
        Margin margin = kc().getMargins(segment);
        log.debug("Margins fetched - Available: {}, Used: {}", margin.available.cash, margin.utilised.debits);
        return margin;
    }

    /**
     * Place a new order
     */
    public OrderResponse placeOrder(OrderRequest orderRequest) throws KiteException, IOException {
        log.info("Placing order - Symbol: {}, Type: {}, Qty: {}",
            orderRequest.getTradingSymbol(), orderRequest.getTransactionType(), orderRequest.getQuantity());

        // Rate limit check
        if (!rateLimiterService.acquire(RateLimiterService.ApiType.ORDER)) {
            log.warn("Rate limit exceeded for order placement - Symbol: {}", orderRequest.getTradingSymbol());
            throw new RateLimiterService.RateLimitExceededException("Rate limit exceeded for order placement. Please retry.");
        }

        OrderParams orderParams = buildOrderParams(orderRequest);
        Order order = kc().placeOrder(orderParams, VARIETY_REGULAR);

        // Check if order was placed successfully
        if (order != null && order.orderId != null && !order.orderId.isEmpty()) {
            log.info("Order placed successfully: {} - {} {} {} @ {}",
                order.orderId, order.transactionType, order.quantity, order.tradingSymbol, order.orderType);
            return new OrderResponse(order.orderId, STATUS_SUCCESS, MSG_ORDER_PLACED_SUCCESS);
        } else {
            log.error("Order placement failed - no order ID returned");
            throw new IllegalStateException(ERR_ORDER_PLACEMENT_FAILED_NO_ID);
        }
    }

    // HFT: Dedicated thread pool for parallel order placement
    private static final ExecutorService ORDER_EXECUTOR = java.util.concurrent.Executors.newFixedThreadPool(4, r -> {
        Thread t = new Thread(r, "hft-order-placer");
        t.setDaemon(true);
        t.setPriority(Thread.MAX_PRIORITY);
        return t;
    });

    /**
     * Place basket order - HFT OPTIMIZED with parallel order placement.
     * Places all orders in the basket concurrently and returns consolidated results.
     * Note: Kite Connect SDK doesn't support atomic basket placement, so orders are placed in parallel.
     *
     * HFT Optimization: Parallel order submission reduces latency from ~2x single order to ~1x.
     */
    public BasketOrderResponse placeBasketOrder(BasketOrderRequest basketRequest) {
        log.info("Placing basket order with {} orders, tag: {}",
                basketRequest.getOrders() != null ? basketRequest.getOrders().size() : 0,
                basketRequest.getTag());

        if (basketRequest.getOrders() == null || basketRequest.getOrders().isEmpty()) {
            log.error("Basket order request has no orders");
            return BasketOrderResponse.builder()
                    .status(STATUS_FAILED)
                    .message("No orders in basket request")
                    .totalOrders(0)
                    .successCount(0)
                    .failureCount(0)
                    .orderResults(new ArrayList<>())
                    .build();
        }

        final List<BasketOrderRequest.BasketOrderItem> orderItems = basketRequest.getOrders();
        final int orderCount = orderItems.size();

        // HFT: Use CompletableFuture for parallel order placement
        // CRITICAL: Capture user context before async execution (executor threads don't inherit context)
        final String capturedUserId = com.tradingbot.util.CurrentUserContext.getUserId();
        List<java.util.concurrent.CompletableFuture<BasketOrderResponse.BasketOrderResult>> futures =
            new ArrayList<>(orderCount);

        for (BasketOrderRequest.BasketOrderItem item : orderItems) {
            // Wrap task with user context to ensure executor threads have access to session
            java.util.concurrent.Callable<BasketOrderResponse.BasketOrderResult> wrappedTask =
                com.tradingbot.util.CurrentUserContext.wrapWithContext(() -> placeSingleBasketOrderItem(item));
            futures.add(java.util.concurrent.CompletableFuture.supplyAsync(() -> {
                try {
                    return wrappedTask.call();
                } catch (Exception e) {
                    log.error("Error in basket order item execution: {}", e.getMessage(), e);
                    return BasketOrderResponse.BasketOrderResult.builder()
                            .status(STATUS_FAILED)
                            .message("Execution error: " + e.getMessage())
                            .build();
                }
            }, ORDER_EXECUTOR));
        }

        // Wait for all orders to complete (blocking but all orders run in parallel)
        List<BasketOrderResponse.BasketOrderResult> results = new ArrayList<>(orderCount);
        int successCount = 0;
        int failureCount = 0;

        for (java.util.concurrent.CompletableFuture<BasketOrderResponse.BasketOrderResult> future : futures) {
            try {
                BasketOrderResponse.BasketOrderResult result = future.join();
                results.add(result);
                if (STATUS_SUCCESS.equals(result.getStatus())) {
                    successCount++;
                } else {
                    failureCount++;
                }
            } catch (Exception e) {
                log.error("Error waiting for order completion: {}", e.getMessage());
                failureCount++;
            }
        }

        String overallStatus = determineOverallStatus(successCount, failureCount, orderCount);
        String message = String.format("Basket order completed: %d/%d orders successful", successCount, orderCount);

        log.info("Basket order completed - Status: {}, Success: {}, Failed: {}",
                overallStatus, successCount, failureCount);

        return BasketOrderResponse.builder()
                .status(overallStatus)
                .message(message)
                .totalOrders(orderCount)
                .successCount(successCount)
                .failureCount(failureCount)
                .orderResults(results)
                .build();
    }

    /**
     * HFT: Place a single basket order item - extracted for parallel execution.
     * Includes rate limiting to prevent exceeding API limits.
     */
    private BasketOrderResponse.BasketOrderResult placeSingleBasketOrderItem(BasketOrderRequest.BasketOrderItem item) {
        try {
            // Rate limit check for order placement
            if (!rateLimiterService.acquire(RateLimiterService.ApiType.ORDER)) {
                log.warn("Rate limit exceeded for basket order item: {}", item.getTradingSymbol());
                return BasketOrderResponse.BasketOrderResult.builder()
                        .tradingSymbol(item.getTradingSymbol())
                        .legType(item.getLegType())
                        .status(STATUS_FAILED)
                        .message("Rate limit exceeded. Please retry.")
                        .instrumentToken(item.getInstrumentToken())
                        .build();
            }

            OrderParams orderParams = buildOrderParamsFromBasketItem(item);
            Order order = kc().placeOrder(orderParams, VARIETY_REGULAR);

            if (order != null && order.orderId != null && !order.orderId.isEmpty()) {
                log.info("Basket order item placed successfully: {} - {} {} {}",
                        order.orderId, item.getTransactionType(), item.getQuantity(), item.getTradingSymbol());

                return BasketOrderResponse.BasketOrderResult.builder()
                        .orderId(order.orderId)
                        .tradingSymbol(item.getTradingSymbol())
                        .legType(item.getLegType())
                        .status(STATUS_SUCCESS)
                        .message(MSG_ORDER_PLACED_SUCCESS)
                        .instrumentToken(item.getInstrumentToken())
                        .build();
            } else {
                log.error("Basket order item failed - no order ID returned for {}", item.getTradingSymbol());
                return BasketOrderResponse.BasketOrderResult.builder()
                        .tradingSymbol(item.getTradingSymbol())
                        .legType(item.getLegType())
                        .status(STATUS_FAILED)
                        .message(ERR_ORDER_PLACEMENT_FAILED_NO_ID)
                        .instrumentToken(item.getInstrumentToken())
                        .build();
            }
        } catch (KiteException e) {
            log.error("Kite API error for {}: {}", item.getTradingSymbol(), e.message);
            return BasketOrderResponse.BasketOrderResult.builder()
                    .tradingSymbol(item.getTradingSymbol())
                    .legType(item.getLegType())
                    .status(STATUS_FAILED)
                    .message(ERR_ORDER_PLACEMENT_FAILED + e.message)
                    .instrumentToken(item.getInstrumentToken())
                    .build();
        } catch (IOException e) {
            log.error("Network error for {}: {}", item.getTradingSymbol(), e.getMessage());
            return BasketOrderResponse.BasketOrderResult.builder()
                    .tradingSymbol(item.getTradingSymbol())
                    .legType(item.getLegType())
                    .status(STATUS_FAILED)
                    .message(ERR_NETWORK + e.getMessage())
                    .instrumentToken(item.getInstrumentToken())
                    .build();
        }
    }

    private String determineOverallStatus(int successCount, int failureCount, int totalOrders) {
        if (successCount == totalOrders) {
            return STATUS_SUCCESS;
        } else if (successCount > 0) {
            return "PARTIAL";
        } else {
            return STATUS_FAILED;
        }
    }

    /**
     * Build OrderParams from BasketOrderItem
     */
    private OrderParams buildOrderParamsFromBasketItem(BasketOrderRequest.BasketOrderItem item) {
        OrderParams orderParams = new OrderParams();
        orderParams.tradingsymbol = item.getTradingSymbol();
        orderParams.exchange = item.getExchange();
        orderParams.transactionType = item.getTransactionType();
        orderParams.quantity = item.getQuantity();
        orderParams.product = item.getProduct();
        orderParams.orderType = item.getOrderType();
        orderParams.price = item.getPrice();
        orderParams.triggerPrice = item.getTriggerPrice();
        orderParams.validity = item.getValidity();
        orderParams.disclosedQuantity = item.getDisclosedQuantity();
        return orderParams;
    }

    /**
     * Modify an existing order
     */
    public OrderResponse modifyOrder(String orderId, OrderRequest orderRequest) throws KiteException, IOException {
        log.info("Modifying order: {} - New params: {}", orderId, orderRequest);
        OrderParams orderParams = buildModifyOrderParams(orderRequest);
        Order order = kc().modifyOrder(orderId, orderParams, VARIETY_REGULAR);
        log.info("Order modified successfully: {}", orderId);

        return new OrderResponse(order.orderId, STATUS_SUCCESS, MSG_ORDER_MODIFIED_SUCCESS);
    }

    /**
     * Cancel an order
     */
    public OrderResponse cancelOrder(String orderId) throws KiteException, IOException {
        log.info("Cancelling order: {}", orderId);
        if (!rateLimiterService.acquire(RateLimiterService.ApiType.ORDER)) {
            throw new RateLimiterService.RateLimitExceededException("Rate limit exceeded for cancelOrder. Please retry.");
        }
        Order order = kc().cancelOrder(orderId, VARIETY_REGULAR);

        // Check if order was cancelled successfully
        if (order != null && order.orderId != null && !order.orderId.isEmpty()) {
            log.info("Order cancelled successfully: {}", orderId);
            return new OrderResponse(order.orderId, STATUS_SUCCESS, MSG_ORDER_CANCELLED_SUCCESS);
        } else {
            log.error("Order cancellation failed for orderId: {}", orderId);
            throw new IllegalStateException(ERR_ORDER_CANCELLATION_FAILED);
        }
    }

    /**
     * Get all orders for the day
     */
    public List<Order> getOrders() throws KiteException, IOException {
        log.debug("Fetching all orders for the day");
        if (!rateLimiterService.acquire(RateLimiterService.ApiType.ORDERS)) {
            throw new RateLimiterService.RateLimitExceededException("Rate limit exceeded for getOrders. Please retry.");
        }
        List<Order> orders = kc().getOrders();
        log.debug("Fetched {} orders", orders != null ? orders.size() : 0);
        return orders;
    }

    /**
     * Get order history
     */
    public List<Order> getOrderHistory(String orderId) throws KiteException, IOException {
        log.debug("Fetching order history for order: {}", orderId);
        if (!rateLimiterService.acquire(RateLimiterService.ApiType.ORDERS)) {
            throw new RateLimiterService.RateLimitExceededException("Rate limit exceeded for getOrderHistory. Please retry.");
        }
        List<Order> history = kc().getOrderHistory(orderId);
        log.debug("Fetched {} order history records for order: {}", history != null ? history.size() : 0, orderId);
        return history;
    }

    /**
     * Get all trades for the day
     */
    public List<Trade> getTrades() throws KiteException, IOException {
        log.debug("Fetching all trades for the day");
        if (!rateLimiterService.acquire(RateLimiterService.ApiType.ORDERS)) {
            throw new RateLimiterService.RateLimitExceededException("Rate limit exceeded for getTrades. Please retry.");
        }
        List<Trade> trades = kc().getTrades();
        log.debug("Fetched {} trades", trades != null ? trades.size() : 0);
        return trades;
    }

    /**
     * Get all positions
     */
    public Map<String, List<Position>> getPositions() throws KiteException, IOException {
        log.debug("Fetching all positions");
        if (!rateLimiterService.acquire(RateLimiterService.ApiType.POSITIONS)) {
            throw new RateLimiterService.RateLimitExceededException("Rate limit exceeded for getPositions. Please retry.");
        }
        Map<String, List<Position>> positions = kc().getPositions();
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
        if (!rateLimiterService.acquire(RateLimiterService.ApiType.HOLDINGS)) {
            throw new RateLimiterService.RateLimitExceededException("Rate limit exceeded for getHoldings. Please retry.");
        }
        List<Holding> holdings = kc().getHoldings();
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
        JSONObject result = kc().convertPosition(tradingSymbol, exchange, transactionType,
                positionType, oldProduct, newProduct, quantity);
        log.info("Position conversion completed for symbol: {}", tradingSymbol);
        return result;
    }

    /**
     * Get quote for instruments
     */
    public Map<String, Quote> getQuote(String[] instruments) throws KiteException, IOException {
        log.debug("Fetching quotes for {} instruments", instruments.length);
        Map<String, Quote> quotes = kc().getQuote(instruments);
        log.debug("Fetched quotes for {} instruments", quotes != null ? quotes.size() : 0);
        return quotes;
    }

    /**
     * Get OHLC data
     */
    public Map<String, OHLCQuote> getOHLC(String[] instruments) throws KiteException, IOException {
        log.debug("Fetching OHLC data for {} instruments", instruments.length);
        Map<String, OHLCQuote> ohlc = kc().getOHLC(instruments);
        log.debug("Fetched OHLC data for {} instruments", ohlc != null ? ohlc.size() : 0);
        return ohlc;
    }

    /**
     * Get LTP (Last Traded Price)
     */
    public Map<String, LTPQuote> getLTP(String[] instruments) throws KiteException, IOException {
        log.debug("Fetching LTP for {} instruments", instruments.length);
        Map<String, LTPQuote> ltp = kc().getLTP(instruments);
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
        HistoricalData data = kc().getHistoricalData(fromDate, toDate, instrumentToken, interval, continuous, oi);
        log.debug("Fetched {} candles of historical data", data != null && data.dataArrayList != null ? data.dataArrayList.size() : 0);
        return data;
    }

    /**
     * Get all instruments (with caching to avoid 429 rate limit errors).
     *
     * Kite's instruments API has a strict rate limit. Since instrument data is static
     * for the trading day, we cache the response for 5 minutes.
     *
     * @see <a href="https://kite.trade/docs/connect/v3/exceptions/">Kite API Rate Limits</a>
     */
    public List<Instrument> getInstruments() throws KiteException, IOException {
        return getInstrumentsCached("ALL");
    }

    /**
     * Get instruments for specific exchange (with caching to avoid 429 rate limit errors).
     *
     * Kite's instruments API has a strict rate limit. Since instrument data is static
     * for the trading day, we cache the response for 5 minutes.
     *
     * @see <a href="https://kite.trade/docs/connect/v3/exceptions/">Kite API Rate Limits</a>
     */
    public List<Instrument> getInstruments(String exchange) throws KiteException, IOException {
        return getInstrumentsCached(exchange);
    }

    /**
     * Internal method to get instruments with caching.
     * Uses synchronized block to prevent multiple concurrent API calls for the same exchange.
     */
    private List<Instrument> getInstrumentsCached(String cacheKey) throws KiteException, IOException {
        // Check if cache is valid
        AtomicLong timestampHolder = instrumentsCacheTimestamp.get(cacheKey);
        if (timestampHolder != null) {
            long cacheAge = System.currentTimeMillis() - timestampHolder.get();
            if (cacheAge < INSTRUMENTS_CACHE_TTL_MS) {
                List<Instrument> cached = instrumentsCache.get(cacheKey);
                if (cached != null) {
                    log.debug("Returning cached instruments for {}: {} instruments (cache age: {}ms)",
                            cacheKey, cached.size(), cacheAge);
                    return cached;
                }
            }
        }

        // Cache miss or expired - fetch from API with synchronization to prevent concurrent calls
        synchronized (instrumentsCacheLock) {
            // Double-check after acquiring lock (another thread might have populated cache)
            timestampHolder = instrumentsCacheTimestamp.get(cacheKey);
            if (timestampHolder != null) {
                long cacheAge = System.currentTimeMillis() - timestampHolder.get();
                if (cacheAge < INSTRUMENTS_CACHE_TTL_MS) {
                    List<Instrument> cached = instrumentsCache.get(cacheKey);
                    if (cached != null) {
                        log.debug("Returning cached instruments for {} (populated by another thread): {} instruments",
                                cacheKey, cached.size());
                        return cached;
                    }
                }
            }

            // Acquire rate limit permit
            log.info("Fetching instruments from Kite API for exchange: {} (cache miss/expired)", cacheKey);
            if (!rateLimiterService.acquire(RateLimiterService.ApiType.INSTRUMENTS)) {
                throw new RateLimiterService.RateLimitExceededException(
                        "Rate limit exceeded for getInstruments. Please retry.");
            }

            // Fetch from API
            List<Instrument> instruments;
            if ("ALL".equals(cacheKey)) {
                instruments = kc().getInstruments();
            } else {
                instruments = kc().getInstruments(cacheKey);
            }

            // Update cache
            if (instruments != null) {
                instrumentsCache.put(cacheKey, instruments);
                instrumentsCacheTimestamp.put(cacheKey, new AtomicLong(System.currentTimeMillis()));
                log.info("Cached {} instruments for exchange: {} (TTL: {}ms)",
                        instruments.size(), cacheKey, INSTRUMENTS_CACHE_TTL_MS);
            }

            return instruments;
        }
    }

    /**
     * Clear instruments cache. Call this if you need fresh data.
     */
    public void clearInstrumentsCache() {
        instrumentsCache.clear();
        instrumentsCacheTimestamp.clear();
        log.info("Instruments cache cleared");
    }

    /**
     * Get GTT (Good Till Triggered) orders
     */
    public List<GTT> getGTTs() throws KiteException, IOException {
        log.debug("Fetching GTT orders");
        List<GTT> gtts = kc().getGTTs();
        log.debug("Fetched {} GTT orders", gtts != null ? gtts.size() : 0);
        return gtts;
    }

    /**
     * Place GTT order
     */
    public GTT placeGTT(GTTParams gttParams) throws KiteException, IOException {
        log.info("Placing GTT order - Type: {}, Symbol: {}", gttParams.triggerType, gttParams.tradingsymbol);
        if (!rateLimiterService.acquire(RateLimiterService.ApiType.GTT)) {
            throw new RateLimiterService.RateLimitExceededException("Rate limit exceeded for placeGTT. Please retry.");
        }
        GTT gtt = kc().placeGTT(gttParams);
        log.info("GTT order placed successfully - ID: {}", gtt.id);
        return gtt;
    }

    /**
     * Get GTT order by ID
     */
    public GTT getGTT(int triggerId) throws KiteException, IOException {
        log.debug("Fetching GTT order: {}", triggerId);
        if (!rateLimiterService.acquire(RateLimiterService.ApiType.GTT)) {
            throw new RateLimiterService.RateLimitExceededException("Rate limit exceeded for getGTT. Please retry.");
        }
        GTT gtt = kc().getGTT(triggerId);
        log.debug("Fetched GTT order: {} - Status: {}", triggerId, gtt.status);
        return gtt;
    }

    /**
     * Modify GTT order
     */
    public GTT modifyGTT(int triggerId, GTTParams gttParams) throws KiteException, IOException {
        log.info("Modifying GTT order: {}", triggerId);
        if (!rateLimiterService.acquire(RateLimiterService.ApiType.GTT)) {
            throw new RateLimiterService.RateLimitExceededException("Rate limit exceeded for modifyGTT. Please retry.");
        }
        GTT gtt = kc().modifyGTT(triggerId, gttParams);
        log.info("GTT order modified successfully: {}", triggerId);
        return gtt;
    }

    /**
     * Cancel GTT order
     */
    public GTT cancelGTT(int triggerId) throws KiteException, IOException {
        log.info("Cancelling GTT order: {}", triggerId);
        if (!rateLimiterService.acquire(RateLimiterService.ApiType.GTT)) {
            throw new RateLimiterService.RateLimitExceededException("Rate limit exceeded for cancelGTT. Please retry.");
        }
        GTT gtt = kc().cancelGTT(triggerId);
        log.info("GTT order cancelled successfully: {}", triggerId);
        return gtt;
    }

    /**
     * Get order charges for orders placed today
     */
    public List<OrderChargesResponse> getOrderCharges() throws KiteException {
        try {
            if (!rateLimiterService.acquire(RateLimiterService.ApiType.ORDERS)) {
                throw new RateLimiterService.RateLimitExceededException("Rate limit exceeded for getOrderCharges. Please retry.");
            }
            // Get all orders for the day
            List<Order> orders = kc().getOrders();

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
            List<ContractNote> contractNotes = kc().getVirtualContractNote(contractNoteParamsList);

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
