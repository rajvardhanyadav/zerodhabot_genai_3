package com.tradingbot.service.strategy;

import com.tradingbot.dto.OrderRequest;
import com.tradingbot.dto.StrategyRequest;
import com.tradingbot.service.TradingService;
import com.tradingbot.service.UnifiedTradingService;
import com.tradingbot.service.greeks.DeltaCacheService;
import com.zerodhatech.kiteconnect.kitehttp.exceptions.KiteException;
import com.zerodhatech.models.Instrument;
import com.zerodhatech.models.LTPQuote;
import com.zerodhatech.models.Order;
import com.zerodhatech.models.Quote;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static com.tradingbot.service.TradingConstants.*;

/**
 * Abstract base class for trading strategies with common utility methods
 * Now supports both Paper Trading and Live Trading through UnifiedTradingService
 *
 * HFT Optimization: Uses DeltaCacheService for pre-computed delta values
 * to reduce order placement latency from ~3-4 seconds to <100ms
 */
@Slf4j
public abstract class BaseStrategy implements TradingStrategy {

    protected final TradingService tradingService;
    protected final UnifiedTradingService unifiedTradingService;
    protected final Map<String, Integer> lotSizeCache;
    protected final DeltaCacheService deltaCacheService;
    private final Map<String, List<Instrument>> instrumentCache = new HashMap<>();

    // HFT: Flag to enable/disable cache usage (default: enabled)
    private static final boolean USE_DELTA_CACHE = true;
    private static final long CACHE_TIMEOUT_MS = 500; // Max wait for async cache refresh

    // Constants for Black-Scholes calculation
    private static final double RISK_FREE_RATE = 0.065; // Approximate annual risk-free rate (6.5%)
    private static final double DIVIDEND_YIELD = 0.0;   // Indices have near-zero dividend yield for short horizons
    private static final TimeZone IST = TimeZone.getTimeZone("Asia/Kolkata");

    /**
     * Constructor for BaseStrategy
     * @param tradingService Core trading service for API calls
     * @param unifiedTradingService Unified service for paper/live trading
     * @param lotSizeCache Cache for lot sizes
     * @param deltaCacheService Service for pre-computed delta values (can be null for backward compatibility)
     */
    protected BaseStrategy(TradingService tradingService,
                          UnifiedTradingService unifiedTradingService,
                          Map<String, Integer> lotSizeCache,
                          DeltaCacheService deltaCacheService) {
        this.tradingService = tradingService;
        this.unifiedTradingService = unifiedTradingService;
        this.lotSizeCache = lotSizeCache;
        this.deltaCacheService = deltaCacheService;
    }

    /**
     * Backward-compatible constructor (for subclasses not yet using DeltaCacheService)
     */
    protected BaseStrategy(TradingService tradingService,
                          UnifiedTradingService unifiedTradingService,
                          Map<String, Integer> lotSizeCache) {
        this(tradingService, unifiedTradingService, lotSizeCache, null);
    }

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
     * HFT OPTIMIZATION: Uses pre-computed delta cache when available.
     * Cache lookup is O(1) and reduces latency from ~3-4 seconds to <10ms.
     * Falls back to synchronous calculation only if cache is unavailable.
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

        // HFT OPTIMIZATION: Try cache first for instant response
        if (USE_DELTA_CACHE && deltaCacheService != null) {
            Optional<Double> cachedStrike = deltaCacheService.getCachedATMStrike(instrumentType, expiry);
            if (cachedStrike.isPresent()) {
                log.debug("Using cached ATM strike: {} (approximate ATM: {})", cachedStrike.get(), approximateATM);
                return cachedStrike.get();
            }

            // Cache miss - trigger async refresh and use simple ATM for this request
            log.debug("Delta cache miss for {}/{}. Using simple ATM: {}", instrumentType, expiry, approximateATM);

            // Fire async cache refresh (non-blocking)
            deltaCacheService.calculateAndCacheATMStrike(instrumentType, expiry, spotPrice)
                    .orTimeout(CACHE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                    .exceptionally(ex -> {
                        log.trace("Async delta calculation timed out or failed: {}", ex.getMessage());
                        return approximateATM;
                    });

            // Return simple ATM immediately (HFT priority: speed over precision)
            // The next order will benefit from the cached value
            return approximateATM;
        }

        // Fallback to synchronous calculation if cache service not available
        return getATMStrikeByDeltaSynchronous(spotPrice, instrumentType, expiry, approximateATM, strikeInterval);
    }

