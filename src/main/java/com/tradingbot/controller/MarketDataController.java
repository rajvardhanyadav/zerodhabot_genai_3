package com.tradingbot.controller;

import com.tradingbot.dto.ApiResponse;
import com.tradingbot.service.TradingService;
import com.zerodhatech.kiteconnect.kitehttp.exceptions.KiteException;
import com.zerodhatech.models.*;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
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
@RequiredArgsConstructor
@Slf4j
@Tag(name = "Market Data", description = "Market data and quotes endpoints")
public class MarketDataController {

    private final TradingService tradingService;

    @GetMapping("/quote")
    @Operation(summary = "Get quote for instruments",
               description = "Fetch full market quote including bid/ask, volume, and other details. Provide either 'instruments' array param or 'symbols' comma-separated.")
    public ResponseEntity<ApiResponse<Map<String, Quote>>> getQuote(
            @RequestParam(name = "instruments", required = false) String[] instruments,
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
    public ResponseEntity<ApiResponse<Map<String, OHLCQuote>>> getOHLC(
            @RequestParam(name = "instruments", required = false) String[] instruments,
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
    public ResponseEntity<ApiResponse<Map<String, LTPQuote>>> getLTP(
            @RequestParam(name = "instruments", required = false) String[] instruments,
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
    public ResponseEntity<ApiResponse<HistoricalData>> getHistoricalData(
            @RequestParam String instrumentToken,
            @RequestParam String from,
            @RequestParam String to,
            @RequestParam String interval,
            @RequestParam(defaultValue = "false") boolean continuous,
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
    public ResponseEntity<ApiResponse<List<Instrument>>> getInstruments() throws KiteException, IOException {
        List<Instrument> instruments = tradingService.getInstruments();
        return ResponseEntity.ok(ApiResponse.success(instruments));
    }

    @GetMapping("/instruments/{exchange}")
    @Operation(summary = "Get instruments for a specific exchange",
               description = "Fetch instruments for specified exchange (e.g., NSE, NFO, BSE)")
    public ResponseEntity<ApiResponse<List<Instrument>>> getInstrumentsByExchange(@PathVariable String exchange)
            throws KiteException, IOException {
        List<Instrument> instruments = tradingService.getInstruments(exchange);
        return ResponseEntity.ok(ApiResponse.success(instruments));
    }

    /**
     * Parse date string in yyyy-MM-dd format to java.util.Date
     */
    private Date parseDate(String dateStr) throws DateTimeParseException {
        LocalDate localDate = LocalDate.parse(dateStr);
        return Date.from(localDate.atStartOfDay(ZoneId.systemDefault()).toInstant());
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
