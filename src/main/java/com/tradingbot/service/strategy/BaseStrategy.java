package com.tradingbot.service.strategy;

import com.tradingbot.dto.OrderRequest;
import com.tradingbot.dto.StrategyRequest;
import com.tradingbot.service.TradingService;
import com.tradingbot.service.UnifiedTradingService;
import com.zerodhatech.kiteconnect.kitehttp.exceptions.KiteException;
import com.zerodhatech.models.Instrument;
import com.zerodhatech.models.LTPQuote;
import com.zerodhatech.models.Order;
import com.zerodhatech.models.Quote;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.stream.Collectors;

import static com.tradingbot.service.TradingConstants.*;

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

    // Constants for Black-Scholes calculation
    private static final double RISK_FREE_RATE = 0.05; // 5% annual risk-free rate (approximate)
//    private static final double VOLATILITY = 0.15; // 15% annualized volatility (approximate)

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
     * Calculate ATM strike based on delta values (nearest to ±0.5)
     * This method finds the strike where Call delta is closest to 0.5 (or Put delta closest to -0.5)
     *
     * @param spotPrice Current spot price
     * @param instrumentType Type of instrument (NIFTY, BANKNIFTY, FINNIFTY)
     * @param expiry Expiry date of the options
     * @return Strike price with delta nearest to ±0.5
     */
    protected double getATMStrikeByDelta(double spotPrice, String instrumentType, Date expiry) {
        double strikeInterval = getStrikeInterval(instrumentType);

        // Get all unique strikes near ATM (±5 strikes)
        double approximateATM = Math.round(spotPrice / strikeInterval) * strikeInterval;
        Set<Double> strikesToCheck = new HashSet<>();

        for (int i = -5; i <= 5; i++) {
            strikesToCheck.add(approximateATM + (i * strikeInterval));
        }

        // Calculate time to expiry in years
        double timeToExpiry = calculateTimeToExpiry(expiry);

        if (timeToExpiry <= 0) {
            log.warn("Time to expiry is zero or negative, falling back to simple ATM calculation");
            return approximateATM;
        }

        // Find strike with delta closest to 0.5 for Call options
        double bestStrike = approximateATM;
        double minDeltaDifference = Double.MAX_VALUE;

        for (Double strike : strikesToCheck) {
            double callDelta = calculateCallDelta(spotPrice, strike, timeToExpiry, instrumentType, expiry);
            double deltaDifference = Math.abs(callDelta - 0.5);

            log.debug("Strike: {}, Call Delta: {}, Delta Diff from 0.5: {}",
                    strike, callDelta, deltaDifference);

            if (deltaDifference < minDeltaDifference) {
                minDeltaDifference = deltaDifference;
                bestStrike = strike;
            }
        }

        log.info("Selected strike {} with delta nearest to 0.5 (difference: {})",
                bestStrike, minDeltaDifference);

        return bestStrike;
    }

    /**
     * Calculate time to expiry in years
     */
    private double calculateTimeToExpiry(Date expiry) {
        Calendar now = Calendar.getInstance();
        Calendar expiryCalendar = Calendar.getInstance();
        expiryCalendar.setTime(expiry);

        // Set to market close time (3:30 PM IST) for expiry day
        expiryCalendar.set(Calendar.HOUR_OF_DAY, 15);
        expiryCalendar.set(Calendar.MINUTE, 30);
        expiryCalendar.set(Calendar.SECOND, 0);

        long diffInMillis = expiryCalendar.getTimeInMillis() - now.getTimeInMillis();
        double diffInYears = diffInMillis / (1000.0 * 60 * 60 * 24 * 365.25);

        return Math.max(diffInYears, 0.0);
    }

    /**
     * Calculate Call option delta using Black-Scholes model
     * Delta = N(d1) where N is the cumulative normal distribution
     *
     * @param spotPrice Current spot price
     * @param strikePrice Strike price
     * @param timeToExpiry Time to expiry in years
     * @return Call option delta (0 to 1)
     */
    private double calculateCallDelta(double spotPrice, double strikePrice, double timeToExpiry, String instrumentType, Date expiry) {
        if (timeToExpiry <= 0) {
            return spotPrice >= strikePrice ? 1.0 : 0.0;
        }

        double optionPrice = getOptionPrice(instrumentType, strikePrice, expiry, "CE");
        if (optionPrice <= 0) {
            log.warn("Invalid option price ({}), unable to calculate delta for strike {}.", optionPrice, strikePrice);
            return 0.0; // Cannot calculate delta without a valid price.
        }

        // Calculate Implied Volatility
        double volatility = calculateImpliedVolatility(optionPrice, spotPrice, strikePrice, timeToExpiry, "CE");
        if (volatility <= 0 || Double.isNaN(volatility)) {
            log.warn("Invalid volatility ({}) calculated for strike {}. Using fallback.", volatility, strikePrice);
            volatility = 0.15; // Fallback to a default value if IV calculation fails
        }


        double d1 = calculateD1(spotPrice, strikePrice, timeToExpiry, volatility);
        log.info("Calculating Call Delta - Strike: {}, Spot: {}, TTE: {}, Vol: {}, d1: {}", strikePrice, spotPrice, timeToExpiry, volatility, d1);
        return cumulativeNormalDistribution(d1);
    }

    /**
     * Calculate d1 for Black-Scholes formula
     */
    private double calculateD1(double spotPrice, double strikePrice, double timeToExpiry, double volatility) {
        double numerator = Math.log(spotPrice / strikePrice) +
                (RISK_FREE_RATE + 0.5 * volatility * volatility) * timeToExpiry;
        double denominator = volatility * Math.sqrt(timeToExpiry);

        return numerator / denominator;
    }

    /**
     * Cumulative Normal Distribution function
     * Approximation using the error function
     */
    private double cumulativeNormalDistribution(double x) {
        // Using the approximation: N(x) = 0.5 * (1 + erf(x / sqrt(2)))
        return 0.5 * (1.0 + erf(x / Math.sqrt(2.0)));
    }

    /**
     * Error function approximation (Abramowitz and Stegun formula)
     */
    private double erf(double x) {
        // Constants
        double a1 =  0.254829592;
        double a2 = -0.284496736;
        double a3 =  1.421413741;
        double a4 = -1.453152027;
        double a5 =  1.061405429;
        double p  =  0.3275911;

        // Save the sign of x
        int sign = x < 0 ? -1 : 1;
        x = Math.abs(x);

        // A&S formula 7.1.26
        double t = 1.0 / (1.0 + p * x);
        double y = 1.0 - (((((a5 * t + a4) * t) + a3) * t + a2) * t + a1) * t * Math.exp(-x * x);

        return sign * y;
    }

    /**
     * Calculate Implied Volatility using Newton-Raphson method.
     *
     * @param marketPrice    The market price of the option.
     * @param spotPrice      The current price of the underlying asset.
     * @param strikePrice    The strike price of the option.
     * @param timeToExpiry   Time to expiration in years.
     * @param optionType     "CE" for Call or "PE" for Put.
     * @return The implied volatility.
     */
    private double calculateImpliedVolatility(double marketPrice, double spotPrice, double strikePrice, double timeToExpiry, String optionType) {
        final int MAX_ITERATIONS = 100;
        final double ACCURACY = 1.0e-5;
        double volatility = 0.5; // Initial guess

        for (int i = 0; i < MAX_ITERATIONS; i++) {
            double theoreticalPrice = calculateBlackScholesPrice(spotPrice, strikePrice, timeToExpiry, volatility, optionType);
            double vega = calculateVega(spotPrice, strikePrice, timeToExpiry, volatility);

            double difference = theoreticalPrice - marketPrice;

            if (Math.abs(difference) < ACCURACY) {
                return volatility;
            }

            if (vega == 0) { // Avoid division by zero
                break;
            }

            volatility -= difference / vega;
        }

        return volatility; // Return last calculated value even if accuracy not reached
    }

    /**
     * Calculate the theoretical price of an option using the Black-Scholes model.
     */
    private double calculateBlackScholesPrice(double spotPrice, double strikePrice, double timeToExpiry, double volatility, String optionType) {
        double d1 = calculateD1(spotPrice, strikePrice, timeToExpiry, volatility);
        double d2 = d1 - volatility * Math.sqrt(timeToExpiry);

        if ("CE".equalsIgnoreCase(optionType)) {
            return spotPrice * cumulativeNormalDistribution(d1) - strikePrice * Math.exp(-RISK_FREE_RATE * timeToExpiry) * cumulativeNormalDistribution(d2);
        } else if ("PE".equalsIgnoreCase(optionType)) {
            return strikePrice * Math.exp(-RISK_FREE_RATE * timeToExpiry) * cumulativeNormalDistribution(-d2) - spotPrice * cumulativeNormalDistribution(-d1);
        } else {
            return 0.0;
        }
    }

    /**
     * Calculate Vega, the sensitivity of option price to volatility.
     */
    private double calculateVega(double spotPrice, double strikePrice, double timeToExpiry, double volatility) {
        double d1 = calculateD1(spotPrice, strikePrice, timeToExpiry, volatility);
        return spotPrice * normalPDF(d1) * Math.sqrt(timeToExpiry);
    }

    /**
     * Standard Normal Probability Density Function.
     */
    private double normalPDF(double x) {
        return (1.0 / Math.sqrt(2.0 * Math.PI)) * Math.exp(-0.5 * x * x);
    }

    private double getOptionPrice(String instrumentType, double strike, Date expiry, String optionType) {
        log.info("Fetching option price for Instrument: {}, Strike: {}, Expiry: {}, Type: {}",
                instrumentType, strike, expiry, optionType);

        String instrumentIdentifier = "";
        try {
            // Format expiry date for trading symbol
            SimpleDateFormat sdf = new SimpleDateFormat("yyMMM");
            String expiryStr = sdf.format(expiry).toUpperCase();
            log.debug("Formatted expiry date string: {}", expiryStr);

            // Construct the trading symbol
            String tradingSymbol = instrumentType.toUpperCase() + expiryStr + (int) strike + optionType;
            String exchange = "NFO";
            instrumentIdentifier = exchange + ":" + tradingSymbol;
            log.info("Constructed instrument identifier: {}", instrumentIdentifier);

            // Fetch quote to get Implied Volatility
            log.debug("Fetching quote from trading service for: {}", instrumentIdentifier);
            Map<String, Quote> quotes = tradingService.getQuote(new String[]{instrumentIdentifier});
            Quote quote = quotes.get(instrumentIdentifier);

            if (quote != null) {
                log.debug("Successfully fetched quote for {}. OI: {}, Sell depth available: {}",
                        instrumentIdentifier, quote.oi, quote.depth != null && quote.depth.sell != null && !quote.depth.sell.isEmpty());

                if (quote.oi > 0 && quote.depth != null && quote.depth.sell != null && !quote.depth.sell.isEmpty()) {
                    double price = quote.lastPrice;
                    log.info("Returning option price: {} for {}", price, instrumentIdentifier);
                    return price;
                } else {
                    log.warn("Could not fetch valid price for {}. OI: {}, Depth: {}",
                            instrumentIdentifier, quote.oi, quote.depth);
                    return 0.0; // Fallback to 0
                }
            } else {
                log.warn("Could not fetch quote for {}. The quote object is null.", instrumentIdentifier);
                return 0.0; // Fallback to 0
            }
        } catch (KiteException e) {
            log.error("KiteException fetching option price for {}: {}", instrumentIdentifier, e.getMessage(), e);
            return 0.0; // Fallback on error
        } catch (Exception e) {
            log.error("Unexpected error fetching option price for {}: {}", instrumentIdentifier, e.getMessage(), e);
            return 0.0; // Fallback on error
        }
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
    protected int getLotSize(String instrumentType) throws KiteException {
        String instrumentKey = instrumentType.toUpperCase();

        // Check if lot size is already cached
        if (lotSizeCache.containsKey(instrumentKey)) {
            log.debug("Returning cached lot size for {}: {}", instrumentKey, lotSizeCache.get(instrumentKey));
            return lotSizeCache.get(instrumentKey);
        }

        // Fetch lot size from Kite instruments
        log.info("Fetching lot size from Kite API for instrument: {}", instrumentKey);

        try {
            List<Instrument> allInstruments = tradingService.getInstruments(EXCHANGE_NFO);

            String instrumentName = switch (instrumentKey) {
                case "NIFTY" -> INSTRUMENT_NIFTY;
                case "BANKNIFTY" -> INSTRUMENT_BANKNIFTY;
                case "FINNIFTY" -> INSTRUMENT_FINNIFTY;
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
     */
    protected int calculateOrderQuantity(StrategyRequest request) throws KiteException {
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

        List<Instrument> allInstruments = tradingService.getInstruments(EXCHANGE_NFO);

        String namePrefix = switch (instrumentType.toUpperCase()) {
            case "NIFTY" -> INSTRUMENT_NIFTY;
            case "BANKNIFTY" -> INSTRUMENT_BANKNIFTY;
            case "FINNIFTY" -> INSTRUMENT_FINNIFTY;
            default -> instrumentType.toUpperCase();
        };

        return allInstruments.stream()
                .filter(i -> i.name.equals(namePrefix))
                .filter(i -> OPTION_TYPE_CE.equals(i.instrument_type) || OPTION_TYPE_PE.equals(i.instrument_type))
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
                                             String orderType) {
        OrderRequest request = new OrderRequest();
        request.setTradingSymbol(symbol);
        request.setExchange(EXCHANGE_NFO);
        request.setTransactionType(transactionType);
        request.setQuantity(quantity);
        request.setProduct(PRODUCT_MIS);
        request.setOrderType(orderType);
        request.setPrice(null);
        request.setValidity(VALIDITY_DAY);
        return request;
    }

    /**
     * Get order price from order history
     */
    protected double getOrderPrice(String orderId) throws KiteException, IOException {
        List<Order> orderHistory = unifiedTradingService.getOrderHistory(orderId);
        log.info("Fetched order history for orderId {}: {} entries", orderId, orderHistory.size());
        if (!orderHistory.isEmpty()) {
            Order lastOrder = orderHistory.get(orderHistory.size() - 1);
            log.info("Last order status: {}, price: {}, averagePrice: {}", lastOrder.status, lastOrder.price, lastOrder.averagePrice);
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