    /**
     * Synchronous delta calculation - used when cache is disabled or unavailable.
     * This is the original implementation preserved for backward compatibility.
     */
    private double getATMStrikeByDeltaSynchronous(double spotPrice, String instrumentType, Date expiry,
                                                   double approximateATM, double strikeInterval) {
        Set<Double> strikesToCheck = new LinkedHashSet<>();
        for (int i = -5; i <= 5; i++) {
            strikesToCheck.add(approximateATM + i * strikeInterval);
        }

        double timeToExpiry = calculateTimeToExpiryPrecise(expiry);
        if (timeToExpiry <= 0) {
            log.warn("Time to expiry is zero or negative. Falling back to approximate ATM strike: {}", approximateATM);
            return approximateATM;
        }

        log.info("Calculating ATM strike by delta for spot: {}, approximate ATM: {}, expiry: {}, T: {} years",
                spotPrice, approximateATM, expiry, String.format("%.6f", timeToExpiry));

        Map<Double, Double> deltas = computeCallDeltas(instrumentType, expiry, spotPrice, strikesToCheck, timeToExpiry);

        if (deltas.isEmpty()) {
            log.warn("Delta computation failed for all strikes. Falling back to simple ATM strike: {}", approximateATM);
            return approximateATM;
        }

        double bestStrike = deltas.entrySet().stream()
                .min(Comparator.comparingDouble(entry -> Math.abs(entry.getValue() - 0.5)))
                .map(Map.Entry::getKey)
                .orElse(approximateATM);

        log.info("Selected strike {} (Δ ≈ {}) as ATM strike.", bestStrike, String.format("%.4f", deltas.getOrDefault(bestStrike, 0.0)));
        return bestStrike;
    }

    private Map<Double, Double> computeCallDeltas(String instrumentType, Date expiry, double spotPrice, Set<Double> strikes, double timeToExpiry) {
        // 1. Fetch mid prices for all strikes
        Map<Double, MidPrices> midPriceMap = strikes.stream()
                .collect(Collectors.toMap(s -> s, s -> getBothMidPrices(instrumentType, s, expiry)));

        // 2. Calculate a stable forward price using put-call parity from liquid strikes
        double forwardPrice = calculateImpliedForwardPrice(spotPrice, midPriceMap, timeToExpiry);
        log.info("Using implied forward price: {}", String.format("%.2f", forwardPrice));

        // 3. Compute delta for each strike
        Map<Double, Double> deltas = new HashMap<>();
        for (double strike : strikes) {
            MidPrices prices = midPriceMap.get(strike);
            if (prices == null) {
                log.warn("Skipping strike {} as no mid prices were found.", strike);
                continue;
            }
            if (!prices.valid()) {
                log.warn("Skipping strike {} due to invalid mid prices (CE: {}, PE: {})", strike, prices.callMid, prices.putMid);
                continue;
            }

            // Calculate strike-specific IV
            double iv = solveIVForwardPerStrike(prices.callMid, forwardPrice, strike, timeToExpiry);
            if (Double.isNaN(iv) || iv <= 1e-4 || iv > 3.0) {
                log.warn("Unreliable IV ({}) calculated for strike {}. Skipping.", String.format("%.4f", iv), strike);
                continue;
            }

            // Calculate delta using the forward model: Delta = N(d1)
            double d1 = (Math.log(forwardPrice / strike) + 0.5 * iv * iv * timeToExpiry) / (iv * Math.sqrt(timeToExpiry));
            double delta = cumulativeNormalDistribution(d1);

            // Round to 2 decimal places to match convention
            double roundedDelta = Math.round(delta * 100.0) / 100.0;
            deltas.put(strike, delta);

            log.debug("Strike: {}, IV: {}, d1: {}, Delta: {}, Rounded Delta: {}",strike, iv, d1, delta, roundedDelta);
        }
        return deltas;
    }

