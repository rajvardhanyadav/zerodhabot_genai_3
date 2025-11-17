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
    private static final double RISK_FREE_RATE = 0.065; // Approximate annual risk-free rate (6.5%)
    private static final double DIVIDEND_YIELD = 0.0;   // Indices have near-zero dividend yield for short horizons
    private static final TimeZone IST = TimeZone.getTimeZone("Asia/Kolkata");

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
     * Implementation notes to match Kite Zerodha:
     * - Use Black-Scholes with dividend yield (q) where d1 uses (r - q)
     * - Delta(Call) = e^{-qT} * N(d1)
     * - Time to expiry uses IST up to 15:30 on expiry day
     * - Implied Volatility is solved from option mid-price (best bid/ask avg), fallback to LTP
     *
     * @param spotPrice Current spot price
     * @param instrumentType Type of instrument (NIFTY, BANKNIFTY, FINNIFTY)
     * @param expiry Expiry date of the options
     * @return Strike price with delta nearest to ±0.5
     */
    protected double getATMStrikeByDelta(double spotPrice, String instrumentType, Date expiry) {
        double strikeInterval = getStrikeInterval(instrumentType);
        double approximateATM = Math.round(spotPrice / strikeInterval) * strikeInterval;
        Set<Double> strikesToCheck = new LinkedHashSet<>();
        for (int i = -5; i <= 5; i++) strikesToCheck.add(approximateATM + i * strikeInterval);
        double T = calculateTimeToExpiry(expiry);
        if (T <= 0) return approximateATM;

        // Use improved delta computation
        Map<Double, Double> deltas = computeCallDeltas(instrumentType, expiry, spotPrice, strikesToCheck);
        if (deltas.isEmpty()) {
            log.warn("Delta computation failed, fallback to simple ATM strike");
            return approximateATM;
        }
        double bestStrike = approximateATM; double minDiff = Double.MAX_VALUE;
        for (Map.Entry<Double, Double> e : deltas.entrySet()) {
            double diff = Math.abs(e.getValue() - 0.5);
            log.debug("Strike: {}, Forward-based Call Δ: {} |Δ-0.5|: {}", e.getKey(), e.getValue(), String.format("%.4f", diff));
            if (diff < minDiff) {minDiff = diff; bestStrike = e.getKey();}
        }
        log.info("Selected strike {} (Δ = {}) using forward-based method", bestStrike, deltas.get(bestStrike));
        return bestStrike;
    }

    // Helper container for CE/PE mid prices
    private static final class MidPrices {
        final Double callMid; final Double putMid; MidPrices(Double c, Double p){this.callMid=c;this.putMid=p;}
        boolean valid(){return callMid!=null && callMid>0 && putMid!=null && putMid>0;}
    }

    // Fetch both CE and PE mid prices for a strike
    private MidPrices getBothMidPrices(String instrumentType, double strike, Date expiry) {
        Double ce = getOptionMidPrice(instrumentType, strike, expiry, "CE");
        Double pe = getOptionMidPrice(instrumentType, strike, expiry, "PE");
        return new MidPrices(ce, pe);
    }

    // Improved delta computation leveraging put-call parity and per-strike IV
    private Map<Double, Double> computeCallDeltas(String instrumentType, Date expiry, double spotPrice, Set<Double> strikes) {
        double T = calculateTimeToExpiryPrecise(expiry);
        if (T <= 0) return Collections.emptyMap();

        Map<Double, MidPrices> midMap = new HashMap<>();
        for (Double k : strikes) midMap.put(k, getBothMidPrices(instrumentType, k, expiry));

        // Filter strikes with valid prices
        List<Double> liquidStrikes = midMap.entrySet().stream()
                .filter(e -> e.getValue().valid())
                .map(Map.Entry::getKey)
                .sorted()
                .toList();
        if (liquidStrikes.isEmpty()) return Collections.emptyMap();

        // Calculate forward using put-call parity: C - P = (F - K) * e^(-rT)
        // Rearranged: F = K + (C - P) * e^(rT)
        // Use median of nearby strikes (within ±2 intervals from spot) for stability
        double strikeInterval = getStrikeInterval(instrumentType);
        List<Double> nearbyForwards = new ArrayList<>();
        for (Double k : liquidStrikes) {
            if (Math.abs(k - spotPrice) <= 2 * strikeInterval) {
                MidPrices mp = midMap.get(k);
                double Fk = k + (mp.callMid - mp.putMid) * Math.exp(RISK_FREE_RATE * T);
                nearbyForwards.add(Fk);
            }
        }

        // Use median forward for robustness
        Collections.sort(nearbyForwards);
        double forward = nearbyForwards.isEmpty() ? spotPrice :
                        nearbyForwards.size() % 2 == 0 ?
                        (nearbyForwards.get(nearbyForwards.size()/2 - 1) + nearbyForwards.get(nearbyForwards.size()/2)) / 2.0 :
                        nearbyForwards.get(nearbyForwards.size()/2);

        log.debug("Forward price: {}, Spot: {}, T: {} years", forward, spotPrice, T);

        // Compute delta for each strike using per-strike IV
        Map<Double, Double> deltas = new HashMap<>();
        for (Double k : strikes) {
            MidPrices mp = midMap.get(k);
            if (!mp.valid()) continue;

            // Solve IV for this specific strike using its call price
            double strikeIV = solveIVForwardPerStrike(mp.callMid, forward, k, T);
            if (Double.isNaN(strikeIV) || strikeIV <= 0 || strikeIV > 3.0) {
                log.debug("Invalid IV {} for strike {}, skipping", strikeIV, k);
                continue;
            }

            // Calculate d1 using forward price: d1 = [ln(F/K) + 0.5*σ²*T] / (σ*√T)
            double sqrtT = Math.sqrt(T);
            double d1 = (Math.log(forward / k) + 0.5 * strikeIV * strikeIV * T) / (strikeIV * sqrtT);

            // Delta = N(d1) for forward-based pricing (no dividend adjustment needed in d1)
            double callDelta = cumulativeNormalDistribution(d1);

            // Truncate delta to 2 decimal places (floor) to match Kite Zerodha format
            double roundedDelta = Math.floor(callDelta * 100.0) / 100.0;

            deltas.put(k, roundedDelta);
            log.debug("Strike: {}, CE: {}, PE: {}, IV: {}, d1: {}, Delta: {}",
                     k, mp.callMid, mp.putMid, strikeIV, d1, roundedDelta);
        }
        return deltas;
    }

    // Calculate time to expiry with second-level precision
    private double calculateTimeToExpiryPrecise(Date expiry) {
        Calendar now = Calendar.getInstance(IST);
        Calendar expiryCalendar = Calendar.getInstance(IST);
        expiryCalendar.setTime(expiry);
        expiryCalendar.set(Calendar.HOUR_OF_DAY, 15);
        expiryCalendar.set(Calendar.MINUTE, 30);
        expiryCalendar.set(Calendar.SECOND, 0);
        expiryCalendar.set(Calendar.MILLISECOND, 0);

        long diffInMillis = expiryCalendar.getTimeInMillis() - now.getTimeInMillis();
        // Use 365.2422 days per year for precision
        double diffInYears = diffInMillis / (365.2422 * 24.0 * 60.0 * 60.0 * 1000.0);
        return Math.max(diffInYears, 0.0);
    }

    // IV solver for a specific strike using forward pricing
    // Call price formula: C = e^(-rT) * [F * N(d1) - K * N(d2)]
    private double solveIVForwardPerStrike(double callPrice, double forward, double strike, double T) {
        if (T <= 0 || callPrice <= 0) return 0.0;

        double discountFactor = Math.exp(-RISK_FREE_RATE * T);
        double intrinsic = Math.max(0, forward - strike) * discountFactor;

        // If call price is at intrinsic, IV is very low
        if (callPrice <= intrinsic * 1.001) return 0.01;

        double sigma = 0.2; // initial guess
        double sqrtT = Math.sqrt(T);

        for (int i = 0; i < 100; i++) {
            double d1 = (Math.log(forward / strike) + 0.5 * sigma * sigma * T) / (sigma * sqrtT);
            double d2 = d1 - sigma * sqrtT;

            double nd1 = cumulativeNormalDistribution(d1);
            double nd2 = cumulativeNormalDistribution(d2);

            // Theoretical call price
            double theoreticalPrice = discountFactor * (forward * nd1 - strike * nd2);

            // Vega (derivative with respect to sigma)
            double vega = discountFactor * forward * normalPDF(d1) * sqrtT;

            double priceDiff = theoreticalPrice - callPrice;

            // Check convergence
            if (Math.abs(priceDiff) < 0.0001) {
                return sigma;
            }

            // Avoid division by very small vega
            if (vega < 1e-10) break;

            // Newton-Raphson step with damping factor 0.5 for stability
            double step = priceDiff / vega;
            sigma = sigma - 0.5 * step;

            // Keep sigma in reasonable bounds
            sigma = Math.max(0.01, Math.min(3.0, sigma));
        }

        return sigma;
    }

    /**
     * Compute mid price for an option using Quote depth; fallback to last traded price.
     * Returns null if quote unavailable or price invalid.
     */
    private Double getOptionMidPrice(String instrumentType, double strike, Date expiry, String optionType) {
        String instrumentIdentifier = buildOptionInstrumentIdentifier(instrumentType, strike, expiry, optionType);
        try {
            Map<String, Quote> quotes;
            try {
                quotes = tradingService.getQuote(new String[]{instrumentIdentifier});
            } catch (KiteException ke) {
                log.warn("KiteException fetching quote for {}: {}", instrumentIdentifier, ke.getMessage());
                return null;
            }
            Quote quote = quotes != null ? quotes.get(instrumentIdentifier) : null;
            if (quote == null) return null;
            try {
                Double bestBid = null, bestAsk = null;
                if (quote.depth != null) {
                    if (quote.depth.buy != null && !quote.depth.buy.isEmpty() && quote.depth.buy.get(0) != null) {
                        try { bestBid = quote.depth.buy.get(0).getPrice(); } catch (Throwable t) {
                            try { bestBid = (Double) quote.depth.buy.get(0).getClass().getField("price").get(quote.depth.buy.get(0)); } catch (Throwable ignore) {}
                        }
                    }
                    if (quote.depth.sell != null && !quote.depth.sell.isEmpty() && quote.depth.sell.get(0) != null) {
                        try { bestAsk = quote.depth.sell.get(0).getPrice(); } catch (Throwable t) {
                            try { bestAsk = (Double) quote.depth.sell.get(0).getClass().getField("price").get(quote.depth.sell.get(0)); } catch (Throwable ignore) {}
                        }
                    }
                }
                if (bestBid != null && bestAsk != null && bestBid > 0 && bestAsk > 0) {
                    return (bestBid + bestAsk) / 2.0;
                }
                if (quote.lastPrice > 0) return quote.lastPrice;
            } catch (Exception ignored) {
            }
        } catch (Exception e) {
            log.warn("Failed to fetch quote for {}: {}", instrumentIdentifier, e.getMessage());
        }
        return null;
    }

    /**
     * Build instrument identifier like NFO:NIFTY25NOV19650CE
     */
    private String buildOptionInstrumentIdentifier(String instrumentType, double strike, Date expiry, String optionType) {
        SimpleDateFormat sdf = new SimpleDateFormat("yyMMM");
        sdf.setTimeZone(IST);
        String expiryStr = sdf.format(expiry).toUpperCase();
        String tradingSymbol = instrumentType.toUpperCase() + expiryStr + (int) strike + optionType.toUpperCase();
        return "NFO:" + tradingSymbol;
    }

    /**
     * Calculate time to expiry in years
     */
    private double calculateTimeToExpiry(Date expiry) {
        Calendar now = Calendar.getInstance(IST);
        Calendar expiryCalendar = Calendar.getInstance(IST);
        expiryCalendar.setTime(expiry);

        // Set to market close time (3:30 PM IST) for expiry day
        expiryCalendar.set(Calendar.HOUR_OF_DAY, 15);
        expiryCalendar.set(Calendar.MINUTE, 30);
        expiryCalendar.set(Calendar.SECOND, 0);
        expiryCalendar.set(Calendar.MILLISECOND, 0);

        long diffInMillis = expiryCalendar.getTimeInMillis() - now.getTimeInMillis();
        double diffInYears = diffInMillis / (1000.0 * 60 * 60 * 24 * 365.0);
        return Math.max(diffInYears, 0.0);
    }

    /**
     * Calculate Call option delta using Black-Scholes model with dividend yield (q)
     * Delta(Call) = e^{-qT} * N(d1)
     *
     * @param spotPrice Current spot price
     * @param strikePrice Strike price
     * @param timeToExpiry Time to expiry in years
     * @return Call option delta (0 to 1)
     */
    private double calculateCallDelta(double spotPrice, double strikePrice, double timeToExpiry, double volatility) {
        if (timeToExpiry <= 0) {
            return spotPrice >= strikePrice ? 1.0 : 0.0;
        }
        double d1 = calculateD1(spotPrice, strikePrice, timeToExpiry, volatility);
        return Math.exp(-DIVIDEND_YIELD * timeToExpiry) * cumulativeNormalDistribution(d1);
    }

    /**
     * Calculate d1 for Black-Scholes formula with dividend yield (q)
     */
    private double calculateD1(double spotPrice, double strikePrice, double timeToExpiry, double volatility) {
        double numerator = Math.log(spotPrice / strikePrice) +
                (RISK_FREE_RATE - DIVIDEND_YIELD + 0.5 * volatility * volatility) * timeToExpiry;
        double denominator = Math.max(volatility, 1e-9) * Math.sqrt(Math.max(timeToExpiry, 1e-9));
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
     * Uses Black-Scholes with dividend yield (q). Bounds and damping are applied for stability.
     */
    private double calculateImpliedVolatility(double marketPrice, double spotPrice, double strikePrice, double timeToExpiry, String optionType) {
        final int MAX_ITERATIONS = 100;
        final double ACCURACY = 1.0e-5;
        double sigma = 0.2; // initial guess

        // Clamp market price to avoid arbitrage edge cases
        if (optionType.equalsIgnoreCase("CE")) {
            double intrinsic = Math.max(0.0, spotPrice - strikePrice);
            marketPrice = Math.max(marketPrice, intrinsic * 0.999);
        } else {
            double intrinsic = Math.max(0.0, strikePrice - spotPrice);
            marketPrice = Math.max(marketPrice, intrinsic * 0.999);
        }

        for (int i = 0; i < MAX_ITERATIONS; i++) {
            double theoreticalPrice = calculateBlackScholesPrice(spotPrice, strikePrice, timeToExpiry, sigma, optionType);
            double vega = calculateVega(spotPrice, strikePrice, timeToExpiry, sigma);
            double diff = theoreticalPrice - marketPrice;

            if (Math.abs(diff) < ACCURACY) {
                return Math.max(1e-6, Math.min(sigma, 5.0));
            }
            if (vega < 1e-8) {
                break; // can't proceed
            }
            // Damped Newton step
            double step = diff / vega;
            sigma = Math.max(1e-6, Math.min(5.0, sigma - 0.7 * step));
        }
        return Math.max(1e-6, Math.min(sigma, 5.0));
    }

    /**
     * Calculate the theoretical price of an option using the Black-Scholes model with dividend yield (q).
     */
    private double calculateBlackScholesPrice(double spotPrice, double strikePrice, double timeToExpiry, double volatility, String optionType) {
        double d1 = calculateD1(spotPrice, strikePrice, timeToExpiry, volatility);
        double d2 = d1 - volatility * Math.sqrt(timeToExpiry);
        double discountR = Math.exp(-RISK_FREE_RATE * timeToExpiry);
        double discountQ = Math.exp(-DIVIDEND_YIELD * timeToExpiry);

        if ("CE".equalsIgnoreCase(optionType)) {
            return spotPrice * discountQ * cumulativeNormalDistribution(d1)
                    - strikePrice * discountR * cumulativeNormalDistribution(d2);
        } else if ("PE".equalsIgnoreCase(optionType)) {
            return strikePrice * discountR * cumulativeNormalDistribution(-d2)
                    - spotPrice * discountQ * cumulativeNormalDistribution(-d1);
        } else {
            return 0.0;
        }
    }

    /**
     * Calculate Vega (sensitivity to volatility) with dividend yield adjustment.
     */
    private double calculateVega(double spotPrice, double strikePrice, double timeToExpiry, double volatility) {
        double d1 = calculateD1(spotPrice, strikePrice, timeToExpiry, volatility);
        double discountQ = Math.exp(-DIVIDEND_YIELD * timeToExpiry);
        return spotPrice * discountQ * normalPDF(d1) * Math.sqrt(timeToExpiry);
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
            sdf.setTimeZone(IST);
            String expiryStr = sdf.format(expiry).toUpperCase();
            log.debug("Formatted expiry date string: {}", expiryStr);

            // Construct the trading symbol
            String tradingSymbol = instrumentType.toUpperCase() + expiryStr + (int) strike + optionType;
            String exchange = "NFO";
            instrumentIdentifier = exchange + ":" + tradingSymbol;
            log.info("Constructed instrument identifier: {}", instrumentIdentifier);

            // Fetch quote to get price
            log.debug("Fetching quote from trading service for: {}", instrumentIdentifier);
            Map<String, Quote> quotes = tradingService.getQuote(new String[]{instrumentIdentifier});
            Quote quote = quotes.get(instrumentIdentifier);

            if (quote != null) {
                log.debug("Successfully fetched quote for {}. OI: {}, Depth available: {}",
                        instrumentIdentifier, quote.oi, quote.depth != null);
                if (quote.lastPrice > 0) {
                    double price = quote.lastPrice;
                    log.info("Returning option price: {} for {}", price, instrumentIdentifier);
                    return price;
                } else {
                    log.warn("Invalid last price for {}. Returning 0.", instrumentIdentifier);
                    return 0.0;
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
