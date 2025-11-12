package com.tradingbot.controller;

import com.tradingbot.dto.ApiResponse;
import com.tradingbot.service.TradingService;
import com.zerodhatech.kiteconnect.kitehttp.exceptions.KiteException;
import com.zerodhatech.models.*;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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
               description = "Fetch full market quote including bid/ask, volume, and other details")
    public ResponseEntity<ApiResponse<Map<String, Quote>>> getQuote(@RequestParam String[] instruments)
            throws KiteException, IOException {
        Map<String, Quote> quotes = tradingService.getQuote(instruments);
        return ResponseEntity.ok(ApiResponse.success(quotes));
    }

    @GetMapping("/ohlc")
    @Operation(summary = "Get OHLC data for instruments",
               description = "Fetch Open, High, Low, Close data for given instruments")
    public ResponseEntity<ApiResponse<Map<String, OHLCQuote>>> getOHLC(@RequestParam String[] instruments)
            throws KiteException, IOException {
        Map<String, OHLCQuote> ohlc = tradingService.getOHLC(instruments);
        return ResponseEntity.ok(ApiResponse.success(ohlc));
    }

    @GetMapping("/ltp")
    @Operation(summary = "Get Last Traded Price (LTP) for instruments",
               description = "Fetch current last traded price for given instruments")
    public ResponseEntity<ApiResponse<Map<String, LTPQuote>>> getLTP(@RequestParam String[] instruments)
            throws KiteException, IOException {
        Map<String, LTPQuote> ltp = tradingService.getLTP(instruments);
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
}