    private double calculateImpliedForwardPrice(double spotPrice, Map<Double, MidPrices> midPriceMap, double timeToExpiry) {
        List<Double> forwardEstimates = new ArrayList<>();
        // F = K + e^(rT) * (C - P)
        double discountFactor = Math.exp(RISK_FREE_RATE * timeToExpiry);

        for (Map.Entry<Double, MidPrices> entry : midPriceMap.entrySet()) {
            double strike = entry.getKey();
            MidPrices prices = entry.getValue();
            if (prices.valid()) {
                double forward = strike + (prices.callMid - prices.putMid) * discountFactor;
                forwardEstimates.add(forward);
            }
        }

        if (forwardEstimates.isEmpty()) {
            log.warn("Could not calculate any forward price estimates from market data. Falling back to formula.");
            return spotPrice * Math.exp((RISK_FREE_RATE - DIVIDEND_YIELD) * timeToExpiry);
        }

        // Return the median for robustness
        Collections.sort(forwardEstimates);
        int size = forwardEstimates.size();
        if (size % 2 == 1) {
            return forwardEstimates.get(size / 2);
        } else {
            return (forwardEstimates.get(size / 2 - 1) + forwardEstimates.get(size / 2)) / 2.0;
        }
    }

    // Helper container for CE/PE mid prices
    private static final class MidPrices {
        final Double callMid;
        final Double putMid;

        MidPrices(Double c, Double p) {
            this.callMid = c;
            this.putMid = p;
        }

        boolean valid() {
            return callMid != null && callMid > 0 && putMid != null && putMid > 0;
        }
    }

