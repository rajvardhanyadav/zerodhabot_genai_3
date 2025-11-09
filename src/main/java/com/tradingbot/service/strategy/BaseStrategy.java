package com.tradingbot.service.strategy;

import com.tradingbot.dto.OrderRequest;
import com.tradingbot.dto.StrategyRequest;
import com.tradingbot.service.TradingService;
import com.tradingbot.service.UnifiedTradingService;
import com.zerodhatech.kiteconnect.kitehttp.exceptions.KiteException;
import com.zerodhatech.models.Instrument;
import com.zerodhatech.models.LTPQuote;
import com.zerodhatech.models.Order;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Abstract base class for trading strategies with common utility methods
 * Now supports both Paper Trading and Live Trading through UnifiedTradingService
 */
@Slf4j
@RequiredArgsConstructor
public abstract class BaseStrategy implements TradingStrategy {

    protected final TradingService tradingService;
    protected final UnifiedTradingService unifiedTradingService;
    protected final Map<String, Integer> lotSizeCache;

    /**
     * Get current spot price for the instrument
     */
    protected double getCurrentSpotPrice(String instrumentType) throws KiteException, IOException {
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
    protected double getATMStrike(double spotPrice, String instrumentType) {
        double strikeInterval = getStrikeInterval(instrumentType);
        return Math.round(spotPrice / strikeInterval) * strikeInterval;
    }

    /**
     * Get strike interval based on instrument
     */
    protected double getStrikeInterval(String instrumentType) {
        return switch (instrumentType.toUpperCase()) {
            case "NIFTY" -> 50.0;
            case "BANKNIFTY" -> 100.0;
            case "FINNIFTY" -> 50.0;
            default -> 50.0;
        };
    }

    /**
     * Get lot size for instrument by fetching from Kite API
     * Results are cached for the session to avoid repeated API calls
     */
    protected int getLotSize(String instrumentType) throws KiteException, IOException {
        String instrumentKey = instrumentType.toUpperCase();

        // Check if lot size is already cached
        if (lotSizeCache.containsKey(instrumentKey)) {
            log.debug("Returning cached lot size for {}: {}", instrumentKey, lotSizeCache.get(instrumentKey));
            return lotSizeCache.get(instrumentKey);
        }

        // Fetch lot size from Kite instruments
        log.info("Fetching lot size from Kite API for instrument: {}", instrumentKey);

        try {
            String exchange = "NFO";
            List<Instrument> allInstruments = tradingService.getInstruments(exchange);

            String instrumentName = switch (instrumentKey) {
                case "NIFTY" -> "NIFTY";
                case "BANKNIFTY" -> "BANKNIFTY";
                case "FINNIFTY" -> "FINNIFTY";
                default -> instrumentKey;
            };

            Optional<Instrument> instrument = allInstruments.stream()
                .filter(i -> i.name != null && i.name.equals(instrumentName))
                .filter(i -> i.lot_size > 0)
                .findFirst();

            if (instrument.isPresent()) {
                int lotSize = instrument.get().lot_size;
                log.info("Found lot size for {}: {}", instrumentKey, lotSize);
                lotSizeCache.put(instrumentKey, lotSize);
                return lotSize;
            } else {
                log.warn("Lot size not found in Kite API for {}, using fallback value", instrumentKey);
                int fallbackLotSize = getFallbackLotSize(instrumentKey);
                lotSizeCache.put(instrumentKey, fallbackLotSize);
                return fallbackLotSize;
            }

        } catch (Exception e) {
            log.error("Error fetching lot size from Kite API for {}: {}", instrumentKey, e.getMessage());
            log.warn("Using fallback lot size for {}", instrumentKey);
            int fallbackLotSize = getFallbackLotSize(instrumentKey);
            lotSizeCache.put(instrumentKey, fallbackLotSize);
            return fallbackLotSize;
        }
    }

    /**
     * Get fallback lot size when Kite API is unavailable
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
     * Calculate actual order quantity based on number of lots and instrument lot size.
     * This method ensures consistent quantity calculation across all strategies.
     *
     * @param request Strategy request containing the number of lots (or null for default 1 lot)
     * @return Actual quantity to trade (numberOfLots * lotSize)
     * @throws KiteException if error fetching lot size from Kite API
     * @throws IOException if network error occurs
     */
    protected int calculateOrderQuantity(StrategyRequest request) throws KiteException, IOException {
        int lotSize = getLotSize(request.getInstrumentType());
        int numberOfLots = request.getLots() != null ? request.getLots() : 1;
        int quantity = numberOfLots * lotSize;

        log.info("Order quantity calculation - Lots: {}, Lot Size: {}, Total Quantity: {}",
                numberOfLots, lotSize, quantity);

        return quantity;
    }

    /**
     * Get option instruments for given index and expiry
     */
    protected List<Instrument> getOptionInstruments(String instrumentType, String expiry)
            throws KiteException, IOException {

        String exchange = "NFO";
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
            return isNearestWeeklyExpiry(instrument.expiry);
        } else if (expiry.equalsIgnoreCase("MONTHLY")) {
            return isMonthlyExpiry(instrument.expiry);
        } else {
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

        if (cal.get(Calendar.DAY_OF_WEEK) != Calendar.THURSDAY) {
            return false;
        }

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

        if (cal.get(Calendar.DAY_OF_WEEK) != Calendar.THURSDAY) {
            return false;
        }

        cal.add(Calendar.DAY_OF_MONTH, 7);
        Calendar expiryCalendar = Calendar.getInstance();
        expiryCalendar.setTime(expiry);
        return cal.get(Calendar.MONTH) != expiryCalendar.get(Calendar.MONTH);
    }

    /**
     * Find option instrument by strike and type
     */
    protected Instrument findOptionInstrument(List<Instrument> instruments, double strike, String optionType) {
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
    protected OrderRequest createOrderRequest(String symbol, String transactionType, int quantity,
                                             String orderType, Double price) {
        OrderRequest request = new OrderRequest();
        request.setTradingSymbol(symbol);
        request.setExchange("NFO");
        request.setTransactionType(transactionType);
        request.setQuantity(quantity);
        request.setProduct("NRML");
        request.setOrderType(orderType);
        request.setPrice(price);
        request.setValidity("DAY");
        return request;
    }

    /**
     * Get order price from order history
     */
    protected double getOrderPrice(String orderId) throws KiteException, IOException {
        List<Order> orderHistory = unifiedTradingService.getOrderHistory(orderId);
        if (!orderHistory.isEmpty()) {
            Order lastOrder = orderHistory.get(orderHistory.size() - 1);

            if (lastOrder.averagePrice != null && !lastOrder.averagePrice.isEmpty()) {
                try {
                    return Double.parseDouble(lastOrder.averagePrice);
                } catch (NumberFormatException e) {
                    log.warn("Unable to parse average price: {}", lastOrder.averagePrice);
                }
            }

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
}
