package com.tradingbot.controller;

import com.tradingbot.dto.ApiResponse;
import com.tradingbot.service.InstrumentCacheService;
import com.tradingbot.service.MarketDataEngine;
import com.tradingbot.service.TradingService;
import com.zerodhatech.kiteconnect.kitehttp.exceptions.KiteException;
import com.zerodhatech.models.*;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.Date;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/market")
@Slf4j
@Tag(name = "Market Data", description = "Market data and quotes endpoints")
public class MarketDataController {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    private final TradingService tradingService;
    private final MarketDataEngine marketDataEngine;
    private final InstrumentCacheService instrumentCacheService;

    public MarketDataController(TradingService tradingService, MarketDataEngine marketDataEngine,
                                InstrumentCacheService instrumentCacheService) {
        this.tradingService = tradingService;
        this.marketDataEngine = marketDataEngine;
        this.instrumentCacheService = instrumentCacheService;
    }

    // ==================== MARKET DATA ENGINE ENDPOINTS ====================

    @GetMapping("/engine/status")
    @Operation(summary = "Get MarketDataEngine status and cache statistics",
               description = "Returns current engine state, cache hit/miss rates, per-instrument spot prices, " +
                       "pre-computed ATM strikes, delta values, VWAP, and data freshness metrics.")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Engine status fetched")
    })
    public ResponseEntity<ApiResponse<Map<String, Object>>> getEngineStatus() {
        Map<String, Object> stats = marketDataEngine.getCacheStats();
        return ResponseEntity.ok(ApiResponse.success(stats));
    }

    @GetMapping("/engine/spot/{instrumentType}")
    @Operation(summary = "Get cached spot price from MarketDataEngine",
               description = "Returns the pre-computed spot price from the engine cache. " +
                       "Returns 404 if cache is cold or stale.")
    public ResponseEntity<ApiResponse<Double>> getCachedSpotPrice(
            @PathVariable String instrumentType) {
        return marketDataEngine.getIndexPrice(instrumentType)
                .map(price -> ResponseEntity.ok(ApiResponse.success(price)))
                .orElse(ResponseEntity.status(HttpStatus.NOT_FOUND)
                        .body(ApiResponse.error("Spot price not in cache for: " + instrumentType)));
    }

    @GetMapping("/engine/atm-strike/{instrumentType}")
    @Operation(summary = "Get pre-computed ATM strike from MarketDataEngine",
               description = "Returns the pre-computed delta-based ATM strike. " +
                       "This is the value strategy execution uses — zero latency.")
    public ResponseEntity<ApiResponse<Double>> getCachedATMStrike(
            @PathVariable String instrumentType) {
        return marketDataEngine.getPrecomputedATMStrike(instrumentType)
                .map(strike -> ResponseEntity.ok(ApiResponse.success(strike)))
                .orElse(ResponseEntity.status(HttpStatus.NOT_FOUND)
                        .body(ApiResponse.error("ATM strike not in cache for: " + instrumentType)));
    }

    // ==================== ORIGINAL ENDPOINTS ====================

    @GetMapping("/quote")
    @Operation(summary = "Get quote for instruments",
               description = "Fetch full market quote including bid/ask, volume, and other details. Provide either 'instruments' array param or 'symbols' comma-separated.")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Quotes fetched successfully"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "Missing instruments/symbols parameter or invalid X-User-Id"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "500", description = "Kite API error")
    })
    public ResponseEntity<ApiResponse<Map<String, Quote>>> getQuote(
            @Parameter(description = "Array of instrument identifiers (e.g., NSE:NIFTY 50)", example = "NSE:NIFTY 50")
            @RequestParam(name = "instruments", required = false) String[] instruments,
            @Parameter(description = "Comma-separated instrument symbols", example = "NSE:NIFTY 50,NSE:BANKNIFTY")
            @RequestParam(name = "symbols", required = false) String symbols)
            throws KiteException, IOException {
        String[] resolved = resolveSymbols(instruments, symbols);
        if (resolved == null || resolved.length == 0) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(ApiResponse.error("Missing query parameter: provide either instruments[]=... or symbols=SYMB1,SYMB2"));
        }
        Map<String, Quote> quotes = tradingService.getQuote(resolved);
        return ResponseEntity.ok(ApiResponse.success(quotes));
    }

    @GetMapping("/ohlc")
    @Operation(summary = "Get OHLC data for instruments",
               description = "Fetch Open, High, Low, Close data. Provide either 'instruments' array param or 'symbols' comma-separated.")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "OHLC data fetched successfully"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "Missing instruments/symbols parameter"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "500", description = "Kite API error")
    })
    public ResponseEntity<ApiResponse<Map<String, OHLCQuote>>> getOHLC(
            @Parameter(description = "Array of instrument identifiers", example = "NSE:NIFTY 50")
            @RequestParam(name = "instruments", required = false) String[] instruments,
            @Parameter(description = "Comma-separated instrument symbols", example = "NSE:NIFTY 50,NSE:BANKNIFTY")
            @RequestParam(name = "symbols", required = false) String symbols)
            throws KiteException, IOException {
        String[] resolved = resolveSymbols(instruments, symbols);
        if (resolved == null || resolved.length == 0) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(ApiResponse.error("Missing query parameter: provide either instruments[]=... or symbols=SYMB1,SYMB2"));
        }
        Map<String, OHLCQuote> ohlc = tradingService.getOHLC(resolved);
        return ResponseEntity.ok(ApiResponse.success(ohlc));
    }

    @GetMapping("/ltp")
    @Operation(summary = "Get Last Traded Price (LTP) for instruments",
               description = "Fetch current last traded price. Provide either 'instruments' array param or 'symbols' comma-separated.")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "LTP data fetched successfully"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "Missing instruments/symbols parameter"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "500", description = "Kite API error")
    })
    public ResponseEntity<ApiResponse<Map<String, LTPQuote>>> getLTP(
            @Parameter(description = "Array of instrument identifiers", example = "NSE:NIFTY 50")
            @RequestParam(name = "instruments", required = false) String[] instruments,
            @Parameter(description = "Comma-separated instrument symbols", example = "NSE:NIFTY 50,NSE:BANKNIFTY")
            @RequestParam(name = "symbols", required = false) String symbols)
            throws KiteException, IOException {
        String[] resolved = resolveSymbols(instruments, symbols);
        if (resolved == null || resolved.length == 0) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(ApiResponse.error("Missing query parameter: provide either instruments[]=... or symbols=SYMB1,SYMB2"));
        }
        Map<String, LTPQuote> ltp = tradingService.getLTP(resolved);
        return ResponseEntity.ok(ApiResponse.success(ltp));
    }

    @GetMapping("/historical")
    @Operation(summary = "Get historical data for an instrument",
               description = "Fetch historical candle data for specified date range and interval")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Historical data fetched successfully"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "Invalid date format or missing parameters"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "500", description = "Kite API error")
    })
    public ResponseEntity<ApiResponse<HistoricalData>> getHistoricalData(
            @Parameter(description = "Instrument token identifier", required = true, example = "256265")
            @RequestParam String instrumentToken,
            @Parameter(description = "Start date (yyyy-MM-dd)", required = true, example = "2026-03-01")
            @RequestParam String from,
            @Parameter(description = "End date (yyyy-MM-dd)", required = true, example = "2026-03-14")
            @RequestParam String to,
            @Parameter(description = "Candle interval: minute, 3minute, 5minute, 15minute, 30minute, 60minute, day", required = true, example = "minute")
            @RequestParam String interval,
            @Parameter(description = "Whether to fetch continuous data for futures", example = "false")
            @RequestParam(defaultValue = "false") boolean continuous,
            @Parameter(description = "Whether to include Open Interest data", example = "false")
            @RequestParam(defaultValue = "false") boolean oi)
            throws KiteException, IOException, DateTimeParseException {

        Date fromDate = parseDate(from);
        Date toDate = parseDate(to);

        HistoricalData historicalData = tradingService.getHistoricalData(
                fromDate, toDate, instrumentToken, interval, continuous, oi);
        return ResponseEntity.ok(ApiResponse.success(historicalData));
    }

    @GetMapping("/instruments")
    @Operation(summary = "Get all instruments",
               description = "Fetch complete list of available instruments across all exchanges")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Instruments fetched successfully"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "500", description = "Kite API error")
    })
    public ResponseEntity<ApiResponse<List<Instrument>>> getInstruments() throws KiteException, IOException {
        List<Instrument> instruments = instrumentCacheService.getAllInstruments();
        return ResponseEntity.ok(ApiResponse.success(instruments));
    }

    @GetMapping("/instruments/{exchange}")
    @Operation(summary = "Get instruments for a specific exchange",
               description = "Fetch instruments for specified exchange (e.g., NSE, NFO, BSE)")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Instruments fetched successfully"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "Invalid exchange"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "500", description = "Kite API error")
    })
    public ResponseEntity<ApiResponse<List<Instrument>>> getInstrumentsByExchange(
            @Parameter(description = "Exchange segment: NSE, NFO, BSE, BFO, MCX, CDS", required = true, example = "NFO")
            @PathVariable String exchange)
            throws KiteException, IOException {
        List<Instrument> instruments = instrumentCacheService.getInstruments(exchange.toUpperCase());
        return ResponseEntity.ok(ApiResponse.success(instruments));
    }

    /**
     * Parse date string in yyyy-MM-dd format to java.util.Date
     */
    private Date parseDate(String dateStr) throws DateTimeParseException {
        LocalDate localDate = LocalDate.parse(dateStr);
        return Date.from(localDate.atStartOfDay(IST).toInstant());
    }

    /**
     * Resolve instruments from either array parameter or comma-separated 'symbols'. Trims whitespace.
     */
    private String[] resolveSymbols(String[] instruments, String symbols) {
        if (instruments != null && instruments.length > 0) {
            return instruments;
        }
        if (symbols != null && !symbols.isBlank()) {
            return java.util.Arrays.stream(symbols.split(","))
                    .map(String::trim)
                    .filter(s -> !s.isEmpty())
                    .toArray(String[]::new);
        }
        return null;
    }
}
