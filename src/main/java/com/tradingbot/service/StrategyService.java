package com.tradingbot.service;

import com.tradingbot.dto.OrderRequest;
import com.tradingbot.dto.StrategyExecutionResponse;
import com.tradingbot.dto.StrategyRequest;
import com.tradingbot.model.StrategyExecution;
import com.tradingbot.model.StrategyType;
import com.zerodhatech.kiteconnect.kitehttp.exceptions.KiteException;
import com.zerodhatech.models.Instrument;
import com.zerodhatech.models.LTPQuote;
import com.zerodhatech.models.Order;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class StrategyService {

    private final TradingService tradingService;
    private final Map<String, StrategyExecution> activeStrategies = new ConcurrentHashMap<>();
    private final Map<String, Integer> lotSizeCache = new ConcurrentHashMap<>();

    /**
     * Execute a trading strategy
     */
    public StrategyExecutionResponse executeStrategy(StrategyRequest request) throws KiteException, IOException {
        log.error("Executing strategy: {} for instrument: {}", request.getStrategyType(), request.getInstrumentType());

        String executionId = UUID.randomUUID().toString();
        StrategyExecution execution = new StrategyExecution();
        execution.setExecutionId(executionId);
        execution.setStrategyType(request.getStrategyType());
        execution.setInstrumentType(request.getInstrumentType());
        execution.setExpiry(request.getExpiry());
        execution.setStatus("EXECUTING");
        execution.setTimestamp(System.currentTimeMillis());

        activeStrategies.put(executionId, execution);

        try {
            StrategyExecutionResponse response = switch (request.getStrategyType()) {
                case ATM_STRADDLE -> executeATMStraddle(request, executionId);
                case ATM_STRANGLE -> executeATMStrangle(request, executionId);
                default -> throw new IllegalArgumentException("Strategy not implemented: " + request.getStrategyType());
            };

            execution.setStatus("COMPLETED");
            execution.setMessage("Strategy executed successfully");
            return response;

        } catch (Exception e) {
            execution.setStatus("FAILED");
            execution.setMessage("Strategy execution failed: " + e.getMessage());
            log.error("Strategy execution failed", e);
            throw e;
        }
    }

    /**
     * Execute ATM Straddle Strategy
     * Buy 1 ATM Call + Buy 1 ATM Put
     */
    private StrategyExecutionResponse executeATMStraddle(StrategyRequest request, String executionId)
            throws KiteException, IOException {

        log.error("Executing ATM Straddle for {}", request.getInstrumentType());

        // Get current spot price
        double spotPrice = getCurrentSpotPrice(request.getInstrumentType());
        log.error("Current spot price: {}", spotPrice);

        // Calculate ATM strike
        double atmStrike = getATMStrike(spotPrice, request.getInstrumentType());
        log.error("ATM Strike: {}", atmStrike);

        // Get option instruments
        List<Instrument> instruments = getOptionInstruments(
            request.getInstrumentType(),
            request.getExpiry()
        );
        log.error("Found {} option instruments for {}", instruments.size(), request.getInstrumentType());

        // Find ATM Call and Put
        Instrument atmCall = findOptionInstrument(instruments, atmStrike, "CE");
        Instrument atmPut = findOptionInstrument(instruments, atmStrike, "PE");
        log.error("ATM Call: {}, ATM Put: {}", atmCall != null ? atmCall.tradingsymbol : "null", atmPut != null ? atmPut.tradingsymbol : "null");

        if (atmCall == null || atmPut == null) {
            throw new RuntimeException("ATM options not found for strike: " + atmStrike);
        }

        List<StrategyExecutionResponse.OrderDetail> orderDetails = new ArrayList<>();
        int quantity = request.getQuantity() != null ? request.getQuantity() : getLotSize(request.getInstrumentType());
        String orderType = request.getOrderType() != null ? request.getOrderType() : "MARKET";

        // Place Call order
        OrderRequest callOrder = createOrderRequest(atmCall.tradingsymbol, "BUY", quantity, orderType, null);
        var callOrderResponse = tradingService.placeOrder(callOrder);

        // Validate Call order response
        if (callOrderResponse == null || callOrderResponse.getOrderId() == null ||
            !"SUCCESS".equals(callOrderResponse.getStatus())) {
            String errorMsg = callOrderResponse != null ? callOrderResponse.getMessage() : "No response received";
            log.error("Call order placement failed: {}", errorMsg);
            throw new RuntimeException("Call order placement failed: " + errorMsg);
        }

        double callPrice = getOrderPrice(callOrderResponse.getOrderId());
        orderDetails.add(new StrategyExecutionResponse.OrderDetail(
            callOrderResponse.getOrderId(),
            atmCall.tradingsymbol,
            "CE",
            atmStrike,
            quantity,
            callPrice,
            "COMPLETED"
        ));

        // Place Put order
        OrderRequest putOrder = createOrderRequest(atmPut.tradingsymbol, "BUY", quantity, orderType, null);
        var putOrderResponse = tradingService.placeOrder(putOrder);

        // Validate Put order response
        if (putOrderResponse == null || putOrderResponse.getOrderId() == null ||
            !"SUCCESS".equals(putOrderResponse.getStatus())) {
            String errorMsg = putOrderResponse != null ? putOrderResponse.getMessage() : "No response received";
            log.error("Put order placement failed: {}", errorMsg);
            throw new RuntimeException("Put order placement failed: " + errorMsg);
        }

        double putPrice = getOrderPrice(putOrderResponse.getOrderId());
        orderDetails.add(new StrategyExecutionResponse.OrderDetail(
            putOrderResponse.getOrderId(),
            atmPut.tradingsymbol,
            "PE",
            atmStrike,
            quantity,
            putPrice,
            "COMPLETED"
        ));

        double totalPremium = (callPrice + putPrice) * quantity;

        StrategyExecutionResponse response = new StrategyExecutionResponse();
        response.setExecutionId(executionId);
        response.setStatus("COMPLETED");
        response.setMessage("ATM Straddle executed successfully");
        response.setOrders(orderDetails);
        response.setTotalPremium(totalPremium);
        response.setCurrentValue(totalPremium);
        response.setProfitLoss(0.0);
        response.setProfitLossPercentage(0.0);

        log.error("ATM Straddle executed. Total Premium: {}", totalPremium);
        return response;
    }

    /**
     * Execute ATM Strangle Strategy
     * Buy 1 OTM Call + Buy 1 OTM Put
     */
    private StrategyExecutionResponse executeATMStrangle(StrategyRequest request, String executionId)
            throws KiteException, IOException {

        log.error("Executing ATM Strangle for {}", request.getInstrumentType());

        // Get current spot price
        double spotPrice = getCurrentSpotPrice(request.getInstrumentType());
        log.error("Current spot price: {}", spotPrice);

        // Calculate ATM strike
        double atmStrike = getATMStrike(spotPrice, request.getInstrumentType());

        // Get strike gap (default: 100 for NIFTY, 200 for BANKNIFTY)
        double strikeGap = request.getStrikeGap() != null ? request.getStrikeGap() :
            getDefaultStrikeGap(request.getInstrumentType());

        double callStrike = atmStrike + strikeGap;
        double putStrike = atmStrike - strikeGap;

        log.error("Strangle Strikes - Call: {}, Put: {}", callStrike, putStrike);

        // Get option instruments
        List<Instrument> instruments = getOptionInstruments(
            request.getInstrumentType(),
            request.getExpiry()
        );

        // Find OTM Call and Put
        Instrument otmCall = findOptionInstrument(instruments, callStrike, "CE");
        Instrument otmPut = findOptionInstrument(instruments, putStrike, "PE");

        if (otmCall == null || otmPut == null) {
            throw new RuntimeException("OTM options not found for strikes: " + callStrike + ", " + putStrike);
        }

        List<StrategyExecutionResponse.OrderDetail> orderDetails = new ArrayList<>();
        int quantity = request.getQuantity() != null ? request.getQuantity() : getLotSize(request.getInstrumentType());
        String orderType = request.getOrderType() != null ? request.getOrderType() : "MARKET";

        // Place Call order
        OrderRequest callOrder = createOrderRequest(otmCall.tradingsymbol, "BUY", quantity, orderType, null);
        var callOrderResponse = tradingService.placeOrder(callOrder);

        // Validate Call order response
        if (callOrderResponse == null || callOrderResponse.getOrderId() == null ||
            !"SUCCESS".equals(callOrderResponse.getStatus())) {
            String errorMsg = callOrderResponse != null ? callOrderResponse.getMessage() : "No response received";
            log.error("Call order placement failed: {}", errorMsg);
            throw new RuntimeException("Call order placement failed: " + errorMsg);
        }

        double callPrice = getOrderPrice(callOrderResponse.getOrderId());
        orderDetails.add(new StrategyExecutionResponse.OrderDetail(
            callOrderResponse.getOrderId(),
            otmCall.tradingsymbol,
            "CE",
            callStrike,
            quantity,
            callPrice,
            "COMPLETED"
        ));

        // Place Put order
        OrderRequest putOrder = createOrderRequest(otmPut.tradingsymbol, "BUY", quantity, orderType, null);
        var putOrderResponse = tradingService.placeOrder(putOrder);

        // Validate Put order response
        if (putOrderResponse == null || putOrderResponse.getOrderId() == null ||
            !"SUCCESS".equals(putOrderResponse.getStatus())) {
            String errorMsg = putOrderResponse != null ? putOrderResponse.getMessage() : "No response received";
            log.error("Put order placement failed: {}", errorMsg);
            throw new RuntimeException("Put order placement failed: " + errorMsg);
        }

        double putPrice = getOrderPrice(putOrderResponse.getOrderId());
        orderDetails.add(new StrategyExecutionResponse.OrderDetail(
            putOrderResponse.getOrderId(),
            otmPut.tradingsymbol,
            "PE",
            putStrike,
            quantity,
            putPrice,
            "COMPLETED"
        ));

        double totalPremium = (callPrice + putPrice) * quantity;

        StrategyExecutionResponse response = new StrategyExecutionResponse();
        response.setExecutionId(executionId);
        response.setStatus("COMPLETED");
        response.setMessage("ATM Strangle executed successfully");
        response.setOrders(orderDetails);
        response.setTotalPremium(totalPremium);
        response.setCurrentValue(totalPremium);
        response.setProfitLoss(0.0);
        response.setProfitLossPercentage(0.0);

        log.error("ATM Strangle executed. Total Premium: {}", totalPremium);
        return response;
    }

    /**
     * Get current spot price for the instrument
     */
    private double getCurrentSpotPrice(String instrumentType) throws KiteException, IOException {
        String symbol = switch (instrumentType.toUpperCase()) {
            case "NIFTY" -> "NSE:NIFTY 50";
            case "BANKNIFTY" -> "NSE:NIFTY BANK";
            case "FINNIFTY" -> "NSE:NIFTY FIN SERVICE";
            default -> throw new IllegalArgumentException("Unsupported instrument: " + instrumentType);
        };

        Map<String, LTPQuote> ltp = tradingService.getLTP(new String[]{symbol});
        return ltp.get(symbol).lastPrice;
    }

    /**
     * Calculate ATM strike based on spot price
     */
    private double getATMStrike(double spotPrice, String instrumentType) {
        double strikeInterval = getStrikeInterval(instrumentType);
        return Math.round(spotPrice / strikeInterval) * strikeInterval;
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
     * Get default strike gap for strangle
     */
    private double getDefaultStrikeGap(String instrumentType) {
        return switch (instrumentType.toUpperCase()) {
            case "NIFTY" -> 100.0;
            case "BANKNIFTY" -> 200.0;
            case "FINNIFTY" -> 100.0;
            default -> 100.0;
        };
    }

    /**
     * Get lot size for instrument by fetching from Kite API
     * Results are cached for the session to avoid repeated API calls
     */
    private int getLotSize(String instrumentType) throws KiteException, IOException {
        String instrumentKey = instrumentType.toUpperCase();

        // Check if lot size is already cached
        if (lotSizeCache.containsKey(instrumentKey)) {
            log.debug("Returning cached lot size for {}: {}", instrumentKey, lotSizeCache.get(instrumentKey));
            return lotSizeCache.get(instrumentKey);
        }

        // Fetch lot size from Kite instruments
        log.info("Fetching lot size from Kite API for instrument: {}", instrumentKey);

        try {
            String exchange = "NFO"; // National Futures and Options exchange
            List<Instrument> allInstruments = tradingService.getInstruments(exchange);

            // Map instrument type to Kite instrument name
            String instrumentName = switch (instrumentKey) {
                case "NIFTY" -> "NIFTY";
                case "BANKNIFTY" -> "BANKNIFTY";
                case "FINNIFTY" -> "FINNIFTY";
                default -> instrumentKey;
            };

            // Find any instrument with this name to get the lot size
            // We filter for futures (FUT) as they have the same lot size as options
            Optional<Instrument> instrument = allInstruments.stream()
                .filter(i -> i.name != null && i.name.equals(instrumentName))
                .filter(i -> i.lot_size > 0)
                .findFirst();

            if (instrument.isPresent()) {
                int lotSize = instrument.get().lot_size;
                log.info("Found lot size for {}: {}", instrumentKey, lotSize);

                // Cache the lot size for future use
                lotSizeCache.put(instrumentKey, lotSize);

                return lotSize;
            } else {
                // If not found in API, use fallback values with warning
                log.warn("Lot size not found in Kite API for {}, using fallback value", instrumentKey);
                int fallbackLotSize = getFallbackLotSize(instrumentKey);
                lotSizeCache.put(instrumentKey, fallbackLotSize);
                return fallbackLotSize;
            }

        } catch (Exception e) {
            // If API call fails, use fallback values
            log.error("Error fetching lot size from Kite API for {}: {}", instrumentKey, e.getMessage());
            log.warn("Using fallback lot size for {}", instrumentKey);
            int fallbackLotSize = getFallbackLotSize(instrumentKey);
            lotSizeCache.put(instrumentKey, fallbackLotSize);
            return fallbackLotSize;
        }
    }

    /**
     * Get fallback lot size when Kite API is unavailable
     * These are approximate values and may not be accurate
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
     * Get option instruments for given index and expiry
     */
    private List<Instrument> getOptionInstruments(String instrumentType, String expiry)
            throws KiteException, IOException {

        String exchange = "NFO"; // National Futures and Options exchange
        List<Instrument> allInstruments = tradingService.getInstruments(exchange);

        String namePrefix = switch (instrumentType.toUpperCase()) {
            case "NIFTY" -> "NIFTY";
            case "BANKNIFTY" -> "BANKNIFTY";
            case "FINNIFTY" -> "FINNIFTY";
            default -> instrumentType.toUpperCase();
        };

        return allInstruments.stream()
            .filter(i -> i.name.equals(namePrefix))
            .filter(i -> i.instrument_type.equals("CE") || i.instrument_type.equals("PE"))
            .filter(i -> matchesExpiry(i, expiry))
            .collect(Collectors.toList());
    }

    /**
     * Check if instrument matches the expiry
     */
    private boolean matchesExpiry(Instrument instrument, String expiry) {
        if (expiry.equalsIgnoreCase("WEEKLY")) {
            // Get nearest weekly expiry (Thursday)
            return isNearestWeeklyExpiry(instrument.expiry);
        } else if (expiry.equalsIgnoreCase("MONTHLY")) {
            // Get monthly expiry (last Thursday)
            return isMonthlyExpiry(instrument.expiry);
        } else {
            // Match specific date
            SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd");
            return sdf.format(instrument.expiry).equals(expiry);
        }
    }

    /**
     * Check if the expiry is the nearest weekly expiry
     */
    private boolean isNearestWeeklyExpiry(Date expiry) {
        Calendar cal = Calendar.getInstance();
        cal.setTime(expiry);

        // Check if it's a Thursday
        if (cal.get(Calendar.DAY_OF_WEEK) != Calendar.THURSDAY) {
            return false;
        }

        // Check if it's within the next 7 days
        Calendar today = Calendar.getInstance();
        long diffInMillis = expiry.getTime() - today.getTimeInMillis();
        long diffInDays = diffInMillis / (24 * 60 * 60 * 1000);

        return diffInDays >= 0 && diffInDays <= 7;
    }

    /**
     * Check if the expiry is a monthly expiry
     */
    private boolean isMonthlyExpiry(Date expiry) {
        Calendar cal = Calendar.getInstance();
        cal.setTime(expiry);

        // Check if it's a Thursday
        if (cal.get(Calendar.DAY_OF_WEEK) != Calendar.THURSDAY) {
            return false;
        }

        // Check if it's the last Thursday of the month
        cal.add(Calendar.DAY_OF_MONTH, 7); // Move to next week

        // If next week is in a different month, current Thursday is the last Thursday
        Calendar expiryCalendar = Calendar.getInstance();
        expiryCalendar.setTime(expiry);
        return cal.get(Calendar.MONTH) != expiryCalendar.get(Calendar.MONTH);
    }

    /**
     * Find option instrument by strike and type
     */
    private Instrument findOptionInstrument(List<Instrument> instruments, double strike, String optionType) {
        return instruments.stream()
            .filter(i -> i.instrument_type.equals(optionType))
            .filter(i -> {
                try {
                    double instrumentStrike = Double.parseDouble(i.strike);
                    return Math.abs(instrumentStrike - strike) < 0.01;
                } catch (NumberFormatException e) {
                    return false;
                }
            })
            .findFirst()
            .orElse(null);
    }

    /**
     * Create order request
     */
    private OrderRequest createOrderRequest(String symbol, String transactionType, int quantity,
                                           String orderType, Double price) {
        OrderRequest request = new OrderRequest();
        request.setTradingSymbol(symbol);
        request.setExchange("NFO");
        request.setTransactionType(transactionType);
        request.setQuantity(quantity);
        request.setProduct("NRML"); // Normal (carry forward)
        request.setOrderType(orderType);
        request.setPrice(price);
        request.setValidity("DAY");
        return request;
    }

    /**
     * Get order price from order history
     */
    private double getOrderPrice(String orderId) throws KiteException, IOException {
        List<Order> orderHistory = tradingService.getOrderHistory(orderId);
        if (!orderHistory.isEmpty()) {
            Order lastOrder = orderHistory.get(orderHistory.size() - 1);

            // Try to get average price first (it's a String in Kite SDK)
            if (lastOrder.averagePrice != null && !lastOrder.averagePrice.isEmpty()) {
                try {
                    return Double.parseDouble(lastOrder.averagePrice);
                } catch (NumberFormatException e) {
                    log.warn("Unable to parse average price: {}", lastOrder.averagePrice);
                }
            }

            // Try to get price if average price is not available
            if (lastOrder.price != null && !lastOrder.price.isEmpty()) {
                try {
                    return Double.parseDouble(lastOrder.price);
                } catch (NumberFormatException e) {
                    log.warn("Unable to parse order price: {}", lastOrder.price);
                }
            }
        }
        return 0.0;
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
     * Get available expiry dates for an instrument
     */
    public List<String> getAvailableExpiries(String instrumentType) throws KiteException, IOException {
        log.info("Fetching available expiries for instrument: {}", instrumentType);

        String exchange = "NFO"; // Futures & Options exchange
        List<Instrument> allInstruments = tradingService.getInstruments(exchange);

        // Map instrument type to Kite instrument name
        String instrumentName = switch (instrumentType.toUpperCase()) {
            case "NIFTY" -> "NIFTY";
            case "BANKNIFTY" -> "BANKNIFTY";
            case "FINNIFTY" -> "FINNIFTY";
            default -> instrumentType.toUpperCase();
        };

        // Filter instruments for the given index and get unique expiry dates
        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd");

        List<String> expiries = allInstruments.stream()
            .filter(i -> i.name != null && i.name.equals(instrumentName))
            .filter(i -> i.expiry != null)
            .filter(i -> {
                // Only include future expiries (not expired)
                return i.expiry.after(new Date());
            })
            .map(i -> sdf.format(i.expiry))
            .distinct()
            .sorted()
            .collect(Collectors.toList());

        log.info("Found {} expiry dates for {}: {}", expiries.size(), instrumentType, expiries);

        if (expiries.isEmpty()) {
            log.warn("No expiries found for instrument: {}", instrumentType);
        }

        return expiries;
    }

    /**
     * Get available instruments with their details from Kite API
     * Returns instrument code, name, lot size, and strike interval
     */
    public List<InstrumentDetail> getAvailableInstruments() throws KiteException, IOException {
        log.info("Fetching available instruments from Kite API");

        List<InstrumentDetail> instrumentDetails = new ArrayList<>();

        // List of supported instruments
        String[] supportedInstruments = {"NIFTY", "BANKNIFTY", "FINNIFTY"};

        for (String instrumentCode : supportedInstruments) {
            try {
                // Fetch lot size using existing cached logic
                int lotSize = getLotSize(instrumentCode);

                // Get strike interval
                double strikeInterval = getStrikeInterval(instrumentCode);

                // Get display name
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
                // Continue with other instruments even if one fails
            }
        }

        log.info("Successfully fetched {} instruments", instrumentDetails.size());
        return instrumentDetails;
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
     * DTO for instrument details
     */
    public record InstrumentDetail(String code, String name, int lotSize, double strikeInterval) {}
}