    // Fetch both CE and PE mid prices for a strike
    private MidPrices getBothMidPrices(String instrumentType, double strike, Date expiry) {
        log.info("Fetching mid prices for Strike: {}, Expiry: {}", strike, expiry);
        Double ce = getOptionMidPrice(instrumentType, strike, expiry, "CE");
        Double pe = getOptionMidPrice(instrumentType, strike, expiry, "PE");
        log.debug("Fetched mid prices for Strike: {}, CE: {}, PE: {}", strike, ce, pe);
        return new MidPrices(ce, pe);
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
        if (diffInMillis <= 0) return 0.0;

        // Use 365.2425 days per year for better accuracy
        return diffInMillis / (365.2425 * 24.0 * 60.0 * 60.0 * 1000.0);
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
        log.info("Fetching mid price for instrument: {}", instrumentIdentifier);
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
     * Build instrument identifier by finding the correct instrument from the Kite API.
     * This is more reliable than manually constructing the symbol.
     */
    private String buildOptionInstrumentIdentifier(String instrumentType, double strike, Date expiry, String optionType) {
        try {
            Instrument optionInstrument = findOptionInstrumentByStrike(instrumentType, strike, expiry, optionType);

            if (optionInstrument != null) {
                String identifier = optionInstrument.exchange + ":" + optionInstrument.tradingsymbol;
                log.debug("Successfully built identifier for strike {}: {}", strike, identifier);
                return identifier;
            } else {
                log.error("Could not find instrument for {} strike {} expiry {}. Falling back to manual build.", instrumentType, strike, expiry);
                return buildOptionInstrumentIdentifierManually(instrumentType, strike, expiry, optionType);
            }
        } catch (Exception e) {
            log.error("Exception finding instrument for {} strike {}: {}. Falling back to manual build.", instrumentType, strike, e.getMessage(), e);
            return buildOptionInstrumentIdentifierManually(instrumentType, strike, expiry, optionType);
        } catch (KiteException e) {
            throw new RuntimeException(e);
        }
    }

    private Instrument findOptionInstrumentByStrike(String instrumentType, double strike, Date expiry, String optionType) throws KiteException, IOException {
        List<Instrument> instrumentsForExpiry = getInstrumentsForExpiry(expiry);
        String underlyingName = getUnderlyingName(instrumentType);

        return instrumentsForExpiry.stream()
                .filter(i -> underlyingName.equals(i.name))
                .filter(i -> optionType.equals(i.instrument_type))
                .filter(i -> {
                    try {
                        return Math.abs(Double.parseDouble(i.strike) - strike) < 0.01;
                    } catch (NumberFormatException e) {
                        return false;
                    }
                })
                .findFirst()
                .orElse(null);
    }

    private List<Instrument> getInstrumentsForExpiry(Date expiry) throws KiteException, IOException {
        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd");
        sdf.setTimeZone(IST);
        String expiryKey = sdf.format(expiry);

        synchronized (instrumentCache) {
            if (instrumentCache.containsKey(expiryKey)) {
                return instrumentCache.get(expiryKey);
            }

            List<Instrument> allNfoInstruments = tradingService.getInstruments(EXCHANGE_NFO);
            Map<String, List<Instrument>> instrumentsByExpiry = allNfoInstruments.stream()
                    .filter(i -> i.getExpiry() != null)
                    .collect(Collectors.groupingBy(i -> sdf.format(i.getExpiry())));

            instrumentCache.putAll(instrumentsByExpiry);

            return instrumentCache.getOrDefault(expiryKey, Collections.emptyList());
        }
    }

    private String getUnderlyingName(String instrumentType) {
        return switch (instrumentType.toUpperCase()) {
            case "NIFTY" -> INSTRUMENT_NIFTY;
            case "BANKNIFTY" -> INSTRUMENT_BANKNIFTY;
            case "FINNIFTY" -> INSTRUMENT_FINNIFTY;
            default -> instrumentType.toUpperCase();
        };
    }

    /**
     * (Fallback) Manually build instrument identifier like NFO:NIFTY25NOV19650CE.
     */
    private String buildOptionInstrumentIdentifierManually(String instrumentType, double strike, Date expiry, String optionType) {
        SimpleDateFormat sdf = new SimpleDateFormat("yyMMM");
        sdf.setTimeZone(IST);
        String expiryStr = sdf.format(expiry).toUpperCase();
        String tradingSymbol = instrumentType.toUpperCase() + expiryStr + (int) strike + optionType.toUpperCase();
        log.warn("Using manually constructed identifier (fallback): {}", tradingSymbol);
        return "NFO:" + tradingSymbol;
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
     * Find option instrument by strike and type.
     * HFT Optimized: Uses indexed loop instead of streams for minimum latency.
     *
     * @param instruments List of instruments to search
     * @param strike Target strike price
     * @param optionType Option type (CE or PE)
     * @return Matching instrument or null if not found
     */
    protected Instrument findOptionInstrument(List<Instrument> instruments, double strike, String optionType) {
        // HFT: Use indexed loop to avoid stream/iterator overhead
        final int size = instruments.size();
        for (int i = 0; i < size; i++) {
            final Instrument inst = instruments.get(i);
            if (optionType.equals(inst.instrument_type)) {
                try {
                    final double instrumentStrike = Double.parseDouble(inst.strike);
                    if (Math.abs(instrumentStrike - strike) < 0.01) {
                        return inst;
                    }
                } catch (NumberFormatException e) {
                    // Skip invalid instruments
                }
            }
        }
        return null;
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
     * Standard Normal Probability Density Function.
     */
    private double normalPDF(double x) {
        return (1.0 / Math.sqrt(2.0 * Math.PI)) * Math.exp(-0.5 * x * x);
    }

    public void exitAllLegs(String executionId){

    }
}
